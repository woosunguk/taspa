package com.taspa.server.admin

import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 조직 관리 콘솔(Phase 0ب-A) 통합 테스트 — 권한(비ADMIN 403), 페이지 렌더(/admin/orgs), API CRUD(생성·목록의
 * memberCount·멤버 upsert·상태변경). 기존 /api/admin/orgs(AdminOrgController)를 소비하는 경로.
 */
class AdminOrgConsoleIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val adminEmail = "orgs-admin@example.com"
    private val memberEmail = "orgs-member@example.com"
    private val password = "SecureP@ssw0rd123"

    @BeforeEach
    fun setUp() {
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()
        userRepository.save(
            User(email = adminEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true, role = UserRole.ADMIN.name),
        )
        userRepository.save(
            User(email = memberEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
        )
    }

    @Test
    fun `조직 콘솔 페이지·API 는 비ADMIN 에 403, ADMIN 에 200`() {
        val memberSession = login(memberEmail)
        memberSession.perform(get("/admin/orgs")).andExpect(status().isForbidden)
        memberSession.perform(get("/api/admin/orgs")).andExpect(status().isForbidden)

        val adminSession = login(adminEmail)
        adminSession
            .perform(get("/admin/orgs"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"org-list\"")))
            .andExpect(content().string(containsString("/admin/orgs")))
        adminSession.perform(get("/api/admin/orgs")).andExpect(status().isOk)

        // 미인증은 로그인 진입점으로 유도된다.
        mockMvc.perform(get("/admin/orgs")).andExpect(status().is3xxRedirection)
    }

    @Test
    fun `조직 콘솔 네비에 조직·캘린더 링크가 있다`() {
        val adminSession = login(adminEmail)
        adminSession
            .perform(get("/admin/orgs"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("href=\"/admin/orgs\"")))
            .andExpect(content().string(containsString("href=\"/admin/calendar\"")))
    }

    @Test
    fun `조직 생성 후 목록이 memberCount 를 포함하고 멤버 upsert 가 반영된다`() {
        val adminSession = login(adminEmail)
        val orgId =
            adminSession
                .perform(
                    post("/api/admin/orgs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"Acme Console"}""")
                        .with(csrf()),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.slug").value("acme-console"))
                .andExpect(jsonPath("$.memberCount").value(0))
                .andReturn()
                .response
                .let {
                    com.fasterxml.jackson.databind
                        .ObjectMapper()
                        .readTree(it.contentAsString)
                        .get("id")
                        .asText()
                }

        val member = userRepository.findByEmail(memberEmail)!!
        adminSession
            .perform(
                post("/api/admin/orgs/$orgId/members")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"userId":"${member.id}","role":"ORG_ADMIN"}""")
                    .with(csrf()),
            ).andExpect(status().isCreated)

        // 목록의 memberCount 가 1 로 반영된다.
        adminSession
            .perform(get("/api/admin/orgs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].memberCount").value(1))

        // 멤버 목록에 이메일이 노출된다.
        adminSession
            .perform(get("/api/admin/orgs/$orgId/members"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].email").value(memberEmail))
            .andExpect(jsonPath("$[0].role").value("ORG_ADMIN"))
    }

    @Test
    fun `상태 변경으로 조직을 정지·활성화한다`() {
        val adminSession = login(adminEmail)
        val orgId =
            organizationRepository
                .save(
                    com.taspa.server.domain.org
                        .Organization(slug = "toggle", name = "Toggle"),
                ).id!!

        adminSession
            .perform(
                put("/api/admin/orgs/$orgId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"status":"SUSPENDED"}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("SUSPENDED"))
        assertThat(organizationRepository.findById(orgId).get().status).isEqualTo("SUSPENDED")
    }

    private fun login(email: String): WebSession {
        val session = webSession()
        session
            .perform(post("/login/identifier").param("email", email).with(csrf()))
            .andExpect(redirectedUrl("/login/password"))
        session
            .perform(post("/login/password").param("username", email).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/account"))
        return session
    }
}
