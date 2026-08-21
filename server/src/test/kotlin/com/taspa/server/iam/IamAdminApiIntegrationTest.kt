package com.taspa.server.iam

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.iam.IamPolicy
import com.taspa.server.domain.iam.IamPolicyRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.Session
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * IAM 정책 관리 API(관리 계층) 통합 테스트: 정책 CRUD / systemManaged 불변성(409) / 부착 후 시뮬레이터 반영 /
 * 비관리자 403. 코어 정책 엔진은 이미 검증됨(PolicyEngineTest) — 여기서는 관리 계층의 계약만 검증한다.
 */
class IamAdminApiIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var iamPolicyRepository: IamPolicyRepository

    @Autowired lateinit var indexedSessionRepository: FindByIndexNameSessionRepository<out Session>

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val adminEmail = "iam-admin@example.com"
    private val memberEmail = "iam-member@example.com"
    private val password = "SecureP@ssw0rd123"

    private val validDocument =
        """{"Version":"2026-07-25","Statement":[{"Sid":"allowPing","Effect":"Allow","Action":"ping:Do","Resource":"*"}]}"""

    @BeforeEach
    fun setUp() {
        jdbcTemplate.update("DELETE FROM iam_policy_attachments")
        jdbcTemplate.update("DELETE FROM iam_group_members")
        jdbcTemplate.update("DELETE FROM iam_inline_policies")
        jdbcTemplate.update("DELETE FROM iam_policies")
        jdbcTemplate.update("DELETE FROM iam_principal_groups")
        listOf(adminEmail, memberEmail).forEach { principal ->
            indexedSessionRepository.findByPrincipalName(principal).keys.forEach {
                indexedSessionRepository.deleteById(it)
            }
        }
        userRepository.deleteAll()
        userRepository.save(
            User(
                email = adminEmail,
                passwordHash = passwordEncoder.encode(password),
                emailVerified = true,
                role = UserRole.ADMIN.name,
            ),
        )
        userRepository.save(
            User(email = memberEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
        )
    }

    @Test
    fun `policy CRUD lifecycle with validation and duplicate guards`() {
        val admin = login(adminEmail)

        // 생성 — 유효 문서는 201 + 파싱된 문장 수 요약.
        val created =
            admin
                .perform(
                    post("/api/admin/iam/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"crud-policy","description":"d","document":$documentJson}""")
                        .with(csrf()),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.name").value("crud-policy"))
                .andExpect(jsonPath("$.statementCount").value(1))
                .andExpect(jsonPath("$.systemManaged").value(false))
                .andReturn()
                .response.contentAsString
        val id = Regex(""""id":"([0-9a-f-]+)"""").find(created)!!.groupValues[1]

        // 잘못된 문서(Statement 누락)는 400 IAM_VALIDATION.
        admin
            .perform(
                post("/api/admin/iam/policies")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"bad-policy","document":"{\"bad\":true}"}""")
                    .with(csrf()),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("IAM_VALIDATION"))

        // 같은 범위 중복 이름은 409 IAM_CONFLICT.
        admin
            .perform(
                post("/api/admin/iam/policies")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"crud-policy","document":$documentJson}""")
                    .with(csrf()),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("IAM_CONFLICT"))

        // 조회.
        admin
            .perform(get("/api/admin/iam/policies"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
        admin
            .perform(get("/api/admin/iam/policies/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("crud-policy"))

        // 수정.
        admin
            .perform(
                put("/api/admin/iam/policies/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"description":"updated","document":$documentJson}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.description").value("updated"))

        // 삭제.
        admin
            .perform(delete("/api/admin/iam/policies/$id").with(csrf()))
            .andExpect(status().isNoContent)
        admin.perform(get("/api/admin/iam/policies/$id")).andExpect(status().isNotFound)
    }

    @Test
    fun `system-managed policy rejects update and delete with 409`() {
        val admin = login(adminEmail)
        val system =
            iamPolicyRepository.save(
                IamPolicy(name = "system-policy", document = validDocument, systemManaged = true),
            )
        val id = system.id!!

        admin
            .perform(
                put("/api/admin/iam/policies/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"document":$documentJson}""")
                    .with(csrf()),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("IAM_POLICY_IMMUTABLE"))

        admin
            .perform(delete("/api/admin/iam/policies/$id").with(csrf()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("IAM_POLICY_IMMUTABLE"))
    }

    @Test
    fun `attaching a managed policy is reflected in the simulator`() {
        val admin = login(adminEmail)
        val memberId = userRepository.findByEmail(memberEmail)!!.id!!

        val created =
            admin
                .perform(
                    post("/api/admin/iam/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"grant-ping","document":$documentJson}""")
                        .with(csrf()),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        val policyId = Regex(""""id":"([0-9a-f-]+)"""").find(created)!!.groupValues[1]

        // 부착 전에는 이 사용자(비관리자·정책 없음)에게 거부.
        admin
            .perform(simulateSession(memberId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.effect").value("DENY"))

        // managed 정책 부착.
        admin
            .perform(
                post("/api/admin/iam/attachments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"policyId":"$policyId","principalType":"USER","principalId":"$memberId"}""")
                    .with(csrf()),
            ).andExpect(status().isNoContent)

        // 부착 후에는 허용으로 반영(Sid 매치).
        admin
            .perform(simulateSession(memberId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.effect").value("ALLOW"))
            .andExpect(jsonPath("$.matchedSid").value("allowPing"))
    }

    @Test
    fun `iam api and page are 403 for non-admins`() {
        val member = login(memberEmail)
        member.perform(get("/admin/iam")).andExpect(status().isForbidden)
        member.perform(get("/api/admin/iam/policies")).andExpect(status().isForbidden)
        member
            .perform(
                post("/api/admin/iam/policies")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"x","document":$documentJson}""")
                    .with(csrf()),
            ).andExpect(status().isForbidden)
    }

    // ---- helpers ----

    /** validDocument 를 JSON 문자열 리터럴로 임베드(본문에 그대로 넣기 위한 이스케이프). */
    private val documentJson: String
        get() = "\"" + validDocument.replace("\"", "\\\"") + "\""

    private fun simulateSession(userId: UUID) =
        post("/api/admin/iam/simulate")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """{"subjectType":"SESSION","userId":"$userId","action":"ping:Do","resource":"trn:taspa:iam:::x/1"}""",
            ).with(csrf())

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
}
