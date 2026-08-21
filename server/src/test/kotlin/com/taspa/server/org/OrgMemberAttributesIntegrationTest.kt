package com.taspa.server.org

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.org.Department
import com.taspa.server.domain.org.DepartmentRepository
import com.taspa.server.domain.org.MembershipChangeType
import com.taspa.server.domain.org.MembershipHistoryRepository
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgRole
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.stepup.StepUp
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
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 조직 2단계 백엔드(임직원 속성 + 멤버십 이력 SCD) 통합 테스트.
 *  - 속성: 갱신·enum 검증(잘못된 employment_type/status 400)·날짜 파싱·비멤버 404·타 org 격리.
 *  - 이력: 역할변경·배정·속성갱신·제거마다 change_type 정확 append, 멱등 재로그인 미증가(단위),
 *    GET history 최신순·org 격리.
 *  - 인가: ORG_ADMIN 허용·타 org 403·베어러 거부·미인증 401·변경 step-up.
 */
class OrgMemberAttributesIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var historyRepository: MembershipHistoryRepository

    @Autowired lateinit var departmentRepository: DepartmentRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var organizationService: OrganizationService

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

    @BeforeEach
    fun setUp() {
        historyRepository.deleteAllInBatch()
        membershipRepository.deleteAll()
        departmentRepository.deleteAllInBatch()
        organizationRepository.deleteAll()
        userRepository.deleteAll()
        every { mailSender.send(any<SimpleMailMessage>()) } just Runs
        orgA = organizationRepository.save(Organization(slug = "attr-a", name = "Attr A")).id!!
        orgB = organizationRepository.save(Organization(slug = "attr-b", name = "Attr B")).id!!
    }

    // ---- 속성 갱신 ----

    @Test
    fun `ORG_ADMIN 은 멤버 HR 속성을 갱신한다`() {
        val admin = saveUser("attr-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val member = saveUser("attr-member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))

        val session = login(admin.email)
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/attributes", orgA, member.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"employeeId":"E-1001","jobTitle":"조리사","employmentType":"FULL_TIME","hireDate":"2024-03-01","employmentStatus":"ON_LEAVE"}""",
                    ).with(csrf()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.employeeId").value("E-1001"))
            .andExpect(jsonPath("$.jobTitle").value("조리사"))
            .andExpect(jsonPath("$.employmentType").value("FULL_TIME"))
            .andExpect(jsonPath("$.hireDate").value("2024-03-01"))
            .andExpect(jsonPath("$.employmentStatus").value("ON_LEAVE"))

        val saved = membershipRepository.findByOrgIdAndUserId(orgA, member.id!!)!!
        assertThat(saved.employeeId).isEqualTo("E-1001")
        assertThat(saved.employmentStatus).isEqualTo("ON_LEAVE")
    }

    @Test
    fun `잘못된 employment_type 은 400 이다`() {
        val admin = saveUser("attr-badtype-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val member = saveUser("attr-badtype-member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))

        val session = login(admin.email)
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/attributes", orgA, member.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"employmentType":"WEEKEND"}""")
                    .with(csrf()),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `잘못된 employment_status 는 400 이다`() {
        val admin = saveUser("attr-badstatus-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val member = saveUser("attr-badstatus-member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))

        val session = login(admin.email)
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/attributes", orgA, member.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"employmentStatus":"QUIT"}""")
                    .with(csrf()),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `잘못된 날짜 형식은 400 이다`() {
        val admin = saveUser("attr-baddate-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val member = saveUser("attr-baddate-member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))

        val session = login(admin.email)
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/attributes", orgA, member.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"hireDate":"2024-13-40"}""")
                    .with(csrf()),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `비멤버 대상 속성 갱신은 404 이다`() {
        val admin = saveUser("attr-nonmember-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val nonMember = saveUser("attr-nonmember@example.com")

        val session = login(admin.email)
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/attributes", orgA, nonMember.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"jobTitle":"X"}""")
                    .with(csrf()),
            ).andExpect(status().isNotFound)
    }

    // ---- 이력(SCD) ----

    @Test
    fun `역할변경 배정 속성갱신 제거마다 이력이 정확한 change_type 으로 append 된다`() {
        val admin = saveUser("hist-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        // co-admin 을 둬 마지막 관리자 강등 가드를 피한다(멤버는 MEMBER 로 바꿔도 조직에 관리자 남음).
        val coAdmin = saveUser("hist-coadmin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = coAdmin.id!!, role = OrgRole.ORG_ADMIN.name))
        val member = saveUser("hist-member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))
        val dept = departmentRepository.save(Department(orgId = orgA, name = "조리부"))

        val session = login(admin.email)
        // 역할변경: MEMBER → ORG_ADMIN
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/role", orgA, member.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"role":"ORG_ADMIN"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
        // 배정
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/assignment", orgA, member.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"departmentId":"${dept.id}"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
        // 속성 갱신
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/attributes", orgA, member.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"jobTitle":"셰프"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
        // 제거
        session
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .delete("/api/orgs/{orgId}/members/{userId}", orgA, member.id)
                    .with(csrf()),
            ).andExpect(status().isNoContent)

        val history = historyRepository.findByOrgIdAndUserIdOrderByRecordedAtDesc(orgA, member.id!!)
        val types = history.map { it.changeType }
        // 최신순: REMOVED, ATTRIBUTES_UPDATED, ASSIGNED, ROLE_CHANGED (JOINED 는 직접 save 로 만들어 없음)
        assertThat(types).containsExactly(
            MembershipChangeType.REMOVED.name,
            MembershipChangeType.ATTRIBUTES_UPDATED.name,
            MembershipChangeType.ASSIGNED.name,
            MembershipChangeType.ROLE_CHANGED.name,
        )
        // 제거 스냅샷은 최종 상태(role=ORG_ADMIN, dept 배정, jobTitle=셰프)를 보존한다.
        val removed = history.first()
        assertThat(removed.role).isEqualTo(OrgRole.ORG_ADMIN.name)
        assertThat(removed.departmentId).isEqualTo(dept.id)
        assertThat(removed.jobTitle).isEqualTo("셰프")
    }

    @Test
    fun `upsert 는 신규는 JOINED, 역할 변경은 ROLE_CHANGED, 진짜 no-op 은 미기록`() {
        val member = saveUser("hist-join@example.com")
        // 신규 생성 → JOINED 1건
        organizationService.upsertMember(
            orgA,
            com.taspa.server.org.dto
                .MembershipRequest(userId = member.id!!, role = OrgRole.MEMBER.name),
        )
        // 역할 동일 재upsert(no-op) → 이력 미증가(이력 폭증 방지)
        organizationService.upsertMember(
            orgA,
            com.taspa.server.org.dto
                .MembershipRequest(userId = member.id!!, role = OrgRole.MEMBER.name),
        )
        assertThat(historyRepository.findByOrgIdAndUserIdOrderByRecordedAtDesc(orgA, member.id!!).map { it.changeType })
            .containsExactly(MembershipChangeType.JOINED.name)
        // 역할이 실제로 바뀌는 upsert(MEMBER→ORG_ADMIN) → ROLE_CHANGED 이력 append(시점별 역할 재구성 정답데이터)
        organizationService.upsertMember(
            orgA,
            com.taspa.server.org.dto
                .MembershipRequest(userId = member.id!!, role = OrgRole.ORG_ADMIN.name),
        )
        assertThat(historyRepository.findByOrgIdAndUserIdOrderByRecordedAtDesc(orgA, member.id!!).map { it.changeType })
            .containsExactly(MembershipChangeType.ROLE_CHANGED.name, MembershipChangeType.JOINED.name)
    }

    @Test
    fun `JIT 멱등 재합류는 이력을 늘리지 않는다`() {
        val member = saveUser("hist-jit@example.com")
        assertThat(organizationService.ensureJitMembership(orgA, member.id!!)).isTrue()
        assertThat(organizationService.ensureJitMembership(orgA, member.id!!)).isFalse()
        val history = historyRepository.findByOrgIdAndUserIdOrderByRecordedAtDesc(orgA, member.id!!)
        assertThat(history).hasSize(1)
        assertThat(history.first().changeType).isEqualTo(MembershipChangeType.JOINED.name)
    }

    @Test
    fun `history 조회는 최신순이며 타 org 이력을 노출하지 않는다`() {
        val admin = saveUser("hist-view-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val member = saveUser("hist-view-member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))
        // 같은 user 가 orgB 에도 멤버십·이력이 있어도 orgA 조회에는 안 잡혀야 한다.
        membershipRepository.save(OrgMembership(orgId = orgB, userId = member.id!!, role = OrgRole.MEMBER.name))
        organizationService.updateAttributes(
            orgB,
            member.id!!,
            com.taspa.server.org.dto
                .MemberAttributesRequest(jobTitle = "B-role"),
        )

        val session = login(admin.email)
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/attributes", orgA, member.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"jobTitle":"A-role"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)

        val body =
            session
                .perform(get("/api/orgs/{orgId}/members/{userId}/history", orgA, member.id))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].changeType").value(MembershipChangeType.ATTRIBUTES_UPDATED.name))
                .andReturn()
                .response.contentAsString
        // PII 미노출 — user_sub/email 필드가 이력 뷰에 없다.
        assertThat(body).doesNotContain("email")
    }

    // ---- 인가 ----

    @Test
    fun `타 org 의 ORG_ADMIN 은 속성을 갱신할 수 없다(격리)`() {
        val bAdmin = saveUser("attr-b-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgB, userId = bAdmin.id!!, role = OrgRole.ORG_ADMIN.name))
        val member = saveUser("attr-iso-member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))

        val session = login(bAdmin.email)
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/attributes", orgA, member.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"jobTitle":"X"}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `플랫폼 ADMIN 은 임의 org 멤버 속성을 갱신한다`() {
        val platformAdmin = saveUser("attr-platform@example.com", role = UserRole.ADMIN)
        val member = saveUser("attr-platform-member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))
        val session = login(platformAdmin.email)
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/attributes", orgA, member.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"jobTitle":"X"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
    }

    @Test
    fun `사용자 베어러 토큰으로는 멤버 관리가 거부된다(confused-deputy 차단)`() {
        val admin = saveUser("attr-bearer@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val token = userBearerToken(admin.id!!, "openid org.read")
        // @RequireRecentAuth 가 없는 GET history 로 검증한다 — step-up 인터셉터가 authorize() 보다 먼저 도는
        // /attributes(PUT) 로는 REAUTH_REQUIRED(401)가 먼저 나와 confused-deputy 거부(403)에 도달하지 못한다.
        mockMvc
            .perform(
                get("/api/orgs/{orgId}/members/{userId}/history", orgA, admin.id)
                    .header("Authorization", "Bearer $token"),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `미인증은 history 조회가 401 이다`() {
        val member = saveUser("attr-unauth-member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))
        mockMvc
            .perform(get("/api/orgs/{orgId}/members/{userId}/history", orgA, member.id))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `오래된 auth_time 이면 속성 갱신은 REAUTH_REQUIRED 로 거절된다`() {
        val admin = saveUser("attr-stepup@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val member = saveUser("attr-stepup-member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))
        val session = login(admin.email)
        session.setAttribute(StepUp.AUTH_TIME_SESSION_KEY, Instant.now().minus(Duration.ofMinutes(11)))
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/attributes", orgA, member.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"jobTitle":"X"}""")
                    .with(csrf()),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"))
    }

    // ---- helpers ----

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
