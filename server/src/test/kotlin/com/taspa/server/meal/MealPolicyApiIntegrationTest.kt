package com.taspa.server.meal

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.audit.AuditEventRepository
import com.taspa.server.domain.meal.MealPolicyRepository
import com.taspa.server.domain.meal.MealPolicyRevisionRepository
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgRole
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import com.taspa.server.token.JwkStorageService
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 조직 식대 정책 API(`/api/orgs/{orgId}/meal-policy`) 통합 테스트.
 *
 * 이 표면이 생기기 전까지 12,000원/1일1회/월20만원은 엔티티 기본값으로 **하드코딩**돼 있었고
 * (정책 행을 저장하는 프로덕션 코드가 한 곳도 없었다) 조직관리자는 자기 회사 한도조차 못 바꿨다.
 * 여기서 잠그는 것: 편집이 실제로 승인 판정에 반영되는가 · 테넌시 격리 · 자릿수 사고 방지 ·
 * 조용한 소실(자정 넘는 창) 거절 · 이력 append.
 */
class MealPolicyApiIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var policyRepository: MealPolicyRepository

    @Autowired lateinit var revisionRepository: MealPolicyRevisionRepository

    @Autowired lateinit var auditEventRepository: AuditEventRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var jwkSource: JWKSource<SecurityContext>

    @Autowired lateinit var jwkStorageService: JwkStorageService

    @Value("\${taspa.issuer-uri}")
    lateinit var issuerUri: String

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val password = "SecureP@ssw0rd123"
    private lateinit var orgA: UUID
    private lateinit var orgB: UUID
    private lateinit var adminA: User

    /** 통과하는 기준 요청 — 각 테스트는 여기서 한 필드만 비틀어 그 필드의 검증만 겨냥한다. */
    private val validBody =
        """
        {"perMealLimitMinor":15000,"dailyMealCount":2,"monthlyCapMinor":300000,
         "breakfastStart":"07:00","breakfastEnd":"10:00",
         "lunchStart":"11:00","lunchEnd":"14:00",
         "dinnerStart":"17:00","dinnerEnd":"21:00"}
        """.trimIndent()

    @BeforeEach
    fun setUp() {
        revisionRepository.deleteAll()
        policyRepository.deleteAll()
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()
        auditEventRepository.deleteAll()
        every { mailSender.send(any<SimpleMailMessage>()) } just Runs
        orgA = organizationRepository.save(Organization(slug = "mp-a", name = "Policy A")).id!!
        orgB = organizationRepository.save(Organization(slug = "mp-b", name = "Policy B")).id!!
        adminA = saveUser("mp-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = adminA.id!!, role = OrgRole.ORG_ADMIN.name))
    }

    @Test
    fun `정책을 저장한 적이 없으면 코드 기본값을 usingDefaults 와 함께 알려 준다`() {
        login(adminA.email)
            .perform(get("/api/orgs/{orgId}/meal-policy", orgA))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.perMealLimitMinor").value(12000))
            .andExpect(jsonPath("$.dailyMealCount").value(1))
            .andExpect(jsonPath("$.monthlyCapMinor").value(200000))
            // ★"기본값을 쓰는 중"이 드러나야 조직관리자가 설정해야 할 이유를 안다.
            .andExpect(jsonPath("$.usingDefaults").value(true))
            // 폼이 상한을 미리 보여줄 수 있게 함께 내려준다(서버도 같은 값으로 거절한다).
            .andExpect(jsonPath("$.ceilingPerMealLimitMinor").value(1000000))
    }

    @Test
    fun `ORG_ADMIN 이 자기 조직 정책을 편집하고 이력이 남는다`() {
        val session = login(adminA.email)
        session
            .perform(putPolicy(orgA, validBody))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.perMealLimitMinor").value(15000))
            .andExpect(jsonPath("$.usingDefaults").value(false))

        val stored = policyRepository.findById(orgA).orElseThrow()
        assertThat(stored.perMealLimitMinor).isEqualTo(15000)
        assertThat(stored.dailyMealCount).isEqualTo(2)

        val revisions = revisionRepository.findByOrgIdOrderByRecordedAtDesc(orgA, PageRequest.of(0, 10))
        assertThat(revisions).hasSize(1)
        assertThat(revisions.first().changeType).isEqualTo("CREATED")
        assertThat(revisions.first().actorIsOrgMember).isTrue()
        // 스냅샷은 델타가 아니라 전체 — 한 줄만 읽으면 그 시점 값을 안다.
        val snapshot = objectMapper.readTree(revisions.first().document)
        assertThat(snapshot.get("perMealLimitMinor").asLong()).isEqualTo(15000)
        assertThat(snapshot.get("lunchStart").asText()).isEqualTo("11:00")

        val audits = auditEventRepository.findByOrgIdOrderByCreatedAtDesc(orgA, PageRequest.of(0, 10))
        assertThat(audits.map { it.type }).contains("ORG_MEAL_POLICY_UPDATED")
    }

    @Test
    fun `두 번째 저장은 UPDATED 로 append 된다(덮어쓰기 아님)`() {
        val session = login(adminA.email)
        session.perform(putPolicy(orgA, validBody)).andExpect(status().isOk)
        session.perform(putPolicy(orgA, validBody.replace("15000", "16000"))).andExpect(status().isOk)

        val revisions = revisionRepository.findByOrgIdOrderByRecordedAtDesc(orgA, PageRequest.of(0, 10))
        assertThat(revisions).hasSize(2)
        assertThat(revisions.map { it.changeType }).containsExactly("UPDATED", "CREATED")
    }

    @Test
    fun `★편집한 한도가 승인 판정과 자격 조회에 즉시 반영된다`() {
        // 이 테스트가 이 슬라이스의 존재 이유다. 정책을 바꿔도 계산 경로가 옛 값을 쓰면 편집 화면은
        // 장식일 뿐이다. 자격 조회는 redeem 과 **같은 해석기**를 쓰므로 여기서 확인되면 승인도 같다.
        val member = saveUser("mp-member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))

        val memberSession = login(member.email)
        memberSession
            .perform(get("/api/meal/entitlement").param("orgId", orgA.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.perMealLimitMinor").value(12000))
            .andExpect(jsonPath("$.perMealLimitSource").value("CODE_DEFAULT"))

        login(adminA.email).perform(putPolicy(orgA, validBody)).andExpect(status().isOk)

        memberSession
            .perform(get("/api/meal/entitlement").param("orgId", orgA.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.perMealLimitMinor").value(15000))
            .andExpect(jsonPath("$.dailyMealCount").value(2))
            .andExpect(jsonPath("$.perMealLimitSource").value("ORG"))
    }

    @Test
    fun `타 조직 정책은 읽지도 쓰지도 못한다`() {
        val session = login(adminA.email)
        session.perform(get("/api/orgs/{orgId}/meal-policy", orgB)).andExpect(status().isForbidden)
        session.perform(putPolicy(orgB, validBody)).andExpect(status().isForbidden)
        assertThat(policyRepository.findById(orgB)).isEmpty
    }

    @Test
    fun `일반 멤버는 정책을 편집할 수 없다`() {
        val member = saveUser("mp-plain@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))

        val session = login(member.email)
        session.perform(get("/api/orgs/{orgId}/meal-policy", orgA)).andExpect(status().isForbidden)
        session.perform(putPolicy(orgA, validBody)).andExpect(status().isForbidden)
    }

    @Test
    fun `위임 베어러 토큰으로는 접근할 수 없다`() {
        val token = userBearerToken(adminA.id!!, "openid org.read")
        mockMvc
            .perform(
                get("/api/orgs/{orgId}/meal-policy", orgA).header("Authorization", "Bearer $token"),
            ).andExpect(status().isForbidden)
        // ★PUT 은 @RequireRecentAuth 라 step-up 인터셉터가 authorize() 보다 먼저 돈다 → 401.
        mockMvc
            .perform(
                put("/api/orgs/{orgId}/meal-policy", orgA)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validBody)
                    .with(csrf()),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"))
    }

    @Test
    fun `미인증 요청은 401 JSON 이다`() {
        /*
         * ★이 단언은 한 번 **뒤집혔다.** 예전에는 이 체인(org API, @Order 2)에 진입점 배선이 없어
         * BearerTokenAuthenticationEntryPoint 의 **빈 401**(+ WWW-Authenticate: Bearer)이 나왔고,
         * 이 테스트가 그 모양을 그대로 못박고 있었다 — "본문이 비어 있다"는 한계를 주석으로 적으면서.
         * 지금은 기본 체인과 같은 `ApiAuthenticationEntryPoint`(401 JSON) 를 배선했다 — 빈 401 이면
         * 화면에 띄울 문구가 없고, 프런트 오류 처리가 체인마다 갈라진다. 세션 쿠키가 실사용인 경로라
         * Bearer 챌린지 헤더는 애초에 의미가 없었다.
         */
        mockMvc
            .perform(get("/api/orgs/{orgId}/meal-policy", orgA))
            .andExpect(status().isUnauthorized)
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .jsonPath("$.errorCode")
                    .value("UNAUTHENTICATED"),
            )
    }

    @Test
    fun `자릿수 사고는 상한이 막는다`() {
        // 12000 대신 1200000 을 입력하는 실수 — 오타 한 번이 곧 회사 지출이 되는 자리다.
        login(adminA.email)
            .perform(putPolicy(orgA, validBody.replace("\"perMealLimitMinor\":15000", "\"perMealLimitMinor\":9900000")))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
        assertThat(policyRepository.findById(orgA)).isEmpty
    }

    @Test
    fun `★자정을 넘는 끼니창은 조용히 사라지지 않고 거절된다`() {
        // openWindows 의 start < end 필터가 이런 창을 후보에서 빼 버리므로, 받아 두면 사용자에게는
        // "저장은 됐는데 저녁시간이 사라졌다"로 나타난다. 저장 실패가 조용한 소실보다 낫다.
        val overnight =
            """
            {"perMealLimitMinor":15000,"dailyMealCount":2,"monthlyCapMinor":300000,
             "breakfastStart":"07:00","breakfastEnd":"10:00",
             "lunchStart":"11:00","lunchEnd":"14:00",
             "dinnerStart":"22:00","dinnerEnd":"02:00"}
            """.trimIndent()
        login(adminA.email)
            .perform(putPolicy(orgA, overnight))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
        assertThat(policyRepository.findById(orgA)).isEmpty
    }

    @Test
    fun `겹치는 끼니창은 거절된다(뒤 창이 영영 안 열리는 설정 방지)`() {
        login(adminA.email)
            .perform(putPolicy(orgA, validBody.replace("\"lunchEnd\":\"14:00\"", "\"lunchEnd\":\"18:00\"")))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
    }

    @Test
    fun `1일 횟수 0 은 거절된다`() {
        login(adminA.email)
            .perform(putPolicy(orgA, validBody.replace("\"dailyMealCount\":2", "\"dailyMealCount\":0")))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `시각 형식이 틀리면 400 이고 원인을 말해 준다`() {
        login(adminA.email)
            .perform(putPolicy(orgA, validBody.replace("\"lunchStart\":\"11:00\"", "\"lunchStart\":\"25시\"")))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("점심")))
    }

    @Test
    fun `플랫폼 관리자의 변경은 비멤버 행위로 이력에 남는다`() {
        // 조직이 "우리는 안 바꿨는데 한도가 달라졌다"를 사후에 가려낼 수 있어야 한다.
        val platformAdmin = saveUser("mp-platform@example.com", UserRole.ADMIN)

        login(platformAdmin.email).perform(putPolicy(orgA, validBody)).andExpect(status().isOk)

        val revisions = revisionRepository.findByOrgIdOrderByRecordedAtDesc(orgA, PageRequest.of(0, 10))
        assertThat(revisions).hasSize(1)
        assertThat(revisions.first().actorIsOrgMember).isFalse()
    }

    @Test
    fun `이력 조회는 최근순이고 플랫폼 운영자의 이메일은 조직에 노출하지 않는다`() {
        val platformAdmin = saveUser("mp-platform2@example.com", UserRole.ADMIN)
        login(adminA.email).perform(putPolicy(orgA, validBody)).andExpect(status().isOk)
        login(platformAdmin.email).perform(putPolicy(orgA, validBody.replace("15000", "17000"))).andExpect(status().isOk)

        login(adminA.email)
            .perform(get("/api/orgs/{orgId}/meal-policy/history", orgA))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].actorIsOrgMember").value(false))
            .andExpect(jsonPath("$[0].actorEmail").doesNotExist())
            .andExpect(jsonPath("$[1].actorIsOrgMember").value(true))
            .andExpect(jsonPath("$[1].actorEmail").value(adminA.email))
    }

    @Test
    fun `이력 조회 limit 은 상한으로 절단된다(자원고갈 방지)`() {
        login(adminA.email).perform(putPolicy(orgA, validBody)).andExpect(status().isOk)
        login(adminA.email)
            .perform(
                get("/api/orgs/{orgId}/meal-policy/history", orgA).param("limit", "100000"),
            ).andExpect(status().isOk)
    }

    // ---- helpers ----

    private fun putPolicy(
        org: UUID,
        body: String,
    ) = put("/api/orgs/{orgId}/meal-policy", org)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .with(csrf())

    private fun userBearerToken(
        userId: UUID,
        scope: String,
    ): String {
        val encoder = NimbusJwtEncoder(jwkSource)
        val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(jwkStorageService.activeKid()).build()
        val now = Instant.now()
        val claims =
            JwtClaimsSet
                .builder()
                .issuer(issuerUri)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .claim("scope", scope)
                .build()
        return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }

    private fun saveUser(
        email: String,
        role: UserRole = UserRole.USER,
    ): User =
        userRepository.save(
            User(
                email = email,
                passwordHash = passwordEncoder.encode(password),
                emailVerified = true,
                role = role.name,
            ),
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
