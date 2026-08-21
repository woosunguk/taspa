package com.taspa.server.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.audit.AuditEventRepository
import com.taspa.server.domain.login.LoginEventRepository
import com.taspa.server.domain.meal.MealQrTokenRepository
import com.taspa.server.domain.meal.MealTransactionRepository
import com.taspa.server.domain.meal.Merchant
import com.taspa.server.domain.meal.MerchantMember
import com.taspa.server.domain.meal.MerchantMemberRepository
import com.taspa.server.domain.meal.MerchantMemberStatus
import com.taspa.server.domain.meal.MerchantRepository
import com.taspa.server.domain.meal.MerchantRole
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.org.Site
import com.taspa.server.domain.org.SiteRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 가맹 관리 API(`/api/admin/merchants`) 통합 테스트.
 *
 * 여기서 잠그는 핵심은 **가맹 직원 부여가 매장 접근권을 발급하는 유일한 경로**라는 사실이다:
 * 발급 권한이 플랫폼에 머물러야(가맹 관리자 자신은 동료를 못 늘린다) 매장 신원이 자가 증식하지 않고,
 * 해제가 즉시 유효해야(세션에 굳지 않는다) 회수가 실효를 갖는다. merchant CRUD 는 그 발급 대상 자원의
 * 수명주기라 같은 파일에서 함께 고정한다(특히 timezone 미전송 = 유지 규약 — 소급 집계 이동 방지).
 */
class AdminMerchantConsoleIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var loginEventRepository: LoginEventRepository

    @Autowired lateinit var auditEventRepository: AuditEventRepository

    @Autowired lateinit var merchantRepository: MerchantRepository

    @Autowired lateinit var merchantMemberRepository: MerchantMemberRepository

    @Autowired lateinit var transactionRepository: MealTransactionRepository

    @Autowired lateinit var qrTokenRepository: MealQrTokenRepository

    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var siteRepository: SiteRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var objectMapper: ObjectMapper

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val password = "SecureP@ssw0rd123"
    private val adminEmail = "merchant-admin-console-platform@example.com"
    private val staffEmail = "merchant-admin-console-staff@example.com"
    private val outsiderEmail = "merchant-admin-console-outsider@example.com"

    private lateinit var adminId: UUID
    private lateinit var staffId: UUID
    private lateinit var outsiderId: UUID
    private lateinit var merchantId: UUID
    private lateinit var orgId: UUID

    @BeforeEach
    fun setUp() {
        transactionRepository.deleteAll()
        qrTokenRepository.deleteAll()
        merchantMemberRepository.deleteAll()
        merchantRepository.deleteAll()
        membershipRepository.deleteAll()
        siteRepository.deleteAll()
        organizationRepository.deleteAll()
        loginEventRepository.deleteAll()
        auditEventRepository.deleteAll()
        userRepository.deleteAll()

        adminId =
            userRepository
                .save(
                    User(
                        email = adminEmail,
                        passwordHash = passwordEncoder.encode(password),
                        emailVerified = true,
                        role = UserRole.ADMIN.name,
                    ),
                ).id!!
        staffId =
            userRepository
                .save(
                    User(email = staffEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
                ).id!!
        outsiderId =
            userRepository
                .save(
                    User(email = outsiderEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
                ).id!!

        orgId = organizationRepository.save(Organization(slug = "adm-merch", name = "Admin Merchant Org")).id!!
        merchantId = merchantRepository.save(Merchant(name = "가맹 본점")).id!!
    }

    // ---- 1. 발급 권한은 플랫폼에만 있다 ----

    @Test
    fun `일반 사용자 세션의 가맹 직원 부여는 403 이고 행이 생기지 않는다`() {
        val session = login(outsiderEmail)
        // csrf 를 실어야 CsrfFilter 가 먼저 403 을 내지 않아 "역할 때문에 막혔다"가 단언된다.
        session
            .perform(addMemberRequest(merchantId, staffEmail).with(csrf()))
            .andExpect(status().isForbidden)
        assertThat(merchantMemberRepository.findByMerchantIdAndUserId(merchantId, staffId)).isNull()
    }

    @Test
    fun `가맹 관리자도 자기 매장에 동료를 추가할 수 없다`() {
        // 매장 신원의 자가 증식 금지 — 부여는 플랫폼이 쥔다(가맹 관리자는 콘솔 조회만).
        merchantMemberRepository.save(MerchantMember(merchantId = merchantId, userId = staffId))
        val session = login(staffEmail)
        session
            .perform(get("/api/merchant-console/{merchantId}/transactions", merchantId))
            .andExpect(status().isOk)

        session
            .perform(addMemberRequest(merchantId, outsiderEmail).with(csrf()))
            .andExpect(status().isForbidden)
        session
            .perform(get("/api/admin/merchants/{merchantId}/members", merchantId))
            .andExpect(status().isForbidden)
        session
            .perform(
                delete("/api/admin/merchants/{merchantId}/members/{userId}", merchantId, staffId).with(csrf()),
            ).andExpect(status().isForbidden)

        assertThat(merchantMemberRepository.findByMerchantIdAndUserId(merchantId, outsiderId)).isNull()
    }

    // ---- 2. 부여의 입력 계약 ----

    @Test
    fun `존재하지 않는 이메일 부여는 400 이고 계정을 암묵 생성하지 않는다`() {
        val session = login(adminEmail)
        val before = userRepository.count()

        session
            .perform(addMemberRequest(merchantId, "ghost-merchant-staff@example.com").with(csrf()))
            .andExpect(status().isBadRequest)

        // 가맹 신원 부여가 계정 생성 경로가 되면 오타 하나가 유령 계정을 만든다.
        assertThat(userRepository.count()).isEqualTo(before)
        assertThat(merchantMemberRepository.findAll()).isEmpty()
    }

    @Test
    fun `빈 이메일 부여는 400 이다`() {
        val session = login(adminEmail)
        session
            .perform(addMemberRequest(merchantId, "   ").with(csrf()))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `존재하지 않는 가맹점에 부여하면 404 다`() {
        val session = login(adminEmail)
        session
            .perform(addMemberRequest(UUID.randomUUID(), staffEmail).with(csrf()))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `같은 이메일 재부여는 멱등이다(행 1개 유지)`() {
        val session = login(adminEmail)
        session
            .perform(addMemberRequest(merchantId, staffEmail).with(csrf()))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.userId").value(staffId.toString()))
            .andExpect(jsonPath("$.role").value(MerchantRole.MERCHANT_ADMIN.name))
            .andExpect(jsonPath("$.status").value(MerchantMemberStatus.ACTIVE.name))

        // 대소문자·공백이 달라도 같은 계정으로 수렴한다(이메일 정규화).
        session
            .perform(addMemberRequest(merchantId, "  ${staffEmail.uppercase()}  ").with(csrf()))
            .andExpect(status().isCreated)

        assertThat(merchantMemberRepository.findAll().filter { it.merchantId == merchantId && it.userId == staffId })
            .hasSize(1)
    }

    // ---- 3. 부여 API 와 콘솔이 실제로 이어져 있다 ----

    @Test
    fun `SUSPENDED 멤버십 재부여는 ACTIVE 로 되살아나고 콘솔이 열린다`() {
        merchantMemberRepository.save(
            MerchantMember(
                merchantId = merchantId,
                userId = staffId,
                status = MerchantMemberStatus.SUSPENDED.name,
            ),
        )
        val staffSession = login(staffEmail)
        staffSession
            .perform(get("/api/merchant-console/{merchantId}/transactions", merchantId))
            .andExpect(status().isForbidden)

        login(adminEmail)
            .perform(addMemberRequest(merchantId, staffEmail).with(csrf()))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value(MerchantMemberStatus.ACTIVE.name))

        assertThat(merchantMemberRepository.findByMerchantIdAndUserId(merchantId, staffId)!!.status)
            .isEqualTo(MerchantMemberStatus.ACTIVE.name)
        // 부여 API 가 콘솔 인가의 실제 근거라는 유일한 연결 단언(같은 세션 그대로).
        staffSession
            .perform(get("/api/merchant-console/{merchantId}/transactions", merchantId))
            .andExpect(status().isOk)
    }

    @Test
    fun `해제는 살아 있는 세션에도 즉시 유효하다(권한이 세션에 굳지 않는다)`() {
        merchantMemberRepository.save(MerchantMember(merchantId = merchantId, userId = staffId))
        val staffSession = login(staffEmail)
        staffSession
            .perform(get("/api/merchant-console/{merchantId}/transactions", merchantId))
            .andExpect(status().isOk)

        login(adminEmail)
            .perform(
                delete("/api/admin/merchants/{merchantId}/members/{userId}", merchantId, staffId).with(csrf()),
            ).andExpect(status().isNoContent)

        staffSession
            .perform(get("/api/merchant-console/{merchantId}/transactions", merchantId))
            .andExpect(status().isForbidden)
        staffSession
            .perform(get("/api/merchant-console/mine"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.merchants.length()").value(0))
            // 멤버십 자체가 사라졌으므로 "열 수 없는 매장"에도 잡히지 않는다 —
            // blocked 는 **담당자이긴 한데 매장 상태가 막는** 경우만 담는다.
            .andExpect(jsonPath("$.blocked.length()").value(0))
    }

    @Test
    fun `멤버십이 없는 사용자 해제는 404 다`() {
        login(adminEmail)
            .perform(
                delete("/api/admin/merchants/{merchantId}/members/{userId}", merchantId, staffId).with(csrf()),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `직원 목록은 ACTIVE 만 보여준다`() {
        merchantMemberRepository.save(MerchantMember(merchantId = merchantId, userId = staffId))
        merchantMemberRepository.save(
            MerchantMember(
                merchantId = merchantId,
                userId = outsiderId,
                status = MerchantMemberStatus.SUSPENDED.name,
            ),
        )

        login(adminEmail)
            .perform(get("/api/admin/merchants/{merchantId}/members", merchantId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].email").value(staffEmail))
    }

    // ---- 4. step-up ----

    @Test
    fun `step-up 만료 상태의 부여는 401 REAUTH_REQUIRED 다`() {
        val session = login(adminEmail)
        session.setAttribute(StepUp.AUTH_TIME_SESSION_KEY, Instant.now().minus(Duration.ofMinutes(11)))

        // 인터셉터가 컨트롤러 인가보다 먼저 돌아 401 로 떨어진다(감사 이벤트도 남지 않는다).
        session
            .perform(addMemberRequest(merchantId, staffEmail).with(csrf()))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"))
        session
            .perform(
                delete("/api/admin/merchants/{merchantId}/members/{userId}", merchantId, staffId).with(csrf()),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"))

        assertThat(merchantMemberRepository.findAll()).isEmpty()
        // 조회는 step-up 대상이 아니다(변경만 재인증을 요구한다).
        session
            .perform(get("/api/admin/merchants/{merchantId}/members", merchantId))
            .andExpect(status().isOk)
    }

    @Test
    fun `CSRF 토큰 없는 부여는 거부된다`() {
        val session = login(adminEmail)
        session
            .perform(addMemberRequest(merchantId, staffEmail))
            .andExpect(status().isForbidden)
        assertThat(merchantMemberRepository.findAll()).isEmpty()
    }

    // ---- 5. 감사 ----

    @Test
    fun `부여·해제 감사 이벤트가 대상 사용자와 가맹 식별자를 남긴다`() {
        val session = login(adminEmail)
        session
            .perform(addMemberRequest(merchantId, staffEmail).with(csrf()))
            .andExpect(status().isCreated)

        val added = auditDetail("ADMIN_MERCHANT_MEMBER_ADDED")
        assertThat(added.get("merchantId").asText()).isEqualTo(merchantId.toString())
        assertThat(added.get("targetUserId").asText()).isEqualTo(staffId.toString())
        assertThat(added.get("targetEmail").asText()).isEqualTo(staffEmail)
        assertThat(added.get("reactivated").asBoolean()).isFalse()

        session
            .perform(
                delete("/api/admin/merchants/{merchantId}/members/{userId}", merchantId, staffId).with(csrf()),
            ).andExpect(status().isNoContent)

        val removed = auditDetail("ADMIN_MERCHANT_MEMBER_REMOVED")
        assertThat(removed.get("merchantId").asText()).isEqualTo(merchantId.toString())
        assertThat(removed.get("targetUserId").asText()).isEqualTo(staffId.toString())

        // 행위자는 플랫폼 관리자 자신으로 기록된다(대상과 구분).
        val actors =
            auditEventRepository
                .findAll()
                .filter { it.type.startsWith("ADMIN_MERCHANT_MEMBER_") }
                .map { it.userId }
        assertThat(actors).isNotEmpty.allMatch { it == adminId }
    }

    // ---- 6. 가맹 CRUD ----

    @Test
    fun `가맹 CRUD 는 일반 사용자에게 403 이다`() {
        val session = login(outsiderEmail)
        session.perform(get("/api/admin/merchants")).andExpect(status().isForbidden)
        session
            .perform(
                post("/api/admin/merchants")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"몰래 가맹"}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)
        session
            .perform(delete("/api/admin/merchants/{id}", merchantId).with(csrf()))
            .andExpect(status().isForbidden)
        assertThat(merchantRepository.findById(merchantId)).isPresent
    }

    @Test
    fun `가맹 생성은 카테고리·상태·타임존·사업장을 저장한다`() {
        val siteId = siteRepository.save(Site(orgId = orgId, name = "본사 구내식당")).id!!
        val session = login(adminEmail)

        val id =
            createMerchant(
                session,
                """{"name":" 새 가맹 ","category":"CAFE","status":"SUSPENDED","siteId":"$siteId","timezone":"Asia/Seoul"}""",
            )

        val saved = merchantRepository.findById(id).orElseThrow()
        assertThat(saved.name).isEqualTo("새 가맹") // 앞뒤 공백 제거
        assertThat(saved.category).isEqualTo("CAFE")
        assertThat(saved.status).isEqualTo("SUSPENDED")
        assertThat(saved.siteId).isEqualTo(siteId)
        assertThat(saved.timezone).isEqualTo("Asia/Seoul")
    }

    @Test
    fun `타임존 미전송 수정은 기존 값을 유지한다`() {
        val session = login(adminEmail)
        val id = createMerchant(session, """{"name":"서울 가맹","timezone":"Asia/Seoul"}""")

        // timezone 은 다른 필드와 달리 full-replace 하지 않는다 — 바뀌면 과거 집계의 날짜 버킷이
        // 통째로 이동해 "어제 몇 인분"이 소급 변경된다(MerchantUpsertRequest KDoc).
        session
            .perform(
                put("/api/admin/merchants/{id}", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"서울 가맹 2호","category":"CONVENIENCE"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.timezone").value("Asia/Seoul"))

        val saved = merchantRepository.findById(id).orElseThrow()
        assertThat(saved.name).isEqualTo("서울 가맹 2호")
        assertThat(saved.category).isEqualTo("CONVENIENCE")
        assertThat(saved.timezone).isEqualTo("Asia/Seoul")

        // 명시 전송은 정상적으로 교체된다.
        session
            .perform(
                put("/api/admin/merchants/{id}", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"서울 가맹 2호","timezone":"UTC"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.timezone").value("UTC"))
    }

    @Test
    fun `잘못된 타임존·사업장·이름·카테고리는 400 이다`() {
        val session = login(adminEmail)
        val bodies =
            listOf(
                """{"name":"타임존 불량","timezone":"Mars/Olympus"}""",
                """{"name":"사업장 불량","siteId":"${UUID.randomUUID()}"}""",
                """{"name":"   "}""",
                """{"name":"${"가".repeat(201)}"}""",
                """{"name":"카테고리 불량","category":"BAR"}""",
                """{"name":"상태 불량","status":"CLOSED"}""",
            )
        for (body in bodies) {
            session
                .perform(
                    post("/api/admin/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()),
                ).andExpect(status().isBadRequest)
        }
        // 처음 만든 1개(setUp) 외에는 아무것도 저장되지 않았다.
        assertThat(merchantRepository.count()).isEqualTo(1)
    }

    @Test
    fun `가맹 삭제 후 조회는 404 이고 없는 가맹의 수정·삭제도 404 다`() {
        val session = login(adminEmail)
        session
            .perform(delete("/api/admin/merchants/{id}", merchantId).with(csrf()))
            .andExpect(status().isNoContent)
        session
            .perform(get("/api/admin/merchants/{id}", merchantId))
            .andExpect(status().isNotFound)

        val missing = UUID.randomUUID()
        session
            .perform(
                put("/api/admin/merchants/{id}", missing)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"없는 가맹"}""")
                    .with(csrf()),
            ).andExpect(status().isNotFound)
        session
            .perform(delete("/api/admin/merchants/{id}", missing).with(csrf()))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `가맹 목록은 생성한 가맹을 모두 담는다`() {
        val session = login(adminEmail)
        createMerchant(session, """{"name":"두 번째 가맹"}""")
        session
            .perform(get("/api/admin/merchants"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
    }

    // ---- 헬퍼 ----

    private fun addMemberRequest(
        merchantId: UUID,
        email: String,
    ) = post("/api/admin/merchants/{merchantId}/members", merchantId)
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(mapOf("email" to email)))

    private fun createMerchant(
        session: WebSession,
        body: String,
    ): UUID {
        val response =
            session
                .perform(
                    post("/api/admin/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(csrf()),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return UUID.fromString(objectMapper.readTree(response).get("id").asText())
    }

    /** 가장 최근 해당 타입 감사 이벤트의 detail(JSON). */
    private fun auditDetail(type: String) =
        objectMapper.readTree(
            auditEventRepository
                .findAll()
                .filter { it.type == type }
                .maxByOrNull { it.createdAt }!!
                .detail!!,
        )

    private fun login(email: String): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", email).with(csrf()))
        session
            .perform(
                post("/login/password").param("username", email).param("password", password).with(csrf()),
            ).andExpect(status().is3xxRedirection)
        return session
    }
}
