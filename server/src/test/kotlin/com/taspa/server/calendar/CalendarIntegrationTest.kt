package com.taspa.server.calendar

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.calendar.dto.FeedCreateRequest
import com.taspa.server.common.exception.AuthException
import com.taspa.server.domain.calendar.CalendarEventRepository
import com.taspa.server.domain.calendar.CalendarFeedRepository
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Base64
import java.util.UUID

/**
 * 캘린더 연동 통합 테스트(Phase 0-E). 업로드→저장, M2M(calendar.read) 조회, ★org 격리(세션 멤버는 타 org
 * 접근 불가), scope 미충족 403, 미인증 401, SSRF 거부(서비스 경계)를 검증한다.
 */
class CalendarIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var feedRepository: CalendarFeedRepository

    @Autowired lateinit var eventRepository: CalendarEventRepository

    @Autowired lateinit var calendarService: CalendarService

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var registeredClientRepository: RegisteredClientRepository

    @Autowired lateinit var objectMapper: ObjectMapper

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val password = "SecureP@ssw0rd123"
    private lateinit var orgA: UUID
    private lateinit var orgB: UUID
    private lateinit var memberEmail: String

    // 신뢰 플랫폼 puller — 전조회 스코프(calendar.read.all).
    private val calClientId = "forecast-svc"
    private val calClientSecret = "forecast-secret"

    // 일반 발급형 calendar.read(org 결속 없음) — 임의 org 조회가 거부돼야 한다(테넌시 격리).
    private val readOnlyClientId = "reporting-svc"
    private val readOnlyClientSecret = "reporting-secret"
    private val noScopeClientId = "payments-svc"
    private val noScopeClientSecret = "payments-secret"

    private fun icsFor(
        uid: String,
        summary: String,
    ) = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//taspa//test//EN
        BEGIN:VEVENT
        UID:$uid
        SUMMARY:$summary
        DTSTART:20260810T090000Z
        DTEND:20260810T100000Z
        CATEGORIES:EVENT
        END:VEVENT
        END:VCALENDAR
        """.trimIndent()

    private fun twoEventIcs() =
        """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//taspa//test//EN
        BEGIN:VEVENT
        UID:sweep-1
        SUMMARY:One
        DTSTART:20260810T090000Z
        END:VEVENT
        BEGIN:VEVENT
        UID:sweep-2
        SUMMARY:Two
        DTSTART:20260811T090000Z
        END:VEVENT
        END:VCALENDAR
        """.trimIndent()

    private fun oneEventIcs() =
        """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//taspa//test//EN
        BEGIN:VEVENT
        UID:sweep-1
        SUMMARY:One
        DTSTART:20260810T090000Z
        END:VEVENT
        END:VCALENDAR
        """.trimIndent()

    @BeforeEach
    fun setUp() {
        eventRepository.deleteAll()
        feedRepository.deleteAll()
        membershipRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()

        orgA = organizationRepository.save(Organization(slug = "org-a", name = "Org A")).id!!
        orgB = organizationRepository.save(Organization(slug = "org-b", name = "Org B")).id!!

        memberEmail = "member@example.com"
        val member =
            userRepository.save(
                User(email = memberEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
            )
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = "MEMBER"))

        // org A 에 2건, org B 에 1건 업서트(파싱 경로 검증).
        seed(orgA, "evt-a1", "A One")
        seed(orgA, "evt-a2", "A Two")
        seed(orgB, "evt-b1", "B One")

        registerM2mClient(calClientId, calClientSecret, "calendar.read.all")
        registerM2mClient(readOnlyClientId, readOnlyClientSecret, "calendar.read")
        registerM2mClient(noScopeClientId, noScopeClientSecret, "meal.pay")
    }

    private fun seed(
        orgId: UUID,
        uid: String,
        summary: String,
    ) {
        val feed = calendarService.createFeed(orgId, FeedCreateRequest(name = "upload", type = "EVENT"))
        calendarService.importUpload(orgId, feed.id, icsFor(uid, summary))
    }

    @Test
    fun `업로드한 이벤트가 저장되고 org 별로 격리된다`() {
        val uids =
            eventRepository
                .findWindow(orgA, instant("2026-08-01T00:00:00Z"), instant("2026-09-01T00:00:00Z"), PageRequest.of(0, 100))
                .content
                .map { it.uid }
        assertThat(uids).containsExactlyInAnyOrder("evt-a1", "evt-a2")
    }

    @Test
    fun `M2M calendar_read_all 토큰으로 org 이벤트를 조회한다`() {
        val token = clientCredentialsToken(calClientId, calClientSecret, "calendar.read.all")
        val body =
            mockMvc
                .perform(
                    get("/api/orgs/{orgId}/calendar/events", orgA)
                        .param("from", "2026-08-01T00:00:00Z")
                        .param("to", "2026-09-01T00:00:00Z")
                        .header("Authorization", "Bearer $token"),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val items = objectMapper.readTree(body).get("items")
        assertThat(items.map { it.get("uid").asText() })
            .containsExactlyInAnyOrder("evt-a1", "evt-a2")
        // org B 이벤트는 포함되지 않는다(org 스코프 조회).
        assertThat(items.map { it.get("uid").asText() }).doesNotContain("evt-b1")
    }

    @Test
    fun `org 결속 없는 calendar_read M2M 토큰은 임의 org 를 못 읽는다(테넌시 격리)`() {
        // authorization_code 사용자 클라이언트에도 부여될 수 있는 일반 발급형 calendar.read 로는
        // 자신이 결속되지 않은 org 를 읽을 수 없어야 한다(스펙 E '본인 org 만').
        val token = clientCredentialsToken(readOnlyClientId, readOnlyClientSecret, "calendar.read")
        mockMvc
            .perform(
                get("/api/orgs/{orgId}/calendar/events", orgA).header("Authorization", "Bearer $token"),
            ).andExpect(status().isForbidden)
        mockMvc
            .perform(
                get("/api/orgs/{orgId}/calendar/events", orgB).header("Authorization", "Bearer $token"),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `calendar_read scope 이 없는 M2M 토큰은 403 이다`() {
        val token = clientCredentialsToken(noScopeClientId, noScopeClientSecret, "meal.pay")
        mockMvc
            .perform(
                get("/api/orgs/{orgId}/calendar/events", orgA).header("Authorization", "Bearer $token"),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `SUSPENDED 조직의 캘린더는 전조회 토큰으로도 차단된다`() {
        organizationRepository.findById(orgA).get().let {
            it.status = "SUSPENDED"
            organizationRepository.save(it)
        }
        val token = clientCredentialsToken(calClientId, calClientSecret, "calendar.read.all")
        mockMvc
            .perform(
                get("/api/orgs/{orgId}/calendar/events", orgA).header("Authorization", "Bearer $token"),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `재업로드 시 소스에서 사라진 이벤트는 정리된다(고아 sweep)`() {
        val feed = calendarService.createFeed(orgA, FeedCreateRequest(name = "sweep", type = "EVENT"))
        // 최초 2건 업로드.
        calendarService.importUpload(orgA, feed.id, twoEventIcs())
        assertThat(eventRepository.findByFeedId(feed.id).map { it.uid })
            .containsExactlyInAnyOrder("sweep-1", "sweep-2")
        // 1건만 담은 .ics 로 재업로드 → 나머지 1건은 고아로 정리돼야 한다.
        calendarService.importUpload(orgA, feed.id, oneEventIcs())
        assertThat(eventRepository.findByFeedId(feed.id).map { it.uid })
            .containsExactly("sweep-1")
    }

    @Test
    fun `미인증 요청은 401 이다`() {
        mockMvc
            .perform(get("/api/orgs/{orgId}/calendar/events", orgA))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `세션 멤버는 본인 org 는 조회하고 타 org 는 403 이다(org 격리)`() {
        val session = login(memberEmail)
        session.perform(get("/api/orgs/{orgId}/calendar/events", orgA)).andExpect(status().isOk)
        // 멤버가 아닌 org B 는 거부된다.
        session.perform(get("/api/orgs/{orgId}/calendar/events", orgB)).andExpect(status().isForbidden)
    }

    @Test
    fun `구독 피드 등록 시 SSRF(사설IP·http·메타데이터)는 거부된다`() {
        assertThatThrownBy {
            calendarService.createFeed(orgA, FeedCreateRequest("sub", "HOLIDAY", "http://example.com/c.ics"))
        }.isInstanceOf(AuthException::class.java)
        assertThatThrownBy {
            calendarService.createFeed(orgA, FeedCreateRequest("sub", "HOLIDAY", "https://169.254.169.254/meta"))
        }.isInstanceOf(AuthException::class.java)
        assertThatThrownBy {
            calendarService.createFeed(orgA, FeedCreateRequest("sub", "HOLIDAY", "https://10.0.0.1/c.ics"))
        }.isInstanceOf(AuthException::class.java)
    }

    // ---- helpers ----

    private fun login(email: String): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", email).with(csrf()))
        session
            .perform(post("/login/password").param("username", email).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
        return session
    }

    private fun registerM2mClient(
        clientId: String,
        secret: String,
        scope: String,
    ) {
        registeredClientRepository.findByClientId(clientId)?.let { return }
        registeredClientRepository.save(
            RegisteredClient
                .withId("$clientId-internal")
                .clientId(clientId)
                .clientName(clientId)
                .clientSecret(passwordEncoder.encode(secret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(scope)
                .build(),
        )
    }

    private fun clientCredentialsToken(
        clientId: String,
        secret: String,
        scope: String,
    ): String {
        val basic = "Basic " + Base64.getEncoder().encodeToString("$clientId:$secret".toByteArray())
        val body =
            mockMvc
                .perform(
                    post("/oauth2/token")
                        .header("Authorization", basic)
                        .param("grant_type", "client_credentials")
                        .param("scope", scope),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body).get("access_token").asText()
    }

    private fun instant(iso: String) = java.time.Instant.parse(iso)
}
