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
import com.taspa.server.domain.user.UserRole
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
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * 진행 중인 달 식대 집계(GET /api/orgs/{orgId}/spend) 통합 테스트.
 *
 * 이 API 의 존재 이유는 "청구서를 만들기 전에 청구서와 같은 숫자를 미리 본다"이므로, 가장 중요한 단언은
 * **청구서 subtotal 과 spend 의 조직부담 합계가 같다**는 것이다(창 계산·집계 질의 공유의 회귀 방지).
 * 그다음이 부수효과 0(invoice 행 수 불변) — 조회가 문서를 굳히면 이 화면을 상시로 열 수 없다.
 *
 * 시각 결정성은 InvoiceIntegrationTest 와 같은 방식이다 — 당월 테스트는 Instant.now(), 경계 테스트는
 * 2개월 전 달의 KST 경계를 고정 오프셋으로 시드한다(항상 과거 월이라 미래 월 400 에 걸리지 않는다).
 */
class OrgSpendIntegrationTest : IntegrationTestBase() {
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
    private lateinit var memberEmail: String
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
                    Organization(slug = "spend-org", name = "Spend Org", timezone = "Asia/Seoul"),
                ).id!!
        otherOrgId =
            organizationRepository
                .save(
                    Organization(slug = "spend-other", name = "Other Org", timezone = "Asia/Seoul"),
                ).id!!

        adminEmail = "spend-admin@example.com"
        membershipRepository.save(
            OrgMembership(orgId = orgId, userId = saveUser(adminEmail), role = "ORG_ADMIN"),
        )
        otherAdminEmail = "spend-other-admin@example.com"
        membershipRepository.save(
            OrgMembership(orgId = otherOrgId, userId = saveUser(otherAdminEmail), role = "ORG_ADMIN"),
        )

        deptId = departmentRepository.save(Department(orgId = orgId, name = "개발팀")).id!!

        // 일반 멤버(비관리자) — 같은 org 소속이어도 금액 표면에는 닿지 못해야 한다.
        memberEmail = "spend-m1@example.com"
        member1Id = saveUser(memberEmail)
        membershipRepository.save(OrgMembership(orgId = orgId, userId = member1Id, departmentId = deptId))

        member2Id = saveUser("spend-m2@example.com")
        membershipRepository.save(OrgMembership(orgId = orgId, userId = member2Id))
        otherMemberId = saveUser("spend-om@example.com")
        membershipRepository.save(OrgMembership(orgId = otherOrgId, userId = otherMemberId))

        merchantId = merchantRepository.save(Merchant(name = "집계 식당")).id!!
    }

    // ---- 집계 정확성 ----

    @Test
    fun `APPROVED 만 집계되고 VOIDED 는 빠지며 부서별로 분해된다`() {
        val period = YearMonth.now(seoul).toString()
        seedTxn(orgId, member1Id, amount = 10000, selfPaid = 0)
        seedTxn(orgId, member1Id, amount = 15000, selfPaid = 3000)
        seedTxn(orgId, member2Id, amount = 9000, selfPaid = 1000)
        seedTxn(orgId, member2Id, amount = 8000, selfPaid = 0, status = "VOIDED") // 제외돼야 한다
        seedTxn(otherOrgId, otherMemberId, amount = 99000, selfPaid = 0) // 타 org 는 혼입 금지

        spend(login(adminEmail), orgId, period)
            .andExpect(status().isOk)
            // 10000 + (15000−3000) + (9000−1000) = 30000, VOIDED·타 org 제외 → 3건
            .andExpect(jsonPath("$.period").value(period))
            .andExpect(jsonPath("$.timezone").value("Asia/Seoul"))
            .andExpect(jsonPath("$.orgPaidMinor").value(30000))
            .andExpect(jsonPath("$.selfPaidMinor").value(4000))
            .andExpect(jsonPath("$.txnCount").value(3))
            .andExpect(jsonPath("$.inProgress").value(true))
            // 아직 청구서를 만들지 않았다.
            .andExpect(jsonPath("$.invoice").doesNotExist())
            // 부서 분해는 금액 큰 순 — 개발팀(22000) > 미배정(8000). 개인 라인은 없다.
            .andExpect(jsonPath("$.departments.length()").value(2))
            .andExpect(jsonPath("$.departments[0].departmentName").value("개발팀"))
            .andExpect(jsonPath("$.departments[0].orgPaidMinor").value(22000))
            .andExpect(jsonPath("$.departments[0].txnCount").value(2))
            .andExpect(jsonPath("$.departments[1].departmentId").doesNotExist())
            .andExpect(jsonPath("$.departments[1].orgPaidMinor").value(8000))
            .andExpect(jsonPath("$.lines").doesNotExist())
    }

    @Test
    fun `실효 창은 org 타임존 월 경계다 — KST 익월 1일 00시30분 거래는 전월 집계에 안 들어간다`() {
        val monthM = YearMonth.now(seoul).minusMonths(2)
        val boundary =
            monthM
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(seoul)
                .toInstant()
        seedTxn(orgId, member1Id, amount = 10000, selfPaid = 0, approvedAt = boundary.minusSeconds(3600))
        seedTxn(orgId, member1Id, amount = 7000, selfPaid = 0, approvedAt = boundary.plusSeconds(1800))

        val session = login(adminEmail)
        // UTC 절단이라면 boundary+30m(UTC 로는 M 말일 15:30)이 M 집계에 오귀속된다.
        val body =
            objectMapper.readTree(
                spend(session, orgId, monthM.toString())
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.orgPaidMinor").value(10000))
                    .andExpect(jsonPath("$.txnCount").value(1))
                    .andExpect(jsonPath("$.inProgress").value(false))
                    .andReturn()
                    .response.contentAsString,
            )
        // 실효 창이 청구서 스냅샷 창과 대조 가능해야 한다 — KST 월 경계 그대로여야 한다.
        assertThat(Instant.parse(body.get("periodStart").asText()))
            .isEqualTo(monthM.atDay(1).atStartOfDay(seoul).toInstant())
        assertThat(Instant.parse(body.get("periodEnd").asText())).isEqualTo(boundary)

        spend(session, orgId, monthM.plusMonths(1).toString())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.orgPaidMinor").value(7000))
            .andExpect(jsonPath("$.txnCount").value(1))
    }

    @Test
    fun `period 를 생략하면 org 타임존 기준 이번 달을 집계한다`() {
        seedTxn(orgId, member1Id, amount = 12000, selfPaid = 2000)
        login(adminEmail)
            .perform(get("/api/orgs/{orgId}/spend", orgId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.period").value(YearMonth.now(seoul).toString()))
            .andExpect(jsonPath("$.orgPaidMinor").value(10000))
    }

    @Test
    fun `전월 동기간 비교가 함께 온다`() {
        val monthM = YearMonth.now(seoul).minusMonths(2)
        val previousMonth = monthM.minusMonths(1)
        seedTxn(orgId, member1Id, amount = 20000, selfPaid = 0, approvedAt = monthM.atDay(2).atStartOfDay(seoul).toInstant())
        seedTxn(orgId, member1Id, amount = 10000, selfPaid = 0, approvedAt = previousMonth.atDay(2).atStartOfDay(seoul).toInstant())

        spend(login(adminEmail), orgId, monthM.toString())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.orgPaidMinor").value(20000))
            .andExpect(jsonPath("$.previous.period").value(previousMonth.toString()))
            .andExpect(jsonPath("$.previous.orgPaidMinor").value(10000))
            // (20000 − 10000) / 10000 = 1.0
            .andExpect(jsonPath("$.previous.changeRatio").value(1.0))
    }

    // ---- 청구서와의 정합 ----

    @Test
    fun `청구서 subtotal 과 조직부담 합계가 일치하고 조회는 청구서를 만들지 않는다`() {
        val period = YearMonth.now(seoul).toString()
        seedTxn(orgId, member1Id, amount = 10000, selfPaid = 0)
        seedTxn(orgId, member1Id, amount = 15000, selfPaid = 3000)
        seedTxn(orgId, member2Id, amount = 9000, selfPaid = 1000)
        seedTxn(orgId, member2Id, amount = 8000, selfPaid = 0, status = "VOIDED")

        val session = login(adminEmail)

        // 1) 조회는 부수효과가 없다 — 몇 번을 불러도 청구서 행이 생기지 않는다.
        repeat(2) { spend(session, orgId, period).andExpect(status().isOk) }
        assertThat(invoiceRepository.count()).isZero()
        assertThat(invoiceLineRepository.count()).isZero()

        // 2) 같은 기간으로 청구서를 만들면 두 숫자가 같아야 한다(이 API 의 존재 이유).
        val invoice =
            objectMapper.readTree(
                session
                    .perform(
                        post("/api/orgs/{orgId}/invoices/generate", orgId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"period":"$period"}""")
                            .with(csrf()),
                    ).andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )
        val subtotal = invoice.get("subtotalMinor").asLong()

        val body =
            objectMapper.readTree(
                spend(session, orgId, period)
                    .andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )
        assertThat(body.get("orgPaidMinor").asLong()).isEqualTo(subtotal)
        assertThat(body.get("txnCount").asInt()).isEqualTo(invoice.get("txnCount").asInt())
        // 창도 청구서 스냅샷과 대조 가능해야 한다.
        assertThat(body.get("periodStart").asText()).isNotBlank()
        assertThat(body.get("invoice").get("status").asText()).isEqualTo("DRAFT")
        assertThat(body.get("invoice").get("subtotalMinor").asLong()).isEqualTo(subtotal)

        // 3) 청구서 생성 이후에도 조회가 그 행을 갱신하지 않는다.
        val generatedAt = invoiceRepository.findAll().single().generatedAt
        spend(session, orgId, period).andExpect(status().isOk)
        assertThat(invoiceRepository.count()).isEqualTo(1)
        assertThat(invoiceRepository.findAll().single().generatedAt).isEqualTo(generatedAt)
        assertThat(invoiceRepository.findAll().single().status).isEqualTo("DRAFT")
    }

    // ---- 인가 ----

    @Test
    fun `일반 멤버는 403 이다`() {
        seedTxn(orgId, member1Id, amount = 10000, selfPaid = 0)
        spend(login(memberEmail), orgId, YearMonth.now(seoul).toString())
            .andExpect(status().isForbidden)
    }

    @Test
    fun `타 org 의 ORG_ADMIN 은 403 이다`() {
        spend(login(otherAdminEmail), orgId, YearMonth.now(seoul).toString())
            .andExpect(status().isForbidden)
    }

    @Test
    fun `미인증 요청은 401 이다`() {
        mockMvc
            .perform(get("/api/orgs/{orgId}/spend", orgId))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `비멤버 플랫폼 ADMIN 도 조회할 수 있다(파리티 역전 방지)`() {
        // ★이 표면이 정확히 CLAUDE.md 가 경고하는 함정 위에 있다. MealQrController 는 platformAdmin 을
        //   false 로 고정하는데, 그건 meal:IssueQr 이 PLATFORM_ADMIN_EXCLUDED_ACTIONS(명시 Deny)에
        //   있어서다 — 명시 Deny 는 멤버 Allow 도 이기므로 true 를 넘기면 멤버인 관리자까지 거부된다.
        //   billing:ReadSpend 는 제외 목록에 **없으므로** 그 관례를 복사하면 Allow 원천이 사라져
        //   플랫폼 관리자가 전원 403 이 된다. 이 단언이 없으면 누군가 "통일" 하면서 조용히 깨뜨린다.
        seedTxn(orgId, member1Id, amount = 10000, selfPaid = 0)
        val platformAdminEmail = "spend-platform-admin@example.com"
        userRepository.save(
            User(
                email = platformAdminEmail,
                passwordHash = passwordEncoder.encode(password),
                emailVerified = true,
                role = UserRole.ADMIN.name,
            ),
        )
        spend(login(platformAdminEmail), orgId, YearMonth.now(seoul).toString())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.orgPaidMinor").value(10000))
    }

    @Test
    fun `위임 베어러 토큰으로는 조회할 수 없다(세션 전용 표면)`() {
        // 조회라도 사람 세션 전용이다 — 제3자 앱 토큰이 조직 지출을 읽는 confused-deputy 를 닫는다.
        // 상태코드가 무엇이든 **도달하지 못한다**는 것이 여기서 잠그는 불변식이다.
        mockMvc
            .perform(
                get("/api/orgs/{orgId}/spend", orgId).header("Authorization", "Bearer not-a-real-token"),
            ).andExpect { result ->
                val status = result.response.status
                check(status == 401 || status == 403) { "세션 전용 표면인데 상태가 $status 다" }
            }
    }

    // ---- period 검증 ----

    @Test
    fun `period 형식 위반과 미래 월은 400 이다`() {
        val session = login(adminEmail)
        spend(session, orgId, "2026-13")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
        spend(session, orgId, YearMonth.now(seoul).plusMonths(1).toString())
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
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

    private fun spend(
        session: WebSession,
        org: UUID,
        period: String,
    ): ResultActions = session.perform(get("/api/orgs/{orgId}/spend", org).param("period", period))

    private fun login(email: String): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", email).with(csrf()))
        session
            .perform(post("/login/password").param("username", email).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
        return session
    }
}
