package com.taspa.server.org

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.audit.AuditEventRepository
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
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
 * 자율 콘솔(ORG_ADMIN) 멤버 관리 + /api/orgs/mine 인가/격리 통합 테스트.
 *  - ORG_ADMIN 이 자기 org 멤버 목록/역할변경/제거 성공.
 *  - 타 org ORG_ADMIN·일반 멤버는 403(org 격리), 플랫폼 ADMIN 은 임의 org 관리 성공.
 *  - 베어러 JWT 는 거부(confused-deputy). 마지막 ORG_ADMIN 강등/제거는 guardLastAdmin 로 400.
 *  - /api/orgs/mine 은 관리 org 만 반환. 변경 엔드포인트는 step-up(오래된 auth_time → 401).
 */
class OrgConsoleApiIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var userRepository: UserRepository

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

    @BeforeEach
    fun setUp() {
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()
        auditEventRepository.deleteAll()
        every { mailSender.send(any<SimpleMailMessage>()) } just Runs
        orgA = organizationRepository.save(Organization(slug = "con-a", name = "Console A")).id!!
        orgB = organizationRepository.save(Organization(slug = "con-b", name = "Console B")).id!!
    }

    // ---- 멤버 관리 성공 ----

    @Test
    fun `ORG_ADMIN 은 자기 org 의 멤버 목록을 본다`() {
        val admin = saveUser("con-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val member = saveUser("con-member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))

        val session = login(admin.email)
        val body =
            session
                .perform(get("/api/orgs/{orgId}/members", orgA))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertThat(objectMapper.readTree(body).size()).isEqualTo(2)
    }

    @Test
    fun `ORG_ADMIN 은 자기 org 의 멤버 역할을 변경한다`() {
        val admin = saveUser("con-admin2@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val member = saveUser("con-member2@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))

        val session = login(admin.email)
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/role", orgA, member.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"role":"ORG_ADMIN"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, member.id!!)!!.role).isEqualTo(OrgRole.ORG_ADMIN.name)

        // ORG_ADMIN 의 역할변경도 감사에 남는다(플랫폼 ADMIN 경로와 동일 이벤트 타입, 행위자=admin).
        val events = auditEventRepository.findByTypeOrderByCreatedAtDesc("ADMIN_ORG_MEMBER_ROLE_CHANGED", PageRequest.of(0, 10))
        assertThat(events).hasSize(1)
        assertThat(events.first().userId).isEqualTo(admin.id)
        assertThat(events.first().detail).contains(orgA.toString()).contains(member.id.toString()).contains("ORG_ADMIN")
    }

    @Test
    fun `ORG_ADMIN 은 자기 org 의 멤버를 제거한다`() {
        val admin = saveUser("con-admin3@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val member = saveUser("con-member3@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))

        val session = login(admin.email)
        session
            .perform(delete("/api/orgs/{orgId}/members/{userId}", orgA, member.id).with(csrf()))
            .andExpect(status().isNoContent)
        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, member.id!!)).isNull()

        val events = auditEventRepository.findByTypeOrderByCreatedAtDesc("ADMIN_ORG_MEMBER_REMOVED", PageRequest.of(0, 10))
        assertThat(events).hasSize(1)
        assertThat(events.first().userId).isEqualTo(admin.id)
        assertThat(events.first().detail).contains(orgA.toString()).contains(member.id.toString())
    }

    // ---- org 격리 ----

    @Test
    fun `타 org 의 ORG_ADMIN 은 멤버 목록을 볼 수 없다(격리)`() {
        val bAdmin = saveUser("con-b-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgB, userId = bAdmin.id!!, role = OrgRole.ORG_ADMIN.name))
        val session = login(bAdmin.email)
        session.perform(get("/api/orgs/{orgId}/members", orgA)).andExpect(status().isForbidden)
    }

    @Test
    fun `일반 멤버는 멤버 관리가 거부된다`() {
        val member = saveUser("con-plain@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))
        val session = login(member.email)
        session.perform(get("/api/orgs/{orgId}/members", orgA)).andExpect(status().isForbidden)
    }

    @Test
    fun `플랫폼 ADMIN 은 임의 org 의 멤버를 관리한다`() {
        val platformAdmin = saveUser("platform@example.com", role = UserRole.ADMIN)
        val member = saveUser("any-member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))
        val session = login(platformAdmin.email)
        session.perform(get("/api/orgs/{orgId}/members", orgA)).andExpect(status().isOk)
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/role", orgA, member.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"role":"ORG_ADMIN"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
    }

    @Test
    fun `사용자 베어러 토큰으로는 멤버 목록을 읽을 수 없다(confused-deputy 차단)`() {
        val admin = saveUser("bearer-con-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val token = userBearerToken(admin.id!!, "openid org.read")
        mockMvc
            .perform(
                get("/api/orgs/{orgId}/members", orgA).header("Authorization", "Bearer $token"),
            ).andExpect(status().isForbidden)
    }

    // ---- guardLastAdmin ----

    @Test
    fun `마지막 ORG_ADMIN 강등은 차단된다`() {
        val admin = saveUser("last-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val session = login(admin.email)
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/role", orgA, admin.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"role":"MEMBER"}""")
                    .with(csrf()),
            ).andExpect(status().isBadRequest)
        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, admin.id!!)!!.role).isEqualTo(OrgRole.ORG_ADMIN.name)
    }

    @Test
    fun `마지막 ORG_ADMIN 제거는 차단된다`() {
        val admin = saveUser("last-admin2@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val session = login(admin.email)
        session
            .perform(delete("/api/orgs/{orgId}/members/{userId}", orgA, admin.id).with(csrf()))
            .andExpect(status().isBadRequest)
        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, admin.id!!)).isNotNull
    }

    // ---- /api/orgs/mine ----

    @Test
    fun `mine 은 ORG_ADMIN 으로 관리하는 org 만 반환한다`() {
        val user = saveUser("mine-user@example.com")
        // orgA: ORG_ADMIN(포함), orgB: MEMBER(미포함)
        membershipRepository.save(OrgMembership(orgId = orgA, userId = user.id!!, role = OrgRole.ORG_ADMIN.name))
        membershipRepository.save(OrgMembership(orgId = orgB, userId = user.id!!, role = OrgRole.MEMBER.name))
        // 다른 유저의 org(미포함) 확인용
        val other = saveUser("mine-other@example.com")
        membershipRepository.save(OrgMembership(orgId = orgB, userId = other.id!!, role = OrgRole.ORG_ADMIN.name))

        val session = login(user.email)
        val body =
            session
                .perform(get("/api/orgs/mine"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val rows = objectMapper.readTree(body)
        assertThat(rows.size()).isEqualTo(1)
        assertThat(rows.first().get("id").asText()).isEqualTo(orgA.toString())
        assertThat(rows.first().get("role").asText()).isEqualTo(OrgRole.ORG_ADMIN.name)
    }

    @Test
    fun `mine 은 관리 org 가 없으면 빈 목록이다`() {
        val member = saveUser("mine-empty@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))
        val session = login(member.email)
        val body =
            session
                .perform(get("/api/orgs/mine"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertThat(objectMapper.readTree(body).size()).isZero()
    }

    // ---- /api/orgs/memberships (계정 페이지 "내 조직", 읽기 전용) ----

    @Test
    fun `memberships 는 본인이 소속된 활성 org 만 역할·부서와 함께 반환한다`() {
        val user = saveUser("mem-user@example.com")
        // orgA: MEMBER + 부서, orgB: ORG_ADMIN (둘 다 포함)
        membershipRepository.save(
            OrgMembership(orgId = orgA, userId = user.id!!, role = OrgRole.MEMBER.name, department = "Engineering"),
        )
        membershipRepository.save(OrgMembership(orgId = orgB, userId = user.id!!, role = OrgRole.ORG_ADMIN.name))
        // 다른 유저의 org 는 안 보여야 한다.
        val other = saveUser("mem-other@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = other.id!!, role = OrgRole.ORG_ADMIN.name))

        val session = login(user.email)
        val body =
            session
                .perform(get("/api/orgs/memberships"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val rows = objectMapper.readTree(body)
        assertThat(rows.size()).isEqualTo(2)
        // 이름 오름차순: "Console A" < "Console B"
        val a = rows[0]
        assertThat(a.get("orgId").asText()).isEqualTo(orgA.toString())
        assertThat(a.get("orgName").asText()).isEqualTo("Console A")
        assertThat(a.get("orgSlug").asText()).isEqualTo("con-a")
        assertThat(a.get("role").asText()).isEqualTo(OrgRole.MEMBER.name)
        assertThat(a.get("department").asText()).isEqualTo("Engineering")
        val b = rows[1]
        assertThat(b.get("orgId").asText()).isEqualTo(orgB.toString())
        assertThat(b.get("role").asText()).isEqualTo(OrgRole.ORG_ADMIN.name)
    }

    @Test
    fun `memberships 는 소속이 없으면 빈 목록이다`() {
        val user = saveUser("mem-empty@example.com")
        val session = login(user.email)
        val body =
            session
                .perform(get("/api/orgs/memberships"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertThat(objectMapper.readTree(body).size()).isZero()
    }

    @Test
    fun `memberships 는 비활성 org·비활성 멤버십을 제외한다`() {
        val user = saveUser("mem-inactive@example.com")
        // orgA: 멤버십은 ACTIVE 지만 org 가 SUSPENDED → 제외
        organizationRepository.save(organizationRepository.findById(orgA).get().apply { status = "SUSPENDED" })
        membershipRepository.save(OrgMembership(orgId = orgA, userId = user.id!!, role = OrgRole.MEMBER.name))
        // orgB: org 는 ACTIVE 지만 멤버십이 SUSPENDED → 제외
        membershipRepository.save(
            OrgMembership(orgId = orgB, userId = user.id!!, role = OrgRole.MEMBER.name, status = "SUSPENDED"),
        )

        val session = login(user.email)
        val body =
            session
                .perform(get("/api/orgs/memberships"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertThat(objectMapper.readTree(body).size()).isZero()
    }

    @Test
    fun `사용자 베어러 토큰으로는 내 조직 목록을 읽을 수 없다(confused-deputy 차단)`() {
        val user = saveUser("mem-bearer@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = user.id!!, role = OrgRole.MEMBER.name))
        val token = userBearerToken(user.id!!, "openid org.read")
        mockMvc
            .perform(
                get("/api/orgs/memberships").header("Authorization", "Bearer $token"),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `미인증은 내 조직 목록 조회가 401 이다`() {
        mockMvc.perform(get("/api/orgs/memberships")).andExpect(status().isUnauthorized)
    }

    // ---- step-up ----

    @Test
    fun `오래된 auth_time 이면 역할 변경은 REAUTH_REQUIRED 로 거절된다`() {
        val admin = saveUser("stepup-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val member = saveUser("stepup-member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))

        val session = login(admin.email)
        session.setAttribute(StepUp.AUTH_TIME_SESSION_KEY, Instant.now().minus(Duration.ofMinutes(11)))
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/role", orgA, member.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"role":"ORG_ADMIN"}""")
                    .with(csrf()),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"))
    }

    // ---- 프로필 편집(PUT /api/orgs/{orgId}) ----

    @Test
    fun `ORG_ADMIN 은 자기 org 의 이름과 타임존을 수정한다`() {
        val admin = saveUser("profile-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))

        val session = login(admin.email)
        session
            .perform(
                put("/api/orgs/{orgId}", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Renamed Org","timezone":"Asia/Seoul"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Renamed Org"))
            .andExpect(jsonPath("$.timezone").value("Asia/Seoul"))

        val org = organizationRepository.findById(orgA).get()
        assertThat(org.name).isEqualTo("Renamed Org")
        assertThat(org.timezone).isEqualTo("Asia/Seoul")

        // 감사 — 플랫폼 ADMIN 경로와 동일 이벤트 타입, 행위자=admin, org_id 기록.
        val events = auditEventRepository.findByTypeOrderByCreatedAtDesc("ADMIN_ORG_UPDATED", PageRequest.of(0, 10))
        assertThat(events).hasSize(1)
        assertThat(events.first().userId).isEqualTo(admin.id)
        assertThat(events.first().detail).contains(orgA.toString()).contains("Renamed Org")
    }

    @Test
    fun `프로필 편집으로는 status 와 slug 를 바꿀 수 없다(불변식)`() {
        val admin = saveUser("profile-guard-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))

        val session = login(admin.email)
        // body 에 status·slug 를 실어도 무시된다(정지 해제·slug 탈취 불가).
        session
            .perform(
                put("/api/orgs/{orgId}", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Still Active","status":"SUSPENDED","slug":"hijacked"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)

        val org = organizationRepository.findById(orgA).get()
        assertThat(org.status).isEqualTo("ACTIVE")
        assertThat(org.slug).isEqualTo("con-a")
        assertThat(org.name).isEqualTo("Still Active")
    }

    @Test
    fun `잘못된 타임존은 400 이다`() {
        val admin = saveUser("profile-tz-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))

        val session = login(admin.email)
        session
            .perform(
                put("/api/orgs/{orgId}", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"timezone":"Mars/Phobos"}""")
                    .with(csrf()),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `타 org 의 ORG_ADMIN 은 프로필을 수정할 수 없다(격리)`() {
        val bAdmin = saveUser("profile-b-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgB, userId = bAdmin.id!!, role = OrgRole.ORG_ADMIN.name))

        val session = login(bAdmin.email)
        session
            .perform(
                put("/api/orgs/{orgId}", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Hijack"}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)
        assertThat(organizationRepository.findById(orgA).get().name).isEqualTo("Console A")
    }

    @Test
    fun `일반 멤버는 프로필 수정이 거부된다`() {
        val member = saveUser("profile-plain@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))

        val session = login(member.email)
        session
            .perform(
                put("/api/orgs/{orgId}", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Nope"}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `플랫폼 ADMIN 은 임의 org 의 프로필을 수정한다`() {
        val platformAdmin = saveUser("profile-platform@example.com", role = UserRole.ADMIN)
        val session = login(platformAdmin.email)
        session
            .perform(
                put("/api/orgs/{orgId}", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"By Platform","timezone":"Asia/Seoul"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
        assertThat(organizationRepository.findById(orgA).get().name).isEqualTo("By Platform")
    }

    @Test
    fun `사용자 베어러 토큰으로는 프로필을 수정할 수 없다(confused-deputy 차단)`() {
        val admin = saveUser("profile-bearer-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val token = userBearerToken(admin.id!!, "openid org.read")
        // 베어러는 세션이 없어 step-up(@RequireRecentAuth) 게이트를 통과할 auth_time 이 없다 —
        // 컨트롤러의 베어러 거부(403) 이전에 RecentAuthInterceptor 가 401 REAUTH_REQUIRED 로 먼저 막는다.
        // 어느 쪽이든 M2M 토큰으로는 프로필을 변경할 수 없다.
        mockMvc
            .perform(
                put("/api/orgs/{orgId}", orgA)
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Nope"}""")
                    .with(csrf()),
            ).andExpect(status().isUnauthorized)
        assertThat(organizationRepository.findById(orgA).get().name).isEqualTo("Console A")
    }

    @Test
    fun `오래된 auth_time 이면 프로필 수정은 REAUTH_REQUIRED 로 거절된다`() {
        val admin = saveUser("profile-stepup-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))

        val session = login(admin.email)
        session.setAttribute(StepUp.AUTH_TIME_SESSION_KEY, Instant.now().minus(Duration.ofMinutes(11)))
        session
            .perform(
                put("/api/orgs/{orgId}", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Stale"}""")
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
