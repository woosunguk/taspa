package com.taspa.server.iam

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.calendar.CalendarFeedRepository
import com.taspa.server.domain.iam.IamInlinePolicy
import com.taspa.server.domain.iam.IamInlinePolicyRepository
import com.taspa.server.domain.iam.IamPolicy
import com.taspa.server.domain.iam.IamPolicyAttachmentRepository
import com.taspa.server.domain.iam.IamPolicyRepository
import com.taspa.server.domain.iam.IamPrincipalType
import com.taspa.server.domain.meal.Merchant
import com.taspa.server.domain.meal.MerchantRepository
import com.taspa.server.domain.org.OrgDomainRepository
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.domain.user.UserStatus
import com.taspa.server.stepup.StepUp
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * "관리 콘솔 74개 핸들러의 인가를 hasRole("ADMIN") 에서 IAM 엔진으로 옮겼다"는 이관이 **실제로 배선돼
 * 있다는 양성 증거**를 만든다. 기존 통합테스트 115건이 한 줄도 안 고치고 통과한 것만으로는 이걸 증명하지
 * 못한다 — 플랫폼 관리자 브리지가 `*`/`*` Allow 라서, 체인을 통과하는 관리자는 엔진에 실제로 질의하든
 * 안 하든 똑같이 200 을 본다(파리티는 "아무것도 안 한 것"과 구별되지 않는다).
 *
 * 그래서 **명시 Deny 프로브**를 쓴다: `PolicyEvaluator` 는 매치되는 Deny 를 만나면 즉시 거부하므로
 * inline Deny 가 `*`/`*` Allow 를 이긴다. 관리자에게 특정 action 만 Deny 하는 정책을 붙였을 때 그
 * 엔드포인트가 403 이 되면, 그 핸들러가 엔진에 질의한다는 증거다. 서로 다른 컨트롤러 6곳(조직·도메인·
 * 캘린더·사용자·가맹·IAM)에서 뽑아 "한 컨트롤러만 우연히 배선됐다"는 가능성을 배제한다.
 */
class AdminEngineEnforcementIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var orgDomainRepository: OrgDomainRepository

    @Autowired lateinit var calendarFeedRepository: CalendarFeedRepository

    @Autowired lateinit var merchantRepository: MerchantRepository

    @Autowired lateinit var iamPolicyRepository: IamPolicyRepository

    @Autowired lateinit var iamPolicyAttachmentRepository: IamPolicyAttachmentRepository

    @Autowired lateinit var iamInlinePolicyRepository: IamInlinePolicyRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val adminEmail = "engine-probe-admin@example.com"
    private val memberEmail = "engine-probe-member@example.com"
    private val password = "SecureP@ssw0rd123"

    private lateinit var adminId: UUID
    private lateinit var memberId: UUID
    private lateinit var orgId: UUID
    private lateinit var merchantId: UUID
    private lateinit var pingPolicyId: UUID

    @BeforeEach
    fun setUp() {
        jdbcTemplate.update("DELETE FROM iam_policy_attachments")
        jdbcTemplate.update("DELETE FROM iam_group_members")
        jdbcTemplate.update("DELETE FROM iam_inline_policies")
        jdbcTemplate.update("DELETE FROM iam_policies")
        jdbcTemplate.update("DELETE FROM iam_principal_groups")
        calendarFeedRepository.deleteAll()
        orgDomainRepository.deleteAll()
        merchantRepository.deleteAll()
        organizationRepository.deleteAll()
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
        memberId =
            userRepository
                .save(
                    User(email = memberEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
                ).id!!
        orgId = organizationRepository.save(Organization(slug = "engine-probe-org", name = "Engine Probe Org")).id!!
        merchantId = merchantRepository.save(Merchant(name = "엔진 프로브 가맹")).id!!
        pingPolicyId =
            iamPolicyRepository
                .save(
                    IamPolicy(name = "engine-probe-ping", document = PING_DOCUMENT, systemManaged = false),
                ).id!!
    }

    // ====================================================================
    // A. 엔진 배선 증거 — 관리자에게 특정 action 만 Deny 하는 inline 정책을 붙이면
    //    1) 그 엔드포인트가 403, 2) 부수효과가 없고(인가가 컨트롤러 본문보다 먼저 실행됐다는 증거),
    //    3) 같은 정책 상태에서 다른 action(엔드포인트)은 여전히 성공하며(복붙 오배정이 아니라는 증거),
    //    4) 정책을 걷으면 같은 요청이 더 이상 403 이 아니다(CSRF·step-up 등 다른 이유로 막힌 게 아니었다는 증거).
    //    네 가지가 전부 성립해야 "그 핸들러가 실제로 엔진에 질의한다"는 증거가 된다.
    // ====================================================================

    @Test
    fun `조직 생성 action Deny 는 403 이고 조직이 생성되지 않으며 목록 조회는 살아있다`() {
        val session = login(adminEmail)
        val before = organizationRepository.count()

        denyAction(adminId, IamActions.PLATFORM_CREATE_ORG)
        session
            .perform(
                post("/api/admin/orgs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Denied Org"}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)

        assertThat(organizationRepository.count()).isEqualTo(before)

        // 다른 action(목록 조회, PLATFORM_LIST_ORGS)은 이 Deny 의 영향을 받지 않는다.
        session.perform(get("/api/admin/orgs")).andExpect(status().isOk)

        clearInlinePolicies(adminId)
        session
            .perform(
                post("/api/admin/orgs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Allowed Org"}""")
                    .with(csrf()),
            ).andExpect(status().isCreated)
        assertThat(organizationRepository.count()).isEqualTo(before + 1)
    }

    @Test
    fun `도메인 등록 action Deny 는 403 이고 등록되지 않으며 도메인 목록 조회는 살아있다`() {
        val session = login(adminEmail)

        fun domainCount() = orgDomainRepository.findByOrgIdOrderByCreatedAtAsc(orgId).size

        denyAction(adminId, IamActions.PLATFORM_REGISTER_ORG_DOMAIN)
        session
            .perform(
                post("/api/admin/orgs/$orgId/domains")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"engine-probe.example"}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)

        assertThat(domainCount()).isEqualTo(0)

        // 다른 action(목록 조회, PLATFORM_LIST_ORG_DOMAINS)은 여전히 성공한다.
        session.perform(get("/api/admin/orgs/$orgId/domains")).andExpect(status().isOk)

        clearInlinePolicies(adminId)
        session
            .perform(
                post("/api/admin/orgs/$orgId/domains")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"domain":"engine-probe.example"}""")
                    .with(csrf()),
            ).andExpect(status().isCreated)
        assertThat(domainCount()).isEqualTo(1)
    }

    @Test
    fun `캘린더 피드 생성 action Deny 는 403 이고 생성되지 않으며 피드 목록 조회는 살아있다`() {
        val session = login(adminEmail)

        denyAction(adminId, IamActions.PLATFORM_CREATE_CALENDAR_FEED)
        session
            .perform(
                post("/api/admin/orgs/$orgId/calendar/feeds")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"probe","type":"EVENT"}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)

        assertThat(calendarFeedRepository.findByOrgId(orgId)).isEmpty()

        // 다른 action(목록 조회, PLATFORM_LIST_CALENDAR_FEEDS)은 여전히 성공한다.
        session.perform(get("/api/admin/orgs/$orgId/calendar/feeds")).andExpect(status().isOk)

        clearInlinePolicies(adminId)
        session
            .perform(
                post("/api/admin/orgs/$orgId/calendar/feeds")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"probe","type":"EVENT"}""")
                    .with(csrf()),
            ).andExpect(status().isCreated)
        assertThat(calendarFeedRepository.findByOrgId(orgId)).hasSize(1)
    }

    @Test
    fun `사용자 정지 action Deny 는 403 이고 상태가 그대로이며 사용자 상세 조회는 살아있다`() {
        val session = login(adminEmail)

        denyAction(adminId, IamActions.PLATFORM_SUSPEND_USER)
        session
            .perform(post("/api/admin/users/$memberId/suspend").with(csrf()))
            .andExpect(status().isForbidden)

        assertThat(userRepository.findById(memberId).get().status).isEqualTo(UserStatus.ACTIVE.name)

        // 다른 action(상세 조회, PLATFORM_READ_USER)은 여전히 성공한다.
        session.perform(get("/api/admin/users/$memberId")).andExpect(status().isOk)

        clearInlinePolicies(adminId)
        session
            .perform(post("/api/admin/users/$memberId/suspend").with(csrf()))
            .andExpect(status().isNoContent)
        assertThat(userRepository.findById(memberId).get().status).isEqualTo(UserStatus.SUSPENDED.name)
    }

    @Test
    fun `가맹 삭제 action Deny 는 403 이고 대상이 여전히 존재하며 가맹 조회는 살아있다`() {
        val session = login(adminEmail)

        denyAction(adminId, IamActions.PLATFORM_DELETE_MERCHANT)
        session
            .perform(delete("/api/admin/merchants/$merchantId").with(csrf()))
            .andExpect(status().isForbidden)

        assertThat(merchantRepository.findById(merchantId)).isPresent

        // 다른 action(단건 조회, PLATFORM_READ_MERCHANT)은 여전히 성공한다.
        session.perform(get("/api/admin/merchants/$merchantId")).andExpect(status().isOk)

        clearInlinePolicies(adminId)
        session
            .perform(delete("/api/admin/merchants/$merchantId").with(csrf()))
            .andExpect(status().isNoContent)
        assertThat(merchantRepository.findById(merchantId)).isEmpty
    }

    @Test
    fun `IAM 정책 부착 action Deny 는 403 이고 부착되지 않으며 정책 목록 조회는 살아있다`() {
        val session = login(adminEmail)

        fun attached() =
            iamPolicyAttachmentRepository.existsByPolicyIdAndPrincipalTypeAndPrincipalId(
                pingPolicyId,
                IamPrincipalType.USER,
                memberId,
            )

        denyAction(adminId, IamActions.IAM_ATTACH_POLICY)
        session
            .perform(
                post("/api/admin/iam/attachments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"policyId":"$pingPolicyId","principalType":"USER","principalId":"$memberId"}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)

        assertThat(attached()).isFalse()

        // 다른 action(정책 목록 조회, IAM_LIST_POLICIES)은 여전히 성공한다.
        session.perform(get("/api/admin/iam/policies")).andExpect(status().isOk)

        clearInlinePolicies(adminId)
        session
            .perform(
                post("/api/admin/iam/attachments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"policyId":"$pingPolicyId","principalType":"USER","principalId":"$memberId"}""")
                    .with(csrf()),
            ).andExpect(status().isNoContent)
        assertThat(attached()).isTrue()
    }

    // ====================================================================
    // B. 인터셉터 순서 — step-up 이 만료된 요청은 인가 판정과 무관하게 401 REAUTH_REQUIRED 여야 한다
    //    (403 이 아니라). Deny 정책까지 같이 걸어서 "엔진이 거부할 이유가 있어도" 재인증 요구가 여전히
    //    먼저 이긴다는 것을 고정한다. 오늘은 관리자가 엔진에서 항상 통과(`*`/`*` Allow)하므로, 누군가
    //    WebMvcConfig 에서 인가 인터셉터를 recentAuthInterceptor 앞으로 옮겨도 **다른 어떤 기존 테스트도
    //    실패하지 않는다** — 이 테스트가 그 순서 자체를 잠그는 유일한 회귀 방지막이다.
    // ====================================================================

    @Test
    fun `step-up 만료와 Deny 정책이 동시에 걸려도 401 REAUTH_REQUIRED 가 403 보다 우선한다`() {
        val session = login(adminEmail)
        val before = organizationRepository.count()

        denyAction(adminId, IamActions.PLATFORM_CREATE_ORG)
        session.setAttribute(StepUp.AUTH_TIME_SESSION_KEY, Instant.now().minus(Duration.ofMinutes(11)))

        session
            .perform(
                post("/api/admin/orgs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Should Not Exist"}""")
                    .with(csrf()),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"))

        assertThat(organizationRepository.count()).isEqualTo(before)
    }

    // ====================================================================
    // C. 네임스페이스 격리 — org 위임 정책의 `org:*` 와일드카드는 `trn:taspa:org:...` 자원만 매치할 뿐,
    //    관리 콘솔 자원(`trn:taspa:platform:...`)은 애초에 다른 service 세그먼트라 매치되지 않는다.
    //    이 경로는 그 이전에 시큐리티 체인의 hasRole("ADMIN") 이 먼저 끊으므로 엔진까지 도달하지도
    //    못한다 — 그 사실 자체가 방어의 1차 계층이라는 것을 고정한다.
    // ====================================================================

    @Test
    fun `org 위임 Allow 정책을 비관리자에게 붙여도 관리 콘솔에는 도달하지 못한다`() {
        iamInlinePolicyRepository.save(
            IamInlinePolicy(
                principalType = IamPrincipalType.USER,
                principalId = memberId,
                name = "org-delegation-probe",
                document =
                    """
                    {"Version":"2026-07-25","Statement":[
                        {"Sid":"OrgDelegation","Effect":"Allow","Action":"*","Resource":"trn:taspa:org:$orgId:*"}
                    ]}
                    """.trimIndent(),
            ),
        )
        val session = login(memberEmail)

        session.perform(get("/api/admin/orgs/$orgId")).andExpect(status().isForbidden)
        session
            .perform(
                put("/api/admin/orgs/$orgId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"pwned"}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)

        assertThat(organizationRepository.findById(orgId).get().name).isEqualTo("Engine Probe Org")
    }

    // ---- 헬퍼 ----

    /** [action] 하나만 거부하는 inline 정책을 [userId] 에게 붙인다 — 플랫폼 관리자 브리지의 `*`/`*` Allow 를 이기는지 검증하는 도구. */
    private fun denyAction(
        userId: UUID,
        action: String,
    ) {
        iamInlinePolicyRepository.save(
            IamInlinePolicy(
                principalType = IamPrincipalType.USER,
                principalId = userId,
                name = "deny-probe-${action.replace(":", "_")}",
                document = denyDocument(action),
            ),
        )
    }

    private fun clearInlinePolicies(userId: UUID) {
        iamInlinePolicyRepository
            .findByPrincipalTypeAndPrincipalId(IamPrincipalType.USER, userId)
            .forEach { iamInlinePolicyRepository.delete(it) }
    }

    private fun denyDocument(action: String): String =
        """{"Version":"2026-07-25","Statement":[{"Sid":"DenyProbe","Effect":"Deny","Action":["$action"],"Resource":["*"]}]}"""

    private fun login(email: String): WebSession {
        val session = webSession()
        session
            .perform(post("/login/identifier").param("email", email).with(csrf()))
            .andExpect(redirectedUrl("/login/password"))
        session
            .perform(
                post("/login/password").param("username", email).param("password", password).with(csrf()),
            ).andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/account"))
        return session
    }

    private companion object {
        const val PING_DOCUMENT =
            """{"Version":"2026-07-25","Statement":[{"Sid":"allowPing","Effect":"Allow","Action":"ping:Do","Resource":"*"}]}"""
    }
}
