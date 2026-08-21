package com.taspa.server.billing

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.billing.InvoiceLineRepository
import com.taspa.server.domain.billing.InvoiceRepository
import com.taspa.server.domain.meal.MealTransaction
import com.taspa.server.domain.meal.MealTransactionRepository
import com.taspa.server.domain.meal.Merchant
import com.taspa.server.domain.meal.MerchantRepository
import com.taspa.server.domain.org.Department
import com.taspa.server.domain.org.DepartmentRepository
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.stepup.StepUp
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * 정산 집계(청구서 draft→finalize) 통합 테스트.
 *  - 집계 정확성: APPROVED 만(VOIDED 제외), 조직부담(amount−selfPaid)만, 사용자 라인 + 부서 스냅샷.
 *  - org 타임존 월 경계: KST 익월 1일 00:30 거래(UTC 로는 전월 말일)가 전월 청구서에 안 들어간다.
 *  - draft 재생성(거래 추가 반영·라인 full-replace), finalize 후 generate/finalize 재호출 409·라인 불변.
 *  - 인가: 타 org ORG_ADMIN 403, 타 org 거래 미혼입, step-up(오래된 auth_time → 401), 미인증 401.
 *  - period 검증: 형식 위반·미래 월 400.
 *
 * 시각 결정성: 당월 테스트는 Instant.now()(KST 당월 확정), 경계 테스트는 2개월 전 달의 KST 월 경계를
 * 계산해 고정 오프셋으로 시드한다 — 실행 시각과 무관하게 항상 과거 월이라 미래 월 400 에 안 걸린다.
 */
class InvoiceIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var departmentRepository: DepartmentRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var merchantRepository: MerchantRepository

    @Autowired lateinit var transactionRepository: MealTransactionRepository

    @Autowired lateinit var invoiceRepository: InvoiceRepository

    @Autowired lateinit var invoiceLineRepository: InvoiceLineRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var objectMapper: ObjectMapper

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val password = "SecureP@ssw0rd123"
    private val seoul: ZoneId = ZoneId.of("Asia/Seoul")

    private lateinit var orgId: UUID
    private lateinit var otherOrgId: UUID
    private lateinit var adminEmail: String
    private lateinit var otherAdminEmail: String
    private lateinit var member1Id: UUID
    private lateinit var member2Id: UUID
    private lateinit var otherMemberId: UUID
    private lateinit var deptId: UUID
    private lateinit var merchantId: UUID

    @BeforeEach
    fun setUp() {
        invoiceLineRepository.deleteAll()
        invoiceRepository.deleteAll()
        transactionRepository.deleteAll()
        merchantRepository.deleteAll()
        membershipRepository.deleteAll()
        departmentRepository.deleteAllInBatch()
        organizationRepository.deleteAll()
        userRepository.deleteAll()

        orgId =
            organizationRepository
                .save(
                    Organization(slug = "inv-org", name = "Invoice Org", timezone = "Asia/Seoul"),
                ).id!!
        otherOrgId =
            organizationRepository
                .save(
                    Organization(slug = "inv-other", name = "Other Org", timezone = "Asia/Seoul"),
                ).id!!

        adminEmail = "inv-admin@example.com"
        val adminId = saveUser(adminEmail)
        membershipRepository.save(OrgMembership(orgId = orgId, userId = adminId, role = "ORG_ADMIN"))

        otherAdminEmail = "inv-other-admin@example.com"
        val otherAdminId = saveUser(otherAdminEmail)
        membershipRepository.save(OrgMembership(orgId = otherOrgId, userId = otherAdminId, role = "ORG_ADMIN"))

        deptId = departmentRepository.save(Department(orgId = orgId, name = "개발팀")).id!!
        member1Id = saveUser("inv-m1@example.com")
        membershipRepository.save(OrgMembership(orgId = orgId, userId = member1Id, departmentId = deptId))
        member2Id = saveUser("inv-m2@example.com")
        membershipRepository.save(OrgMembership(orgId = orgId, userId = member2Id))
        otherMemberId = saveUser("inv-om@example.com")
        membershipRepository.save(OrgMembership(orgId = otherOrgId, userId = otherMemberId))

        merchantId = merchantRepository.save(Merchant(name = "청구 식당")).id!!
    }

    // ---- 집계 정확성 ----

    @Test
    fun `APPROVED 만 조직부담으로 집계되고 라인에 사용자·부서가 스냅샷된다`() {
        val period = YearMonth.now(seoul).toString()
        seedTxn(orgId, member1Id, amount = 10000, selfPaid = 0)
        seedTxn(orgId, member1Id, amount = 15000, selfPaid = 3000)
        seedTxn(orgId, member2Id, amount = 9000, selfPaid = 1000)
        seedTxn(orgId, member2Id, amount = 8000, selfPaid = 0, status = "VOIDED") // 제외돼야 한다

        val session = login(adminEmail)
        generate(session, orgId, period)
            .andExpect(status().isOk)
            // 10000 + (15000−3000) + (9000−1000) = 30000, VOIDED 제외 → 3건
            .andExpect(jsonPath("$.period").value(period))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.subtotalMinor").value(30000))
            .andExpect(jsonPath("$.txnCount").value(3))
            .andExpect(jsonPath("$.lines.length()").value(2))
            // 라인은 email 오름차순 — m1(부서 스냅샷), m2(부서 없음 null)
            .andExpect(jsonPath("$.lines[0].userEmail").value("inv-m1@example.com"))
            .andExpect(jsonPath("$.lines[0].txnCount").value(2))
            .andExpect(jsonPath("$.lines[0].amountMinor").value(22000))
            .andExpect(jsonPath("$.lines[0].departmentId").value(deptId.toString()))
            .andExpect(jsonPath("$.lines[0].departmentName").value("개발팀"))
            .andExpect(jsonPath("$.lines[1].userEmail").value("inv-m2@example.com"))
            .andExpect(jsonPath("$.lines[1].amountMinor").value(8000))
            .andExpect(jsonPath("$.lines[1].departmentId").doesNotExist())
            // 부서 소계 — 이름순, 미배정(null) 그룹은 뒤
            .andExpect(jsonPath("$.departmentSubtotals.length()").value(2))
            .andExpect(jsonPath("$.departmentSubtotals[0].departmentName").value("개발팀"))
            .andExpect(jsonPath("$.departmentSubtotals[0].amountMinor").value(22000))
            .andExpect(jsonPath("$.departmentSubtotals[1].departmentName").doesNotExist())
            .andExpect(jsonPath("$.departmentSubtotals[1].amountMinor").value(8000))

        val invoice = invoiceRepository.findAll().single()
        assertThat(invoice.subtotalMinor).isEqualTo(30000)
        assertThat(invoice.txnCount).isEqualTo(3)
        assertThat(invoiceLineRepository.findAll()).hasSize(2)
    }

    @Test
    fun `org 타임존 월 경계 — KST 익월 1일 00시30분 거래는 전월 청구서에 들어가지 않는다`() {
        // 2개월 전 달 M 과 M+1 의 KST 경계. UTC 절단이라면 boundary+30m(UTC 로는 M 말일 15:30)이
        // M 청구서에 오귀속된다 — org 타임존 앵커가 그것을 막는지를 검증한다.
        val monthM = YearMonth.now(seoul).minusMonths(2)
        val boundary =
            monthM
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(seoul)
                .toInstant()
        seedTxn(orgId, member1Id, amount = 10000, selfPaid = 0, approvedAt = boundary.minusSeconds(3600)) // M 말일 23:00 KST
        seedTxn(orgId, member1Id, amount = 7000, selfPaid = 0, approvedAt = boundary.plusSeconds(1800)) // M+1 1일 00:30 KST

        val session = login(adminEmail)
        generate(session, orgId, monthM.toString())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.subtotalMinor").value(10000))
            .andExpect(jsonPath("$.txnCount").value(1))
        generate(session, orgId, monthM.plusMonths(1).toString())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.subtotalMinor").value(7000))
            .andExpect(jsonPath("$.txnCount").value(1))
    }

    // ---- draft 재생성 ----

    @Test
    fun `DRAFT 는 거래 추가 후 재생성하면 라인이 갱신되고 청구서 행은 하나만 유지된다`() {
        val period = YearMonth.now(seoul).toString()
        seedTxn(orgId, member1Id, amount = 10000, selfPaid = 0)
        val session = login(adminEmail)
        generate(session, orgId, period)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.subtotalMinor").value(10000))

        seedTxn(orgId, member2Id, amount = 6000, selfPaid = 0)
        generate(session, orgId, period)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.subtotalMinor").value(16000))
            .andExpect(jsonPath("$.txnCount").value(2))
            .andExpect(jsonPath("$.lines.length()").value(2))

        assertThat(invoiceRepository.count()).isEqualTo(1)
        assertThat(invoiceLineRepository.count()).isEqualTo(2)
    }

    // ---- finalize 불변화 ----

    @Test
    fun `finalize 후에는 generate·재-finalize 모두 409 이고 라인은 불변이다`() {
        val period = YearMonth.now(seoul).toString()
        seedTxn(orgId, member1Id, amount = 10000, selfPaid = 0)
        val session = login(adminEmail)
        val invoiceId =
            generate(session, orgId, period)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
                .let { objectMapper.readTree(it).get("id").asText() }

        session
            .perform(post("/api/orgs/{orgId}/invoices/{id}/finalize", orgId, invoiceId).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("FINALIZED"))
            .andExpect(jsonPath("$.finalizedAt").exists())

        // 확정 후 재생성 거절 — 거래를 더 넣어도 라인·합계가 변하지 않는다.
        seedTxn(orgId, member2Id, amount = 5000, selfPaid = 0)
        generate(session, orgId, period)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("INVOICE_ALREADY_FINALIZED"))
        session
            .perform(post("/api/orgs/{orgId}/invoices/{id}/finalize", orgId, invoiceId).with(csrf()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("INVOICE_ALREADY_FINALIZED"))

        val invoice = invoiceRepository.findAll().single()
        assertThat(invoice.status).isEqualTo("FINALIZED")
        assertThat(invoice.subtotalMinor).isEqualTo(10000)
        assertThat(invoiceLineRepository.findAll().single().amountMinor).isEqualTo(10000)
    }

    @Test
    fun `생성 이후 void 된 stale draft 는 finalize 가 409 이고 재생성 후에만 확정된다`() {
        val period = YearMonth.now(seoul).toString()
        val txnA = seedTxn(orgId, member1Id, amount = 10000, selfPaid = 0)
        seedTxn(orgId, member2Id, amount = 20000, selfPaid = 0)
        val session = login(adminEmail)
        val invoiceId =
            generate(session, orgId, period)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.subtotalMinor").value(30000))
                .andReturn()
                .response.contentAsString
                .let { objectMapper.readTree(it).get("id").asText() }

        // draft 생성 후 가맹점이 거래 A 를 void — 재생성 없이 확정하면 10000 과다 청구로 영구히 굳는다.
        txnA.status = "VOIDED"
        txnA.voidedAt = Instant.now()
        transactionRepository.save(txnA)

        session
            .perform(post("/api/orgs/{orgId}/invoices/{id}/finalize", orgId, invoiceId).with(csrf()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("INVOICE_STALE"))
        assertThat(invoiceRepository.findAll().single().status).isEqualTo("DRAFT")

        // 재생성으로 void 반영 후에는 확정이 성공한다.
        generate(session, orgId, period)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.subtotalMinor").value(20000))
        session
            .perform(post("/api/orgs/{orgId}/invoices/{id}/finalize", orgId, invoiceId).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("FINALIZED"))
            .andExpect(jsonPath("$.subtotalMinor").value(20000))
    }

    @Test
    fun `org 타임존 변경 후에도 확정된 전월 창과 익월 창 사이에 미청구 갭이 생기지 않는다`() {
        // monthM 을 Asia/Seoul 로 확정 → org 타임존을 UTC 로 변경 → 두 앵커 사이 구간
        // [KST M+1월 1일 00:00, UTC M+1월 1일 00:00) 의 거래가 M+1 청구서에 들어가야 한다.
        val monthM = YearMonth.now(seoul).minusMonths(2)
        val kstBoundary =
            monthM
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(seoul)
                .toInstant()
        seedTxn(orgId, member1Id, amount = 10000, selfPaid = 0, approvedAt = kstBoundary.minusSeconds(3600))

        val session = login(adminEmail)
        val invoiceId =
            generate(session, orgId, monthM.toString())
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
                .let { objectMapper.readTree(it).get("id").asText() }
        session
            .perform(post("/api/orgs/{orgId}/invoices/{id}/finalize", orgId, invoiceId).with(csrf()))
            .andExpect(status().isOk)

        val org = organizationRepository.findById(orgId).orElseThrow()
        org.timezone = "UTC"
        organizationRepository.save(org)

        // KST 앵커(확정 창 끝)와 UTC 앵커 사이 9시간 구간의 거래 — 정합이 없으면 어느 청구서에도 안 들어간다.
        seedTxn(orgId, member2Id, amount = 7000, selfPaid = 0, approvedAt = kstBoundary.plusSeconds(1800))
        generate(session, orgId, monthM.plusMonths(1).toString())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.subtotalMinor").value(7000))
            .andExpect(jsonPath("$.txnCount").value(1))
    }

    // ---- 인가·격리 ----

    @Test
    fun `★청구서 CSV 는 화면과 같은 라인을 내고 타 org 는 403 이다`() {
        // 회계팀이 ERP 로 옮기는 경로 — 형식만 다를 뿐 권한도 숫자도 화면과 같아야 한다.
        val period = YearMonth.now(seoul).toString()
        seedTxn(orgId, member1Id, amount = 10000, selfPaid = 0)
        val session = login(adminEmail)
        val detail =
            generate(session, orgId, period)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val invoiceId = objectMapper.readTree(detail).get("id").asText()

        val body =
            session
                .perform(get("/api/orgs/{orgId}/invoices/{id}/csv", orgId, invoiceId))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .getContentAsString(Charsets.UTF_8)

        assertThat(body).contains("조직부담(원)")
        assertThat(body).contains("inv-m1@example.com")
        assertThat(body).contains("10000")
        assertThat(body).contains("개발팀")
        // 엑셀 한글 깨짐 방지 BOM — 없으면 받는 사람이 조용히 깨진 파일을 본다.
        assertThat(body.first()).isEqualTo('\uFEFF')

        // 형식이 테넌시를 우회하지 않는다.
        login(otherAdminEmail)
            .perform(get("/api/orgs/{orgId}/invoices/{id}/csv", orgId, invoiceId))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `타 org 의 ORG_ADMIN 은 403 이고 타 org 거래는 혼입되지 않는다`() {
        val period = YearMonth.now(seoul).toString()
        seedTxn(orgId, member1Id, amount = 10000, selfPaid = 0)
        seedTxn(otherOrgId, otherMemberId, amount = 99000, selfPaid = 0)

        // 타 org 관리자는 생성·목록 모두 403.
        val otherSession = login(otherAdminEmail)
        generate(otherSession, orgId, period).andExpect(status().isForbidden)
        otherSession.perform(get("/api/orgs/{orgId}/invoices", orgId)).andExpect(status().isForbidden)

        // 자기 org 집계에는 타 org 거래가 섞이지 않는다.
        login(adminEmail).let { session ->
            generate(session, orgId, period)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.subtotalMinor").value(10000))
                .andExpect(jsonPath("$.txnCount").value(1))
        }
    }

    @Test
    fun `오래된 auth_time 이면 generate 는 REAUTH_REQUIRED 로 거절된다(step-up)`() {
        val session = login(adminEmail)
        session.setAttribute(StepUp.AUTH_TIME_SESSION_KEY, Instant.now().minus(Duration.ofMinutes(11)))
        generate(session, orgId, YearMonth.now(seoul).toString())
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"))
    }

    @Test
    fun `미인증 요청은 401 이다`() {
        mockMvc
            .perform(
                post("/api/orgs/{orgId}/invoices/generate", orgId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"period":"${YearMonth.now(seoul)}"}""")
                    .with(csrf()),
            ).andExpect(status().isUnauthorized)
    }

    // ---- period 검증 ----

    @Test
    fun `period 형식 위반과 미래 월은 400 이다`() {
        val session = login(adminEmail)
        generate(session, orgId, "2026-13")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
        generate(session, orgId, "202606")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
        generate(session, orgId, YearMonth.now(seoul).plusMonths(1).toString())
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
        assertThat(invoiceRepository.count()).isZero()
    }

    @Test
    fun `period 누락·null 본문은 500 이 아니라 400 VALIDATION_ERROR 다`() {
        val session = login(adminEmail)
        listOf("{}", """{"period":null}""").forEach { body ->
            session
                .perform(
                    post("/api/orgs/{orgId}/invoices/generate", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
        }
        assertThat(invoiceRepository.count()).isZero()
    }

    // ---- 헬퍼 ----

    private fun saveUser(email: String): UUID =
        userRepository
            .save(
                User(email = email, passwordHash = passwordEncoder.encode(password), emailVerified = true),
            ).id!!

    private fun seedTxn(
        org: UUID,
        user: UUID,
        amount: Long,
        selfPaid: Long,
        approvedAt: Instant = Instant.now(),
        status: String = "APPROVED",
    ): MealTransaction =
        transactionRepository.save(
            MealTransaction(
                authId = UUID.randomUUID().toString(),
                orgId = org,
                userId = user,
                merchantId = merchantId,
                amountMinor = amount,
                selfPaidMinor = selfPaid,
                mealWindow = "LUNCH",
                status = status,
                posTxnId = UUID.randomUUID().toString(),
                approvedAt = approvedAt,
                voidedAt = if (status == "VOIDED") approvedAt else null,
            ),
        )

    private fun generate(
        session: WebSession,
        org: UUID,
        period: String,
    ): ResultActions =
        session.perform(
            post("/api/orgs/{orgId}/invoices/generate", org)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"period":"$period"}""")
                .with(csrf()),
        )

    private fun login(email: String): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", email).with(csrf()))
        session
            .perform(post("/login/password").param("username", email).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
        return session
    }
}
