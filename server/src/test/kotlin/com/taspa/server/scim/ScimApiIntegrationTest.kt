package com.taspa.server.scim

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.audit.AuditEventRepository
import com.taspa.server.domain.org.MembershipHistoryRepository
import com.taspa.server.domain.org.MembershipStatus
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrgRole
import com.taspa.server.domain.org.OrgStatus
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserStatus
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import com.taspa.server.token.JwkStorageService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * SCIM 2.0 프로비저닝(조직 3c) 통합 테스트 — 테넌트 앵커는 토큰 org_id 클레임이며 모든 효과가 그 org 에
 * 한정됨을 검증한다. 핵심 불변식: users 테이블 비파괴(비활성/삭제는 멤버십만), 소셜 전용 계정 생성 패턴
 * (password NULL·미검증·무통지), SCD 이력(JOINED/ATTRIBUTES_UPDATED/REMOVED) 자동 기록.
 * M2M 토큰은 consumption 테스트와 동일하게 앱 JWKSource 로 직접 서명해 재현한다.
 */
class ScimApiIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var historyRepository: MembershipHistoryRepository

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
    private lateinit var sessionUserEmail: String
    private lateinit var sessionUserId: UUID

    @BeforeEach
    fun setUp() {
        historyRepository.deleteAll()
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()

        orgA = organizationRepository.save(Organization(slug = "scim-a", name = "SCIM A")).id!!
        orgB = organizationRepository.save(Organization(slug = "scim-b", name = "SCIM B")).id!!

        sessionUserEmail = "scim-admin@example.com"
        sessionUserId =
            userRepository
                .save(
                    User(email = sessionUserEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
                ).id!!
    }

    // ---- POST /Users ----

    @Test
    fun `POST 는 신규 사용자를 소셜 전용 패턴으로 생성하고 멤버십·속성·JOINED 이력을 남긴다`() {
        val token = scimToken(orgA)
        val body =
            """
            {
              "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
              "userName": "hire-1@corp.example.com",
              "externalId": "wd-1001",
              "name": {"formatted": "Kim Hire"},
              "emails": [{"value": "hire-1@corp.example.com", "primary": true}],
              "active": true,
              "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User": {
                "employeeNumber": "E-1001", "title": "Engineer", "department": "Platform"
              }
            }
            """.trimIndent()
        mockMvc
            .perform(scimPost("/scim/v2/Users", token, body))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.userName").value("hire-1@corp.example.com"))
            .andExpect(jsonPath("$.externalId").value("wd-1001"))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(
                jsonPath("$['urn:ietf:params:scim:schemas:extension:enterprise:2.0:User'].employeeNumber")
                    .value("E-1001"),
            )

        val user = userRepository.findByEmail("hire-1@corp.example.com")!!
        // 소셜 전용 패턴: 폼 로그인 불가(password NULL) + 미검증(첫 로그인 시 이메일 게이트가 검증).
        assertThat(user.passwordHash).isNull()
        assertThat(user.emailVerified).isFalse()
        assertThat(user.displayName).isEqualTo("Kim Hire")

        val membership = membershipRepository.findByOrgIdAndUserId(orgA, user.id!!)!!
        assertThat(membership.statusEnum()).isEqualTo(MembershipStatus.ACTIVE)
        assertThat(membership.employeeId).isEqualTo("E-1001")
        assertThat(membership.jobTitle).isEqualTo("Engineer")
        assertThat(membership.department).isEqualTo("Platform")
        assertThat(membership.scimExternalId).isEqualTo("wd-1001")

        val history = historyRepository.findByOrgIdAndUserIdOrderByRecordedAtDesc(orgA, user.id!!)
        assertThat(history.map { it.changeType }).contains("JOINED")
    }

    @Test
    fun `POST 기존 사용자는 users 행을 재사용하고 멤버십만 만든다`() {
        val existing =
            userRepository.save(
                User(email = "veteran@corp.example.com", passwordHash = passwordEncoder.encode(password), emailVerified = true),
            )
        val before = userRepository.count()
        mockMvc
            .perform(scimPost("/scim/v2/Users", scimToken(orgA), """{"userName":"veteran@corp.example.com"}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(existing.id.toString()))
        assertThat(userRepository.count()).isEqualTo(before)
        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, existing.id!!)).isNotNull
        // 기존 계정의 자격증명·검증 상태는 불변.
        val reloaded = userRepository.findById(existing.id!!).orElseThrow()
        assertThat(reloaded.passwordHash).isNotNull()
        assertThat(reloaded.emailVerified).isTrue()
    }

    @Test
    fun `POST 이미 그 org 멤버면 409 uniqueness SCIM 오류다`() {
        val user = provisionedUser(orgA, "dup@corp.example.com")
        mockMvc
            .perform(scimPost("/scim/v2/Users", scimToken(orgA), """{"userName":"dup@corp.example.com"}"""))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.schemas[0]").value("urn:ietf:params:scim:api:messages:2.0:Error"))
            .andExpect(jsonPath("$.scimType").value("uniqueness"))
            .andExpect(jsonPath("$.status").value("409"))
        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, user.id!!)).isNotNull
    }

    // ---- GET /Users (목록·필터·페이지네이션) ----

    @Test
    fun `GET 목록은 토큰 org 의 멤버만 노출한다(타 org 미노출)`() {
        provisionedUser(orgA, "a-1@corp.example.com")
        provisionedUser(orgB, "b-1@corp.example.com")
        val body =
            mockMvc
                .perform(scimGet("/scim/v2/Users", scimToken(orgA)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.totalResults").value(1))
                .andReturn()
                .response.contentAsString
        assertThat(body).contains("a-1@corp.example.com").doesNotContain("b-1@corp.example.com")
        // 내부 필드 미노출.
        assertThat(body).doesNotContain("passwordHash").doesNotContain("password")
    }

    @Test
    fun `GET filter userName·externalId 로 조회하고 미지원 필터는 400 invalidFilter 다`() {
        val user = provisionedUser(orgA, "f-1@corp.example.com", externalId = "wd-42")
        provisionedUser(orgA, "f-2@corp.example.com")

        mockMvc
            .perform(scimGet("/scim/v2/Users", scimToken(orgA)).param("filter", """userName eq "f-1@corp.example.com""""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalResults").value(1))
            .andExpect(jsonPath("$.Resources[0].id").value(user.id.toString()))

        mockMvc
            .perform(scimGet("/scim/v2/Users", scimToken(orgA)).param("filter", """externalId eq "wd-42""""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalResults").value(1))
            .andExpect(jsonPath("$.Resources[0].externalId").value("wd-42"))

        mockMvc
            .perform(scimGet("/scim/v2/Users", scimToken(orgA)).param("filter", """emails co "corp""""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.scimType").value("invalidFilter"))
    }

    @Test
    fun `GET 페이지네이션은 1-base startIndex 와 count 로 자른다`() {
        (1..3).forEach { provisionedUser(orgA, "page-$it@corp.example.com") }
        mockMvc
            .perform(
                scimGet("/scim/v2/Users", scimToken(orgA)).param("startIndex", "2").param("count", "1"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.totalResults").value(3))
            .andExpect(jsonPath("$.startIndex").value(2))
            .andExpect(jsonPath("$.itemsPerPage").value(1))
            .andExpect(jsonPath("$.Resources.length()").value(1))
    }

    @Test
    fun `GET Users id 는 타 org 멤버십이면 404 다`() {
        val userB = provisionedUser(orgB, "only-b@corp.example.com")
        mockMvc
            .perform(scimGet("/scim/v2/Users/${userB.id}", scimToken(orgA)))
            .andExpect(status().isNotFound)
    }

    // ---- PATCH (active 토글) ----

    @Test
    fun `PATCH active=false 는 멤버십만 SUSPENDED+TERMINATED 로 만들고 users 행은 불변이다`() {
        val user = provisionedUser(orgA, "leave@corp.example.com")
        val body = """{"schemas":["urn:ietf:params:scim:api:messages:2.0:PatchOp"],"Operations":[{"op":"replace","value":{"active":false}}]}"""
        mockMvc
            .perform(scimPatch("/scim/v2/Users/${user.id}", scimToken(orgA), body))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.active").value(false))

        val membership = membershipRepository.findByOrgIdAndUserId(orgA, user.id!!)!!
        assertThat(membership.statusEnum()).isEqualTo(MembershipStatus.SUSPENDED)
        assertThat(membership.employmentStatus).isEqualTo("TERMINATED")
        // ★멀티테넌시 불변식 — users 테이블은 절대 건드리지 않는다(다른 org 소속일 수 있음).
        val reloaded = userRepository.findById(user.id!!).orElseThrow()
        assertThat(reloaded.status).isEqualTo(UserStatus.ACTIVE.name)
        // 이력(ATTRIBUTES_UPDATED 스냅샷)에 TERMINATED 가 남는다.
        val history = historyRepository.findByOrgIdAndUserIdOrderByRecordedAtDesc(orgA, user.id!!)
        assertThat(history.first().changeType).isEqualTo("ATTRIBUTES_UPDATED")
        assertThat(history.first().employmentStatus).isEqualTo("TERMINATED")
    }

    @Test
    fun `PATCH path active 로 재활성하면 ACTIVE+EMPLOYED 로 돌아온다(Azure 문자열 True 수용)`() {
        val user = provisionedUser(orgA, "rehire@corp.example.com", active = false)
        val body = """{"Operations":[{"op":"Replace","path":"active","value":"True"}]}"""
        mockMvc
            .perform(scimPatch("/scim/v2/Users/${user.id}", scimToken(orgA), body))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.active").value(true))
        val membership = membershipRepository.findByOrgIdAndUserId(orgA, user.id!!)!!
        assertThat(membership.statusEnum()).isEqualTo(MembershipStatus.ACTIVE)
        assertThat(membership.employmentStatus).isEqualTo("EMPLOYED")
    }

    @Test
    fun `PATCH 는 미지원 path·remove op 를 무시하고 같은 요청에 묶인 active 토글은 반영한다(Azure 호환)`() {
        val user = provisionedUser(orgA, "badpatch@corp.example.com")
        // Azure 기본 매핑 형태: path=displayName + manager remove + active=false 가 한 PatchOp 에 묶여 온다.
        // 미지원 path/remove 가 400 이면 오프보딩(active=false)까지 통째로 실패한다 — 무시 후 active 는 반영돼야 한다.
        val body =
            """
            {"Operations":[
              {"op":"Replace","path":"displayName","value":"홍길동(퇴사)"},
              {"op":"remove","path":"urn:ietf:params:scim:schemas:extension:enterprise:2.0:User:manager"},
              {"op":"Replace","path":"active","value":"False"}
            ]}
            """.trimIndent()
        mockMvc
            .perform(scimPatch("/scim/v2/Users/${user.id}", scimToken(orgA), body))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.active").value(false))
        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, user.id!!)!!.statusEnum())
            .isEqualTo(MembershipStatus.SUSPENDED)
        // displayName(users 전역 속성)은 SCIM 갱신 경로에서 절대 변경되지 않는다(테넌시 격리).
        assertThat(userRepository.findById(user.id!!).orElseThrow().displayName).isNull()
        // 진짜 구문 오류(미지원 op 이름)는 여전히 400 이다.
        mockMvc
            .perform(
                scimPatch(
                    "/scim/v2/Users/${user.id}",
                    scimToken(orgA),
                    """{"Operations":[{"op":"noop","path":"active","value":"true"}]}""",
                ),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.scimType").value("invalidValue"))
    }

    // ---- PUT ----

    @Test
    fun `PUT 은 HR 속성·active 만 반영하고 displayName(전역 속성)은 무시한다`() {
        val user = provisionedUser(orgA, "putme@corp.example.com")
        val body =
            """
            {
              "userName": "putme@corp.example.com",
              "displayName": "Replaced Name",
              "active": false,
              "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User": {"employeeNumber": "E-77", "title": "Manager"}
            }
            """.trimIndent()
        mockMvc
            .perform(scimPut("/scim/v2/Users/${user.id}", scimToken(orgA), body))
            .andExpect(status().isOk)
            // ★org 경계: displayName 은 users 전역 속성 — 한 org 의 프로비저너가 플랫폼 전역 표기를
            // 덮어쓸 수 없다(신규 생성 시에만 설정). 응답은 현재 전역 값(null → 필드 생략)을 에코한다.
            .andExpect(jsonPath("$.displayName").doesNotExist())
            .andExpect(jsonPath("$.active").value(false))
        assertThat(userRepository.findById(user.id!!).orElseThrow().displayName).isNull()
        val membership = membershipRepository.findByOrgIdAndUserId(orgA, user.id!!)!!
        assertThat(membership.employeeId).isEqualTo("E-77")
        assertThat(membership.jobTitle).isEqualTo("Manager")
        assertThat(membership.statusEnum()).isEqualTo(MembershipStatus.SUSPENDED)
    }

    @Test
    fun `PUT 의 userName 변경은 조용히 무시하지 않고 400 mutability 로 거부한다(식별자 드리프트 방지)`() {
        val user = provisionedUser(orgA, "rename-me@corp.example.com")
        mockMvc
            .perform(
                scimPut("/scim/v2/Users/${user.id}", scimToken(orgA), """{"userName":"renamed@corp.example.com"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.scimType").value("mutability"))
        assertThat(userRepository.findById(user.id!!).orElseThrow().email).isEqualTo("rename-me@corp.example.com")
    }

    // ---- DELETE ----

    @Test
    fun `DELETE 는 멤버십만 제거하고(REMOVED 이력) users 행은 잔존한다`() {
        val user = provisionedUser(orgA, "offboard@corp.example.com")
        mockMvc
            .perform(scimDelete("/scim/v2/Users/${user.id}", scimToken(orgA)))
            .andExpect(status().isNoContent)
        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, user.id!!)).isNull()
        // ★계정 삭제 아님 — users 행 잔존(다른 org 소속 가능).
        assertThat(userRepository.findById(user.id!!)).isPresent
        val history = historyRepository.findByOrgIdAndUserIdOrderByRecordedAtDesc(orgA, user.id!!)
        assertThat(history.first().changeType).isEqualTo("REMOVED")
    }

    @Test
    fun `DELETE 는 타 org 멤버십에 도달할 수 없다(404)`() {
        val userB = provisionedUser(orgB, "safe-b@corp.example.com")
        mockMvc
            .perform(scimDelete("/scim/v2/Users/${userB.id}", scimToken(orgA)))
            .andExpect(status().isNotFound)
        assertThat(membershipRepository.findByOrgIdAndUserId(orgB, userB.id!!)).isNotNull
    }

    // ---- 마지막 ORG_ADMIN 보호 ----

    @Test
    fun `마지막 ORG_ADMIN 은 SCIM 비활성화·삭제 모두 409 로 거부되고 멤버십은 유지된다`() {
        val admin = userRepository.save(User(email = "last-admin@corp.example.com", passwordHash = null, emailVerified = false))
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = OrgRole.ORG_ADMIN.name))

        val body = """{"Operations":[{"op":"replace","value":{"active":false}}]}"""
        mockMvc
            .perform(scimPatch("/scim/v2/Users/${admin.id}", scimToken(orgA), body))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.schemas[0]").value("urn:ietf:params:scim:api:messages:2.0:Error"))
            .andExpect(jsonPath("$.scimType").value("mutability"))
            .andExpect(jsonPath("$.status").value("409"))
        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, admin.id!!)!!.statusEnum())
            .isEqualTo(MembershipStatus.ACTIVE)

        mockMvc
            .perform(scimDelete("/scim/v2/Users/${admin.id}", scimToken(orgA)))
            .andExpect(status().isConflict)
        assertThat(membershipRepository.findByOrgIdAndUserId(orgA, admin.id!!)).isNotNull
    }

    // ---- externalId org 범위 유니크(V23) ----

    @Test
    fun `externalId 는 org 범위 유니크다 - 같은 org 중복은 409, 타 org 재사용은 허용`() {
        provisionedUser(orgA, "ext-1@corp.example.com", externalId = "wd-100")
        val other = provisionedUser(orgA, "ext-2@corp.example.com")
        mockMvc
            .perform(
                scimPut("/scim/v2/Users/${other.id}", scimToken(orgA), """{"userName":"ext-2@corp.example.com","externalId":"wd-100"}"""),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.scimType").value("uniqueness"))

        // 부분 유니크 인덱스는 org 범위 — 다른 org 에서는 같은 externalId 가 허용된다.
        val userB = provisionedUser(orgB, "ext-3@corp.example.com")
        mockMvc
            .perform(
                scimPut("/scim/v2/Users/${userB.id}", scimToken(orgB), """{"userName":"ext-3@corp.example.com","externalId":"wd-100"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.externalId").value("wd-100"))
    }

    // ---- audit ----

    @Test
    fun `SCIM 변경 작업은 org 결속 audit 이벤트를 남긴다(PII 최소 - 이메일 미기록)`() {
        val token = scimToken(orgA)
        mockMvc
            .perform(scimPost("/scim/v2/Users", token, """{"userName":"audited@corp.example.com"}"""))
            .andExpect(status().isCreated)
        val user = userRepository.findByEmail("audited@corp.example.com")!!
        mockMvc
            .perform(
                scimPatch("/scim/v2/Users/${user.id}", token, """{"Operations":[{"op":"replace","value":{"active":false}}]}"""),
            ).andExpect(status().isOk)
        mockMvc.perform(scimDelete("/scim/v2/Users/${user.id}", token)).andExpect(status().isNoContent)

        val events = auditEventRepository.findByOrgIdOrderByCreatedAtDesc(orgA, PageRequest.of(0, 50))
        assertThat(events.map { it.type })
            .contains("SCIM_USER_CREATED", "SCIM_USER_DEACTIVATED", "SCIM_USER_DELETED")
        val created = events.first { it.type == "SCIM_USER_CREATED" }
        assertThat(created.orgId).isEqualTo(orgA)
        assertThat(created.detail).contains(user.id.toString()).doesNotContain("audited@corp.example.com")
    }

    // ---- 조직 정지 ----

    @Test
    fun `SUSPENDED 조직의 SCIM 토큰은 403 이다(정지 중 전역 계정 생성 차단)`() {
        val org = organizationRepository.findById(orgA).orElseThrow()
        org.status = OrgStatus.SUSPENDED.name
        organizationRepository.save(org)

        mockMvc.perform(scimGet("/scim/v2/Users", scimToken(orgA))).andExpect(status().isForbidden)
        mockMvc
            .perform(scimPost("/scim/v2/Users", scimToken(orgA), """{"userName":"blocked@corp.example.com"}"""))
            .andExpect(status().isForbidden)
        assertThat(userRepository.findByEmail("blocked@corp.example.com")).isNull()
    }

    // ---- 인가 ----

    @Test
    fun `scope 없는 M2M 토큰은 403 이다`() {
        val token = signedToken("org.read", orgA)
        mockMvc.perform(scimGet("/scim/v2/Users", token)).andExpect(status().isForbidden)
    }

    @Test
    fun `org 미결속 org_scim 토큰은 403 이다(fail-closed)`() {
        val token = signedToken("org.scim", null)
        mockMvc.perform(scimGet("/scim/v2/Users", token)).andExpect(status().isForbidden)
    }

    @Test
    fun `사용자 위임 토큰은 org_scim scope 가 있어도 403 이다`() {
        // subject 가 실제 users.id 로 해석되는 토큰 = authorization_code 사용자 토큰 모사.
        val token = signedToken("org.scim", orgA, subject = sessionUserId.toString())
        mockMvc.perform(scimGet("/scim/v2/Users", token)).andExpect(status().isForbidden)
    }

    @Test
    fun `로그인 세션으로는 SCIM 에 접근할 수 없다(베어러 전용 체인)`() {
        val session = login(sessionUserEmail)
        session.perform(get("/scim/v2/Users")).andExpect(status().isUnauthorized)
        session
            .perform(
                post("/scim/v2/Users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userName":"nope@corp.example.com"}""")
                    .with(csrf()),
            ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `무토큰 요청은 401 이다`() {
        mockMvc.perform(get("/scim/v2/Users")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/scim/v2/ServiceProviderConfig")).andExpect(status().isUnauthorized)
    }

    // ---- 디스커버리 ----

    @Test
    fun `디스커버리 3종은 patch·filter 지원과 bulk 미지원을 광고한다`() {
        val token = scimToken(orgA)
        mockMvc
            .perform(scimGet("/scim/v2/ServiceProviderConfig", token))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.patch.supported").value(true))
            .andExpect(jsonPath("$.filter.supported").value(true))
            .andExpect(jsonPath("$.bulk.supported").value(false))
        mockMvc
            .perform(scimGet("/scim/v2/ResourceTypes", token))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.Resources[0].id").value("User"))
        mockMvc
            .perform(scimGet("/scim/v2/Schemas", token))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalResults").value(2))
    }

    // ---- helpers ----

    private fun scimToken(orgId: UUID): String = signedToken("org.scim", orgId)

    /** 앱 JWKSource 로 RS256 서명한 M2M 토큰(iss·exp·scope[·org_id]) — HR 프로비저너 토큰 모사(consumption 선례). */
    private fun signedToken(
        scope: String,
        orgId: UUID?,
        subject: String = "svc-hr-scim",
    ): String {
        val encoder = NimbusJwtEncoder(jwkSource)
        val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(jwkStorageService.activeKid()).build()
        val now = Instant.now()
        val builder =
            JwtClaimsSet
                .builder()
                .issuer(issuerUri)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(1, ChronoUnit.HOURS))
                .claim("scope", scope)
        if (orgId != null) builder.claim("org_id", orgId.toString())
        return encoder.encode(JwtEncoderParameters.from(header, builder.build())).tokenValue
    }

    /** SCIM 으로 이미 프로비저닝된 상태를 재현한다(소셜 전용 패턴 users 행 + 멤버십). */
    private fun provisionedUser(
        orgId: UUID,
        email: String,
        externalId: String? = null,
        active: Boolean = true,
    ): User {
        val user =
            userRepository.findByEmail(email)
                ?: userRepository.save(User(email = email, passwordHash = null, emailVerified = false))
        membershipRepository.save(
            OrgMembership(
                orgId = orgId,
                userId = user.id!!,
                status = if (active) MembershipStatus.ACTIVE.name else MembershipStatus.SUSPENDED.name,
                scimExternalId = externalId,
            ),
        )
        return user
    }

    private fun scimGet(
        uri: String,
        token: String,
    ) = get(uri).header("Authorization", "Bearer $token")

    private fun scimPost(
        uri: String,
        token: String,
        body: String,
    ) = post(uri)
        .header("Authorization", "Bearer $token")
        .contentType("application/scim+json")
        .content(body)

    private fun scimPut(
        uri: String,
        token: String,
        body: String,
    ) = put(uri)
        .header("Authorization", "Bearer $token")
        .contentType("application/scim+json")
        .content(body)

    private fun scimPatch(
        uri: String,
        token: String,
        body: String,
    ) = patch(uri)
        .header("Authorization", "Bearer $token")
        .contentType("application/scim+json")
        .content(body)

    private fun scimDelete(
        uri: String,
        token: String,
    ) = delete(uri).header("Authorization", "Bearer $token")

    private fun login(email: String): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", email).with(csrf()))
        session
            .perform(post("/login/password").param("username", email).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
        return session
    }
}
