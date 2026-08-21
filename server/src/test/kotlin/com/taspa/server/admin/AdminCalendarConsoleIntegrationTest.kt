package com.taspa.server.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.calendar.CalendarEventRepository
import com.taspa.server.domain.calendar.CalendarFeedRepository
import com.taspa.server.domain.org.Organization
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * 캘린더 관리 콘솔(Phase 0ب-B) 통합 테스트 — 권한(비ADMIN 403), 페이지 렌더(/admin/calendar),
 * 누락 API 보강(피드 삭제·활성 토글·이벤트 미리보기), 삭제 시 이벤트 CASCADE, 구독 URL SSRF 거부.
 */
class AdminCalendarConsoleIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var feedRepository: CalendarFeedRepository

    @Autowired lateinit var eventRepository: CalendarEventRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var objectMapper: ObjectMapper

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val adminEmail = "cal-admin@example.com"
    private val memberEmail = "cal-member@example.com"
    private val password = "SecureP@ssw0rd123"
    private lateinit var orgId: UUID

    private fun ics() =
        """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//taspa//test//EN
        BEGIN:VEVENT
        UID:cal-console-1
        SUMMARY:Console Event
        DTSTART:20260810T090000Z
        DTEND:20260810T100000Z
        CATEGORIES:EVENT
        END:VEVENT
        END:VCALENDAR
        """.trimIndent()

    @BeforeEach
    fun setUp() {
        eventRepository.deleteAll()
        feedRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()
        orgId = organizationRepository.save(Organization(slug = "cal-org", name = "Cal Org")).id!!
        userRepository.save(
            User(email = adminEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true, role = UserRole.ADMIN.name),
        )
        userRepository.save(
            User(email = memberEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
        )
    }

    @Test
    fun `캘린더 콘솔 페이지·API 는 비ADMIN 에 403, ADMIN 에 200`() {
        val memberSession = login(memberEmail)
        memberSession.perform(get("/admin/calendar")).andExpect(status().isForbidden)
        memberSession.perform(get("/api/admin/orgs/$orgId/calendar/feeds")).andExpect(status().isForbidden)

        val adminSession = login(adminEmail)
        adminSession
            .perform(get("/admin/calendar"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("id=\"feed-list\"")))
            .andExpect(content().string(containsString("href=\"/admin/calendar\"")))
        adminSession.perform(get("/api/admin/orgs/$orgId/calendar/feeds")).andExpect(status().isOk)

        mockMvc.perform(get("/admin/calendar")).andExpect(status().is3xxRedirection)
    }

    @Test
    fun `업로드 피드 등록·임포트 후 이벤트 미리보기가 이벤트를 반환한다`() {
        val adminSession = login(adminEmail)
        val feedId = createUploadFeed(adminSession)

        adminSession
            .perform(
                post("/api/admin/orgs/$orgId/calendar/feeds/$feedId/import")
                    .contentType("text/calendar")
                    .content(ics())
                    .with(csrf()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.imported").value(1))

        adminSession
            .perform(get("/api/admin/orgs/$orgId/calendar/feeds/$feedId/events"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].uid").value("cal-console-1"))
            .andExpect(jsonPath("$.total").value(1))
    }

    @Test
    fun `피드 활성 토글이 반영된다`() {
        val adminSession = login(adminEmail)
        val feedId = createUploadFeed(adminSession)

        adminSession
            .perform(
                patch("/api/admin/orgs/$orgId/calendar/feeds/$feedId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"enabled":false}""")
                    .with(csrf()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(false))
        assertThat(feedRepository.findById(UUID.fromString(feedId)).get().enabled).isFalse()
    }

    @Test
    fun `피드 삭제 시 소속 이벤트가 CASCADE 로 함께 삭제된다`() {
        val adminSession = login(adminEmail)
        val feedId = createUploadFeed(adminSession)
        adminSession
            .perform(
                post("/api/admin/orgs/$orgId/calendar/feeds/$feedId/import")
                    .contentType("text/calendar")
                    .content(ics())
                    .with(csrf()),
            ).andExpect(status().isOk)
        assertThat(eventRepository.findByFeedId(UUID.fromString(feedId))).hasSize(1)

        adminSession
            .perform(delete("/api/admin/orgs/$orgId/calendar/feeds/$feedId").with(csrf()))
            .andExpect(status().isNoContent)

        assertThat(feedRepository.findById(UUID.fromString(feedId))).isEmpty
        assertThat(eventRepository.findByFeedId(UUID.fromString(feedId))).isEmpty()
    }

    @Test
    fun `구독 URL 등록은 SSRF 방어를 경유해 사설·http 를 거부한다`() {
        val adminSession = login(adminEmail)
        adminSession
            .perform(
                post("/api/admin/orgs/$orgId/calendar/feeds")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"bad","type":"HOLIDAY","sourceUrl":"http://169.254.169.254/meta"}""")
                    .with(csrf()),
            ).andExpect(status().isBadRequest)
        assertThat(feedRepository.findByOrgId(orgId)).isEmpty()
    }

    private fun createUploadFeed(session: WebSession): String {
        val body =
            session
                .perform(
                    post("/api/admin/orgs/$orgId/calendar/feeds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"upload","type":"EVENT"}""")
                        .with(csrf()),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body).get("id").asText()
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
