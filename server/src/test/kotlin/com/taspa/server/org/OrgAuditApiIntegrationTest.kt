package com.taspa.server.org

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.audit.AuditEventService
import com.taspa.server.domain.audit.AuditEventRepository
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 조직 스코프 활동로그(GET /api/orgs/{orgId}/audit) 통합 테스트.
 *  - org 액션(역할변경) 후 audit_events.org_id 가 채워진다.
 *  - ORG_ADMIN 은 자기 org 이벤트만 본다(타 org·org_id null 전역 이벤트는 미노출 — 격리).
 *  - 타 org ORG_ADMIN 403, 일반 멤버 403, 플랫폼 ADMIN 성공.
 *  - limit 상한(100)·offset 페이징, 사용자 베어러 토큰 거부(confused-deputy).
 *  - 3-arg 콜러(전역 이벤트)는 org_id=null 로 남고 org 조회에 새지 않는다.
 */
class OrgAuditApiIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var auditEventRepository: AuditEventRepository

    @Autowired lateinit var auditEventService: AuditEventService

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
        orgA = organizationRepository.save(Organization(slug = "aud-a", name = "Audit A")).id!!
        orgB = organizationRepository.save(Organization(slug = "aud-b", name = "Audit B")).id!!
    }

    @Test
    fun `org 역할변경 액션이 audit_events_org_id 를 채운다`() {
        val admin = saveUser("aud-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val member = saveUser("aud-member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))

        val session = login(admin.email)
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/role", orgA, member.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"role":"ORG_ADMIN"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)

        val events =
            auditEventRepository.findByOrgIdOrderByCreatedAtDesc(
                orgA,
                org.springframework.data.domain.PageRequest
                    .of(0, 10),
            )
        assertThat(events).hasSize(1)
        assertThat(events.first().type).isEqualTo("ADMIN_ORG_MEMBER_ROLE_CHANGED")
        assertThat(events.first().orgId).isEqualTo(orgA)
    }

    @Test
    fun `ORG_ADMIN 은 자기 org 이벤트만 본다(격리)`() {
        val admin = saveUser("iso-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))

        // orgA 이벤트 2건 + orgB 이벤트 1건 + 전역(org_id=null) 1건.
        auditEventService.record("ORG_INVITE_CREATED", admin.id, orgA, mapOf("orgId" to orgA.toString()))
        auditEventService.record("ADMIN_ORG_MEMBER_REMOVED", admin.id, orgA, mapOf("orgId" to orgA.toString()))
        auditEventService.record("ORG_INVITE_CREATED", admin.id, orgB, mapOf("orgId" to orgB.toString()))
        auditEventService.record("LOGIN_SUCCESS", admin.id, mapOf("method" to "password")) // 3-arg → org_id null

        val session = login(admin.email)
        val body =
            session
                .perform(get("/api/orgs/{orgId}/audit", orgA))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val rows = objectMapper.readTree(body)
        assertThat(rows.size()).isEqualTo(2)
        rows.forEach { assertThat(it.get("type").asText()).isIn("ORG_INVITE_CREATED", "ADMIN_ORG_MEMBER_REMOVED") }
    }

    @Test
    fun `타 org 의 ORG_ADMIN 은 조회할 수 없다`() {
        val bAdmin = saveUser("aud-b-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgB, userId = bAdmin.id!!, role = OrgRole.ORG_ADMIN.name))
        val session = login(bAdmin.email)
        session.perform(get("/api/orgs/{orgId}/audit", orgA)).andExpect(status().isForbidden)
    }

    @Test
    fun `일반 멤버는 조회할 수 없다`() {
        val member = saveUser("aud-plain@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))
        val session = login(member.email)
        session.perform(get("/api/orgs/{orgId}/audit", orgA)).andExpect(status().isForbidden)
    }

    @Test
    fun `플랫폼 ADMIN 은 임의 org 를 조회한다`() {
        val platformAdmin = saveUser("aud-platform@example.com", role = UserRole.ADMIN)
        auditEventService.record("ORG_INVITE_CREATED", platformAdmin.id, orgA, mapOf("orgId" to orgA.toString()))
        val session = login(platformAdmin.email)
        val body =
            session
                .perform(get("/api/orgs/{orgId}/audit", orgA))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertThat(objectMapper.readTree(body).size()).isEqualTo(1)
    }

    @Test
    fun `플랫폼 ADMIN 행위자 이벤트는 테넌트에게 신원을 마스킹한다`() {
        val orgAdmin = saveUser("mask-org-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = orgAdmin.id!!, role = OrgRole.ORG_ADMIN.name))
        val platformAdmin = saveUser("mask-platform@example.com", role = UserRole.ADMIN)
        // 플랫폼 운영자가 org A 를 대상으로 남긴 org 결속 이벤트.
        auditEventService.record("ADMIN_ORG_UPDATED", platformAdmin.id, orgA, mapOf("orgId" to orgA.toString()))
        // 대조군: org 내부 구성원(ORG_ADMIN)이 남긴 이벤트는 이메일이 그대로 보인다.
        auditEventService.record("ORG_INVITE_CREATED", orgAdmin.id, orgA, mapOf("orgId" to orgA.toString()))

        val session = login(orgAdmin.email)
        val body =
            session
                .perform(get("/api/orgs/{orgId}/audit", orgA))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val rows = objectMapper.readTree(body).associateBy { it.get("type").asText() }

        val platformRow = rows.getValue("ADMIN_ORG_UPDATED")
        assertThat(platformRow.get("platformActor").asBoolean()).isTrue()
        assertThat(platformRow.get("email").isNull).isTrue()
        assertThat(platformRow.get("userId").isNull).isTrue()

        val tenantRow = rows.getValue("ORG_INVITE_CREATED")
        assertThat(tenantRow.get("platformActor").asBoolean()).isFalse()
        assertThat(tenantRow.get("email").asText()).isEqualTo("mask-org-admin@example.com")
    }

    @Test
    fun `limit 상한과 offset 이 적용된다`() {
        val admin = saveUser("aud-page-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        repeat(5) { auditEventService.record("ORG_INVITE_CREATED", admin.id, orgA, mapOf("i" to it.toString())) }

        val session = login(admin.email)
        // limit=2 → 2건
        val page1 =
            session
                .perform(get("/api/orgs/{orgId}/audit", orgA).param("limit", "2").param("offset", "0"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertThat(objectMapper.readTree(page1).size()).isEqualTo(2)
        // offset=4 → 나머지 1건
        val page2 =
            session
                .perform(get("/api/orgs/{orgId}/audit", orgA).param("limit", "2").param("offset", "4"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertThat(objectMapper.readTree(page2).size()).isEqualTo(1)
        // limit=999 는 상한(100)으로 클램프되어도 5건 전부(총량<상한) 반환.
        val all =
            session
                .perform(get("/api/orgs/{orgId}/audit", orgA).param("limit", "999"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        assertThat(objectMapper.readTree(all).size()).isEqualTo(5)
    }

    @Test
    fun `사용자 베어러 토큰으로는 조회할 수 없다(confused-deputy 차단)`() {
        val admin = saveUser("aud-bearer-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val token = userBearerToken(admin.id!!, "openid org.read")
        mockMvc
            .perform(
                get("/api/orgs/{orgId}/audit", orgA).header("Authorization", "Bearer $token"),
            ).andExpect(status().isForbidden)
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
