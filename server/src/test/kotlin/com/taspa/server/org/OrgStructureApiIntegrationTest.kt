package com.taspa.server.org

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.audit.AuditEventRepository
import com.taspa.server.domain.org.Department
import com.taspa.server.domain.org.DepartmentRepository
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
 * 조직 1단계 백엔드(부서 트리 + 사업장 + 멤버 배정) 통합 테스트.
 *  - 부서: 생성(루트·자식)·이름 중복 거부·타 org 부모 거부·rename·자식 있는 부서 삭제 거부·리프 삭제 시 멤버 SET NULL·트리 memberCount.
 *  - 사업장: CRUD·이름 중복·잘못된 timezone 400·삭제 시 멤버 SET NULL.
 *  - 배정: dept·site 배정·타 org 배정 거부(격리)·해제(null)·비멤버 거부.
 *  - 인가: ORG_ADMIN 허용·타 org 403·일반멤버 403·베어러 거부·미인증 401·변경 step-up.
 */
class OrgStructureApiIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var departmentRepository: DepartmentRepository

    @Autowired lateinit var siteRepository: SiteRepository

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
        // departments 는 자기참조 parent_id ON DELETE CASCADE — 행 단위 deleteAll 은 부모 삭제 시 DB 가 자식을
        // 먼저 CASCADE 로 지우고, 이어서 이미 사라진 자식을 지우려다 StaleState(낙관적 잠금 예외)를 낸다.
        // 단일 벌크 DELETE(deleteAllInBatch)는 전 행을 한 문장에 지워 이 이중삭제를 피한다.
        departmentRepository.deleteAllInBatch()
        siteRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()
        auditEventRepository.deleteAll()
        every { mailSender.send(any<SimpleMailMessage>()) } just Runs
        orgA = organizationRepository.save(Organization(slug = "struct-a", name = "Struct A")).id!!
        orgB = organizationRepository.save(Organization(slug = "struct-b", name = "Struct B")).id!!
    }

    // ---- 부서 ----

    @Test
    fun `ORG_ADMIN 은 루트 부서와 자식 부서를 생성한다`() {
        val session = adminSession(orgA, "dept-admin@example.com")
        val rootBody =
            session
                .perform(
                    post("/api/orgs/{orgId}/departments", orgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"본사"}""")
                        .with(csrf()),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        val rootId = objectMapper.readTree(rootBody).get("id").asText()

        session
            .perform(
                post("/api/orgs/{orgId}/departments", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"개발팀","parentId":"$rootId"}""")
                    .with(csrf()),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.parentId").value(rootId))

        assertThat(departmentRepository.findByOrgId(orgA)).hasSize(2)
        val events = auditEventRepository.findByTypeOrderByCreatedAtDesc("ADMIN_ORG_DEPARTMENT_CREATED", PageRequest.of(0, 10))
        assertThat(events).hasSize(2)
    }

    @Test
    fun `형제 부서 이름 중복은 거부된다`() {
        val session = adminSession(orgA, "dept-dup@example.com")
        session
            .perform(
                post("/api/orgs/{orgId}/departments", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"영업"}""")
                    .with(csrf()),
            ).andExpect(status().isCreated)
        session
            .perform(
                post("/api/orgs/{orgId}/departments", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"영업"}""")
                    .with(csrf()),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `타 org 의 부서를 부모로 지정하면 거부된다(격리)`() {
        // orgB 에 부서를 만들고, orgA 관리자가 그것을 부모로 쓰려 하면 거부.
        val foreign = departmentRepository.save(Department(orgId = orgB, name = "B-root"))
        val session = adminSession(orgA, "dept-foreign@example.com")
        session
            .perform(
                post("/api/orgs/{orgId}/departments", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"child","parentId":"${foreign.id}"}""")
                    .with(csrf()),
            ).andExpect(status().isBadRequest)
        assertThat(departmentRepository.findByOrgId(orgA)).isEmpty()
    }

    @Test
    fun `부서 이름을 변경한다`() {
        val session = adminSession(orgA, "dept-rename@example.com")
        val dept = departmentRepository.save(Department(orgId = orgA, name = "Old"))
        session
            .perform(
                put("/api/orgs/{orgId}/departments/{deptId}", orgA, dept.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"New"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("New"))
        assertThat(departmentRepository.findById(dept.id!!).get().name).isEqualTo("New")
    }

    @Test
    fun `자식이 있는 부서 삭제는 거부된다`() {
        val session = adminSession(orgA, "dept-haschild@example.com")
        val root = departmentRepository.save(Department(orgId = orgA, name = "Root"))
        departmentRepository.save(Department(orgId = orgA, parentId = root.id, name = "Child"))
        session
            .perform(delete("/api/orgs/{orgId}/departments/{deptId}", orgA, root.id).with(csrf()))
            .andExpect(status().isBadRequest)
        assertThat(departmentRepository.findById(root.id!!)).isPresent
    }

    @Test
    fun `리프 부서 삭제 시 배정 멤버의 department_id 가 SET NULL 된다`() {
        val admin = saveUser("dept-leaf-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val dept = departmentRepository.save(Department(orgId = orgA, name = "Leaf"))
        val member = saveUser("dept-leaf-member@example.com")
        membershipRepository.save(
            OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name, departmentId = dept.id),
        )

        val session = login(admin.email)
        session
            .perform(delete("/api/orgs/{orgId}/departments/{deptId}", orgA, dept.id).with(csrf()))
            .andExpect(status().isNoContent)
        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, member.id!!)!!.departmentId).isNull()
    }

    @Test
    fun `부서 트리 조회는 직접 배정 멤버 수를 반환한다`() {
        val admin = saveUser("dept-tree-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val dept = departmentRepository.save(Department(orgId = orgA, name = "Eng"))
        val m1 = saveUser("dept-tree-m1@example.com")
        val m2 = saveUser("dept-tree-m2@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = m1.id!!, role = OrgRole.MEMBER.name, departmentId = dept.id))
        membershipRepository.save(OrgMembership(orgId = orgA, userId = m2.id!!, role = OrgRole.MEMBER.name, departmentId = dept.id))

        val session = login(admin.email)
        val body =
            session
                .perform(get("/api/orgs/{orgId}/departments", orgA))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val row = objectMapper.readTree(body).first { it.get("id").asText() == dept.id.toString() }
        assertThat(row.get("memberCount").asLong()).isEqualTo(2)
    }

    // ---- 사업장 ----

    @Test
    fun `ORG_ADMIN 은 사업장을 CRUD 한다`() {
        val session = adminSession(orgA, "site-admin@example.com")
        val createBody =
            session
                .perform(
                    post("/api/orgs/{orgId}/sites", orgA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"강남점","address":"서울","timezone":"Asia/Seoul"}""")
                        .with(csrf()),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.timezone").value("Asia/Seoul"))
                .andReturn()
                .response.contentAsString
        val siteId = objectMapper.readTree(createBody).get("id").asText()

        session
            .perform(
                put("/api/orgs/{orgId}/sites/{siteId}", orgA, siteId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"강남2점"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("강남2점"))

        session
            .perform(get("/api/orgs/{orgId}/sites", orgA))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))

        session
            .perform(delete("/api/orgs/{orgId}/sites/{siteId}", orgA, siteId).with(csrf()))
            .andExpect(status().isNoContent)
        assertThat(siteRepository.findByOrgId(orgA)).isEmpty()
    }

    @Test
    fun `사업장 이름 중복은 거부된다`() {
        val session = adminSession(orgA, "site-dup@example.com")
        siteRepository.save(Site(orgId = orgA, name = "본점"))
        session
            .perform(
                post("/api/orgs/{orgId}/sites", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"본점"}""")
                    .with(csrf()),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `잘못된 타임존 사업장은 400 이다`() {
        val session = adminSession(orgA, "site-tz@example.com")
        session
            .perform(
                post("/api/orgs/{orgId}/sites", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"X","timezone":"Mars/Phobos"}""")
                    .with(csrf()),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `사업장 삭제 시 배정 멤버의 site_id 가 SET NULL 된다`() {
        val admin = saveUser("site-del-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val site = siteRepository.save(Site(orgId = orgA, name = "점포"))
        val member = saveUser("site-del-member@example.com")
        membershipRepository.save(
            OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name, siteId = site.id),
        )

        val session = login(admin.email)
        session
            .perform(delete("/api/orgs/{orgId}/sites/{siteId}", orgA, site.id).with(csrf()))
            .andExpect(status().isNoContent)
        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, member.id!!)!!.siteId).isNull()
    }

    // ---- 멤버 배정 ----

    @Test
    fun `멤버에 부서와 사업장을 배정하고 해제한다`() {
        val admin = saveUser("assign-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val member = saveUser("assign-member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))
        val dept = departmentRepository.save(Department(orgId = orgA, name = "D"))
        val site = siteRepository.save(Site(orgId = orgA, name = "S"))

        val session = login(admin.email)
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/assignment", orgA, member.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"departmentId":"${dept.id}","siteId":"${site.id}"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
        val assigned = membershipRepository.findByOrgIdAndUserId(orgA, member.id!!)!!
        assertThat(assigned.departmentId).isEqualTo(dept.id)
        assertThat(assigned.siteId).isEqualTo(site.id)

        // null 배정 = 해제
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/assignment", orgA, member.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
        val cleared = membershipRepository.findByOrgIdAndUserId(orgA, member.id!!)!!
        assertThat(cleared.departmentId).isNull()
        assertThat(cleared.siteId).isNull()

        val events = auditEventRepository.findByTypeOrderByCreatedAtDesc("ADMIN_ORG_MEMBER_ASSIGNED", PageRequest.of(0, 10))
        assertThat(events).hasSize(2)
    }

    @Test
    fun `타 org 의 부서 배정은 거부된다(격리)`() {
        val admin = saveUser("assign-foreign-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val member = saveUser("assign-foreign-member@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))
        val foreignDept = departmentRepository.save(Department(orgId = orgB, name = "B-dept"))

        val session = login(admin.email)
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/assignment", orgA, member.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"departmentId":"${foreignDept.id}"}""")
                    .with(csrf()),
            ).andExpect(status().isBadRequest)
        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, member.id!!)!!.departmentId).isNull()
    }

    @Test
    fun `비멤버 대상 배정은 거부된다`() {
        val admin = saveUser("assign-nonmember-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val nonMember = saveUser("assign-nonmember@example.com")
        val dept = departmentRepository.save(Department(orgId = orgA, name = "D"))

        val session = login(admin.email)
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/assignment", orgA, nonMember.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"departmentId":"${dept.id}"}""")
                    .with(csrf()),
            ).andExpect(status().isNotFound)
    }

    // ---- 인가 ----

    @Test
    fun `타 org 의 ORG_ADMIN 은 부서를 관리할 수 없다(격리)`() {
        val bAdmin = saveUser("struct-b-admin@example.com")
        membershipRepository.save(OrgMembership(orgId = orgB, userId = bAdmin.id!!, role = OrgRole.ORG_ADMIN.name))
        val session = login(bAdmin.email)
        session.perform(get("/api/orgs/{orgId}/departments", orgA)).andExpect(status().isForbidden)
    }

    @Test
    fun `일반 멤버는 사업장 조회가 거부된다`() {
        val member = saveUser("struct-plain@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = OrgRole.MEMBER.name))
        val session = login(member.email)
        session.perform(get("/api/orgs/{orgId}/sites", orgA)).andExpect(status().isForbidden)
    }

    @Test
    fun `플랫폼 ADMIN 은 임의 org 의 부서를 생성한다`() {
        val platformAdmin = saveUser("struct-platform@example.com", role = UserRole.ADMIN)
        val session = login(platformAdmin.email)
        session
            .perform(
                post("/api/orgs/{orgId}/departments", orgA)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"HQ"}""")
                    .with(csrf()),
            ).andExpect(status().isCreated)
    }

    @Test
    fun `사용자 베어러 토큰으로는 부서 목록을 읽을 수 없다(confused-deputy 차단)`() {
        val admin = saveUser("struct-bearer@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val token = userBearerToken(admin.id!!, "openid org.read")
        mockMvc
            .perform(
                get("/api/orgs/{orgId}/departments", orgA).header("Authorization", "Bearer $token"),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `미인증은 부서 목록 조회가 401 이다`() {
        mockMvc.perform(get("/api/orgs/{orgId}/departments", orgA)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `오래된 auth_time 이면 부서 생성은 REAUTH_REQUIRED 로 거절된다`() {
        val admin = saveUser("struct-stepup@example.com")
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        val session = login(admin.email)
        session.setAttribute(StepUp.AUTH_TIME_SESSION_KEY, Instant.now().minus(Duration.ofMinutes(11)))
        session
            .perform(
                put("/api/orgs/{orgId}/members/{userId}/assignment", orgA, admin.id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{}""")
                    .with(csrf()),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("REAUTH_REQUIRED"))
    }

    // ---- helpers ----

    private fun adminSession(
        orgId: UUID,
        email: String,
    ): WebSession {
        val admin = saveUser(email)
        membershipRepository.save(OrgMembership(orgId = orgId, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))
        return login(admin.email)
    }

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
