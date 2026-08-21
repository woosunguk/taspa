package com.taspa.server.org

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.org.Department
import com.taspa.server.domain.org.DepartmentRepository
import com.taspa.server.domain.org.EmploymentStatus
import com.taspa.server.domain.org.EmploymentType
import com.taspa.server.domain.org.InvitationStatus
import com.taspa.server.domain.org.MembershipChangeType
import com.taspa.server.domain.org.MembershipHistory
import com.taspa.server.domain.org.MembershipHistoryRepository
import com.taspa.server.domain.org.MembershipStatus
import com.taspa.server.domain.org.OrgInvitation
import com.taspa.server.domain.org.OrgInvitationRepository
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgRole
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.org.Site
import com.taspa.server.domain.org.SiteRepository
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 조직 개요 대시보드(GET /api/orgs/{orgId}/dashboard) 통합 테스트.
 *  - 집계 정확성: 역할·재직상태·고용형태(미지정 포함)·부서 직접/롤업·사업장·미배정·PENDING 초대·최근 합류.
 *  - 활성 멤버십(status=ACTIVE) 기준 — SUSPENDED 멤버십은 어떤 분포에도 안 잡힌다.
 *  - org 격리: 타 org 의 멤버·초대·이력은 카운트에 미포함.
 *  - 인가: 타 org ORG_ADMIN·일반 멤버 403, 위임 베어러 403(confused-deputy), 미인증 401, 플랫폼 ADMIN 200.
 */
class OrgDashboardIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var historyRepository: MembershipHistoryRepository

    @Autowired lateinit var departmentRepository: DepartmentRepository

    @Autowired lateinit var siteRepository: SiteRepository

    @Autowired lateinit var invitationRepository: OrgInvitationRepository

    @Autowired lateinit var userRepository: UserRepository

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
        invitationRepository.deleteAllInBatch()
        membershipRepository.deleteAll()
        // departments 는 자기참조 parent_id ON DELETE CASCADE 라 개별 deleteAll 이 이중삭제로 실패할 수 있다 —
        // 단일 벌크 DELETE(deleteAllInBatch)로 전 행을 한 문장에 지운다(OrgStructureApiIntegrationTest 관례).
        departmentRepository.deleteAllInBatch()
        siteRepository.deleteAllInBatch()
        organizationRepository.deleteAll()
        userRepository.deleteAll()
        every { mailSender.send(any<SimpleMailMessage>()) } just Runs
        orgA = organizationRepository.save(Organization(slug = "dash-a", name = "Dash A")).id!!
        orgB = organizationRepository.save(Organization(slug = "dash-b", name = "Dash B")).id!!
    }

    // ---- 집계 정확성 ----

    @Test
    fun `대시보드는 활성 멤버십 기준으로 역할·재직상태·고용형태·부서 롤업·사업장·초대·최근 합류를 집계한다`() {
        val admin = saveUser("dash-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))

        val deptRoot = departmentRepository.save(Department(orgId = orgA, name = "Kitchen")).id!!
        val deptChild = departmentRepository.save(Department(orgId = orgA, parentId = deptRoot, name = "Bakery")).id!!
        val site = siteRepository.save(Site(orgId = orgA, name = "HQ", timezone = "UTC")).id!!

        // member1: 루트 부서 + 사업장, FULL_TIME(재직 기본 EMPLOYED)
        val member1 = saveUser("dash-m1@example.com")
        membershipRepository.save(
            OrgMembership(
                orgId = orgA,
                userId = member1.id!!,
                role = OrgRole.MEMBER.name,
                departmentId = deptRoot,
                siteId = site,
                employmentType = EmploymentType.FULL_TIME.name,
            ),
        )
        // member2: 하위 부서, PART_TIME + 휴직
        val member2 = saveUser("dash-m2@example.com")
        membershipRepository.save(
            OrgMembership(
                orgId = orgA,
                userId = member2.id!!,
                role = OrgRole.MEMBER.name,
                departmentId = deptChild,
                employmentType = EmploymentType.PART_TIME.name,
                employmentStatus = EmploymentStatus.ON_LEAVE.name,
            ),
        )
        // member3: 구조 미배정 + 고용형태 미지정 + 퇴직
        val member3 = saveUser("dash-m3@example.com")
        membershipRepository.save(
            OrgMembership(
                orgId = orgA,
                userId = member3.id!!,
                role = OrgRole.MEMBER.name,
                employmentStatus = EmploymentStatus.TERMINATED.name,
            ),
        )
        // SUSPENDED 멤버십 — 어떤 카운트에도 안 잡혀야 한다(부서 배정이 있어도 제외).
        val suspended = saveUser("dash-suspended@example.com")
        membershipRepository.save(
            OrgMembership(
                orgId = orgA,
                userId = suspended.id!!,
                role = OrgRole.MEMBER.name,
                departmentId = deptRoot,
                siteId = site,
                status = MembershipStatus.SUSPENDED.name,
            ),
        )

        // 초대: 유효 PENDING 1 + 만료된 PENDING(제외) + REVOKED(제외)
        saveInvitation(orgA, "pending@example.com", InvitationStatus.PENDING, expiresAt = Instant.now().plus(1, ChronoUnit.DAYS))
        saveInvitation(orgA, "expired@example.com", InvitationStatus.PENDING, expiresAt = Instant.now().minus(1, ChronoUnit.DAYS))
        saveInvitation(orgA, "revoked@example.com", InvitationStatus.REVOKED, expiresAt = Instant.now().plus(1, ChronoUnit.DAYS))

        // 이력: 최근 JOINED 2건 + 30일 밖 JOINED(제외) + 최근 ROLE_CHANGED(제외)
        saveHistory(orgA, member1.id!!, MembershipChangeType.JOINED, Instant.now().minus(1, ChronoUnit.DAYS))
        saveHistory(orgA, member2.id!!, MembershipChangeType.JOINED, Instant.now().minus(10, ChronoUnit.DAYS))
        saveHistory(orgA, member3.id!!, MembershipChangeType.JOINED, Instant.now().minus(40, ChronoUnit.DAYS))
        saveHistory(orgA, member1.id!!, MembershipChangeType.ROLE_CHANGED, Instant.now().minus(1, ChronoUnit.DAYS))

        val session = login(admin.email)
        val body =
            session
                .perform(get("/api/orgs/{orgId}/dashboard", orgA))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = objectMapper.readTree(body)

        assertThat(json.get("memberCount").asLong()).isEqualTo(4)
        assertThat(json.get("byRole").get("MEMBER").asLong()).isEqualTo(3)
        assertThat(json.get("byRole").get("ORG_ADMIN").asLong()).isEqualTo(1)

        assertThat(json.get("byEmploymentStatus").get("EMPLOYED").asLong()).isEqualTo(2)
        assertThat(json.get("byEmploymentStatus").get("ON_LEAVE").asLong()).isEqualTo(1)
        assertThat(json.get("byEmploymentStatus").get("TERMINATED").asLong()).isEqualTo(1)

        assertThat(json.get("byEmploymentType").get("FULL_TIME").asLong()).isEqualTo(1)
        assertThat(json.get("byEmploymentType").get("PART_TIME").asLong()).isEqualTo(1)
        assertThat(json.get("byEmploymentType").get("CONTRACT").asLong()).isEqualTo(0)
        assertThat(json.get("byEmploymentType").get("INTERN").asLong()).isEqualTo(0)
        // 미지정(NULL) — admin + member3
        assertThat(json.get("byEmploymentType").get("UNSPECIFIED").asLong()).isEqualTo(2)

        // 부서 — 루트: 직접 1(member1), 롤업 2(member1 + 하위 member2). 하위: 직접=롤업=1.
        val root = deptNode(json, "Kitchen")
        assertThat(root.get("directCount").asLong()).isEqualTo(1)
        assertThat(root.get("rollupCount").asLong()).isEqualTo(2)
        assertThat(root.get("parentId").isNull).isTrue()
        val child = deptNode(json, "Bakery")
        assertThat(child.get("directCount").asLong()).isEqualTo(1)
        assertThat(child.get("rollupCount").asLong()).isEqualTo(1)
        assertThat(child.get("parentId").asText()).isEqualTo(deptRoot.toString())
        // 미배정 — admin + member3 (SUSPENDED 는 부서 배정이 있어도 제외 확인 겸용)
        assertThat(json.get("departmentUnassignedCount").asLong()).isEqualTo(2)

        // 사업장 — HQ 1(member1), 미배정 3(admin·member2·member3), 사업장 수 1.
        val sites = json.get("bySite")
        assertThat(sites.size()).isEqualTo(1)
        assertThat(sites[0].get("name").asText()).isEqualTo("HQ")
        assertThat(sites[0].get("count").asLong()).isEqualTo(1)
        assertThat(json.get("siteUnassignedCount").asLong()).isEqualTo(3)
        assertThat(json.get("siteCount").asLong()).isEqualTo(1)

        assertThat(json.get("pendingInvitations").asLong()).isEqualTo(1)
        assertThat(json.get("recentJoins30d").asLong()).isEqualTo(2)
    }

    @Test
    fun `멤버가 없는 조직은 전 카운트가 0 이고 enum 키는 모두 존재한다(빈 상태 계약)`() {
        val platformAdmin = saveUser("dash-empty-admin@example.com", role = UserRole.ADMIN)
        val session = login(platformAdmin.email)
        val body =
            session
                .perform(get("/api/orgs/{orgId}/dashboard", orgA))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = objectMapper.readTree(body)

        assertThat(json.get("memberCount").asLong()).isZero()
        assertThat(json.get("byRole").get("MEMBER").asLong()).isZero()
        assertThat(json.get("byRole").get("ORG_ADMIN").asLong()).isZero()
        listOf("EMPLOYED", "ON_LEAVE", "TERMINATED").forEach {
            assertThat(json.get("byEmploymentStatus").get(it).asLong()).isZero()
        }
        listOf("FULL_TIME", "PART_TIME", "CONTRACT", "INTERN", "UNSPECIFIED").forEach {
            assertThat(json.get("byEmploymentType").get(it).asLong()).isZero()
        }
        assertThat(json.get("byDepartment").size()).isZero()
        assertThat(json.get("departmentUnassignedCount").asLong()).isZero()
        assertThat(json.get("bySite").size()).isZero()
        assertThat(json.get("siteUnassignedCount").asLong()).isZero()
        assertThat(json.get("pendingInvitations").asLong()).isZero()
        assertThat(json.get("recentJoins30d").asLong()).isZero()
    }

    // ---- org 격리 ----

    @Test
    fun `타 org 의 멤버·초대·이력은 카운트에 포함되지 않는다(격리)`() {
        val admin = saveUser("dash-iso-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))

        // orgB 노이즈: 멤버 3 + 유효 PENDING 초대 + 최근 JOINED 이력 + 부서/사업장
        val bDept = departmentRepository.save(Department(orgId = orgB, name = "B Kitchen")).id!!
        val bSite = siteRepository.save(Site(orgId = orgB, name = "B HQ", timezone = "UTC")).id!!
        repeat(3) { i ->
            val u = saveUser("dash-iso-b$i@example.com")
            membershipRepository.save(
                OrgMembership(
                    orgId = orgB,
                    userId = u.id!!,
                    role = OrgRole.MEMBER.name,
                    departmentId = bDept,
                    siteId = bSite,
                    employmentType = EmploymentType.FULL_TIME.name,
                ),
            )
            saveHistory(orgB, u.id!!, MembershipChangeType.JOINED, Instant.now().minus(1, ChronoUnit.DAYS))
        }
        saveInvitation(orgB, "b-pending@example.com", InvitationStatus.PENDING, expiresAt = Instant.now().plus(1, ChronoUnit.DAYS))

        val session = login(admin.email)
        val body =
            session
                .perform(get("/api/orgs/{orgId}/dashboard", orgA))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val json = objectMapper.readTree(body)

        assertThat(json.get("memberCount").asLong()).isEqualTo(1) // admin 뿐
        assertThat(json.get("byRole").get("MEMBER").asLong()).isZero()
        assertThat(json.get("byEmploymentType").get("FULL_TIME").asLong()).isZero()
        assertThat(json.get("byDepartment").size()).isZero() // orgB 부서 미노출
        assertThat(json.get("bySite").size()).isZero()
        assertThat(json.get("pendingInvitations").asLong()).isZero()
        assertThat(json.get("recentJoins30d").asLong()).isZero()
    }

    // ---- 인가 ----

    @Test
    fun `타 org 의 ORG_ADMIN 은 대시보드를 볼 수 없다(격리)`() {
        val bAdmin = saveUser("dash-b-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgB, userId = bAdmin.id!!, role = OrgRole.ORG_ADMIN.name))
        val session = login(bAdmin.email)
        session.perform(get("/api/orgs/{orgId}/dashboard", orgA)).andExpect(status().isForbidden)
    }

    @Test
    fun `일반 멤버는 대시보드가 거부된다`() {
        val member = saveUser("dash-plain@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))
        val session = login(member.email)
        session.perform(get("/api/orgs/{orgId}/dashboard", orgA)).andExpect(status().isForbidden)
    }

    @Test
    fun `플랫폼 ADMIN 은 임의 org 의 대시보드를 본다`() {
        val platformAdmin = saveUser("dash-platform@example.com", role = UserRole.ADMIN)
        val session = login(platformAdmin.email)
        session.perform(get("/api/orgs/{orgId}/dashboard", orgA)).andExpect(status().isOk)
    }

    @Test
    fun `사용자 베어러 토큰으로는 대시보드를 읽을 수 없다(confused-deputy 차단)`() {
        val admin = saveUser("dash-bearer-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val token = userBearerToken(admin.id!!, "openid org.read")
        mockMvc
            .perform(
                get("/api/orgs/{orgId}/dashboard", orgA).header("Authorization", "Bearer $token"),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `미인증은 대시보드 조회가 401 이다`() {
        mockMvc.perform(get("/api/orgs/{orgId}/dashboard", orgA)).andExpect(status().isUnauthorized)
    }

    // ---- helpers ----

    private fun deptNode(
        json: JsonNode,
        name: String,
    ): JsonNode = json.get("byDepartment").first { it.get("name").asText() == name }

    private fun saveInvitation(
        orgId: UUID,
        email: String,
        status: InvitationStatus,
        expiresAt: Instant,
    ): OrgInvitation =
        invitationRepository.save(
            OrgInvitation(
                orgId = orgId,
                email = email,
                tokenHash =
                    UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .padEnd(64, '0'),
                status = status.name,
                expiresAt = expiresAt,
            ),
        )

    private fun saveHistory(
        orgId: UUID,
        userId: UUID,
        changeType: MembershipChangeType,
        recordedAt: Instant,
    ): MembershipHistory =
        historyRepository.save(
            MembershipHistory(
                orgId = orgId,
                userId = userId,
                role = OrgRole.MEMBER.name,
                employmentStatus = EmploymentStatus.EMPLOYED.name,
                changeType = changeType.name,
                recordedAt = recordedAt,
            ),
        )

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
