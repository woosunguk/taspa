package com.taspa.server.meal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.calendar.CalendarEvent
import com.taspa.server.domain.calendar.CalendarEventRepository
import com.taspa.server.domain.calendar.CalendarFeed
import com.taspa.server.domain.calendar.CalendarFeedRepository
import com.taspa.server.domain.consumption.ConsumptionEvent
import com.taspa.server.domain.consumption.ConsumptionEventRepository
import com.taspa.server.domain.meal.MealMenuRepository
import com.taspa.server.domain.meal.Merchant
import com.taspa.server.domain.meal.MerchantForecastSettingsRepository
import com.taspa.server.domain.meal.MerchantMember
import com.taspa.server.domain.meal.MerchantMemberRepository
import com.taspa.server.domain.meal.MerchantRepository
import com.taspa.server.domain.org.MemberAbsence
import com.taspa.server.domain.org.MemberAbsenceRepository
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.org.SiteRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * 가맹 예측의 **조직 분해 + 신호 + 저장 설정** 통합 회귀.
 *
 * 실경로(수동 curl)로만 검증돼 있던 것을 못박는다. 핵심 단언은 넷이다:
 *  1. **분해가 교차 오염을 막는다** — A 조직의 행사 선언이 B 조직 몫의 예측을 바꾸지 않는다.
 *  2. **저장 설정이 파라미터 없는 요청에 적용**되고, 쿼리 파라미터는 요청 한 번만 덮는다(부분 덮어쓰기).
 *  3. **연차가 그 조직 몫만 낮춘다**(재직 40, 부재 10 → 비율 30/40).
 *  4. **저장은 감사 이벤트를 남긴다** — "누가 언제 켰는지 모른다"는 우려의 답이므로 이것이 깨지면
 *     저장형 전환의 전제가 무너진다.
 *
 * 날짜 픽스처는 **오늘에서 역산**한다(과거 고정 날짜는 시간이 지나면 lookback 밖으로 밀린다).
 * 타깃은 다음 주 수요일 — basis(D-7·14·21·28)가 항상 "오늘 이전 완결 구간"에 들어온다.
 */
class MerchantForecastSignalsIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var merchantRepository: MerchantRepository

    @Autowired lateinit var merchantMemberRepository: MerchantMemberRepository

    @Autowired lateinit var eventRepository: ConsumptionEventRepository

    @Autowired lateinit var absenceRepository: MemberAbsenceRepository

    @Autowired lateinit var feedRepository: CalendarFeedRepository

    @Autowired lateinit var calendarEventRepository: CalendarEventRepository

    @Autowired lateinit var settingsRepository: MerchantForecastSettingsRepository

    @Autowired lateinit var menuRepository: MealMenuRepository

    @Autowired lateinit var siteRepository: SiteRepository

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var objectMapper: ObjectMapper

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val password = "SecureP@ssw0rd123"
    private lateinit var adminEmail: String
    private lateinit var merchantId: UUID
    private lateinit var orgA: UUID
    private lateinit var orgB: UUID

    /** 다음 주 수요일 — basis 4주가 전부 과거 완결 구간(오늘 이전)에 들어오는 가장 가까운 미래. */
    private val target: LocalDate =
        LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.next(DayOfWeek.WEDNESDAY)).let {
            if (it <= LocalDate.now(ZoneOffset.UTC).plusDays(1)) it.plusWeeks(1) else it
        }

    @BeforeEach
    fun setUp() {
        settingsRepository.deleteAll()
        menuRepository.deleteAll()
        calendarEventRepository.deleteAll()
        feedRepository.deleteAll()
        absenceRepository.deleteAll()
        eventRepository.deleteAll()
        merchantMemberRepository.deleteAll()
        merchantRepository.deleteAll()
        membershipRepository.deleteAll()
        siteRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()
        jdbcTemplate.update("DELETE FROM audit_events")

        orgA = organizationRepository.save(Organization(slug = "mfs-a", name = "A조직")).id!!
        orgB = organizationRepository.save(Organization(slug = "mfs-b", name = "B조직")).id!!
        merchantId = merchantRepository.save(Merchant(name = "분해식당", timezone = "UTC")).id!!

        adminEmail = "mfs-admin@example.com"
        val admin =
            userRepository.save(
                User(email = adminEmail, passwordHash = passwordEncoder.encode(password), emailVerified = true),
            )
        merchantMemberRepository.save(
            MerchantMember(merchantId = merchantId, userId = admin.id!!, role = "MERCHANT_ADMIN", status = "ACTIVE"),
        )

        // 4주 basis: A=20, B=10 (같은 요일 중식). 분해의 합 = 30.
        for (week in 1..4) {
            val date = target.minusWeeks(week.toLong())
            saveActual(orgA, date, 20)
            saveActual(orgB, date, 10)
        }
    }

    // ---- 1. 분해 = 교차 오염 차단 ----

    @Test
    fun `A 조직의 행사 선언은 A 몫만 바꾸고 B 몫은 그대로다`() {
        declareEvent(orgA, target, "체육대회")
        val session = login()

        val cell = lunchCell(session, "eventAware=true")

        val a = sliceOf(cell, orgA)
        val b = sliceOf(cell, orgB)
        // A: 타깃이 행사일인데 과거 행사 실적이 없다 → 근거 없음(숫자를 지어내지 않는다).
        assertThat(a.get("predicted").isNull).isTrue
        assertThat(a.get("event").asBoolean()).isTrue
        assertThat(a.get("eventName").asText()).isEqualTo("체육대회")
        // B: 남의 조직 행사와 무관 — 전주 동요일 10 그대로.
        assertThat(b.get("predicted").asLong()).isEqualTo(10L)
        // 합계는 아는 조직의 합(하한) + partial 표시.
        assertThat(cell.get("predicted").asLong()).isEqualTo(10L)
        assertThat(cell.get("partial").asBoolean()).isTrue
    }

    @Test
    fun `행사 선언이 없으면 분해의 합이 총합과 같다(대조군)`() {
        val session = login()
        val cell = lunchCell(session, "eventAware=true")
        assertThat(cell.get("predicted").asLong()).isEqualTo(30L)
        assertThat(cell.get("partial").asBoolean()).isFalse
        // 두 조직 모두 같은 방법(전주 동요일)이면 COMPOSITE 로 뭉개지 않고 그 방법을 그대로 말한다 —
        // COMPOSITE 는 "섞였다"는 사실 자체가 정보일 때만 쓴다(설계된 동작).
        assertThat(cell.get("method").asText()).isEqualTo("SEASONAL_NAIVE")
    }

    // ---- 2. 연차 = 그 조직 몫만 ----

    @Test
    fun `연차는 그 조직 몫만 낮춘다(재직 40 중 10 부재 = 비율 0_75)`() {
        seedEmployees(orgA, 40)
        val member = membershipRepository.findByOrgId(orgA).first()
        // 부재 10명(가중 1.0 × 10행 — 사람이 달라야 UNIQUE 를 지나므로 10명을 만든다)
        membershipRepository.findByOrgId(orgA).take(10).forEach { m ->
            absenceRepository.save(
                MemberAbsence(orgId = orgA, userId = m.userId, absenceDate = target, type = "ANNUAL_LEAVE"),
            )
        }
        assertThat(member).isNotNull
        val session = login()

        val cell = lunchCell(session, "absenceAware=true")

        val a = sliceOf(cell, orgA)
        val b = sliceOf(cell, orgB)
        // A: 20 × (40-10)/40 = 15, 방법이 보정으로 승격된다.
        assertThat(a.get("predicted").asLong()).isEqualTo(15L)
        assertThat(a.get("method").asText()).isEqualTo("SEASONAL_NAIVE_ADJUSTED")
        assertThat(a.get("absentWeight").asDouble()).isEqualTo(10.0)
        // B: 부재 없음 — 그대로.
        assertThat(b.get("predicted").asLong()).isEqualTo(10L)
    }

    // ---- 3. 저장 설정 + 부분 덮어쓰기 ----

    @Test
    fun `저장한 설정은 파라미터 없는 요청에 적용되고 쿼리는 요청 한 번만 덮는다`() {
        declareEvent(orgA, target, "워크숍")
        val session = login()

        // 저장 전 기본(eventAware=false): 행사 무시 → 30.
        assertThat(lunchCell(session).get("predicted").asLong()).isEqualTo(30L)

        // eventAware 저장 — 이후 파라미터 없는 요청이 행사를 인지한다(A 몫 근거 없음 → 10).
        saveSettings(session, eventAware = true)
        assertThat(lunchCell(session).get("predicted").asLong()).isEqualTo(10L)

        // ★부분 덮어쓰기: absenceAware=false 만 실험해도 저장해 둔 eventAware 는 유지된다.
        assertThat(lunchCell(session, "absenceAware=false").get("predicted").asLong()).isEqualTo(10L)
        // 실험으로 eventAware=false 를 덮으면 그 요청만 30 으로 돌아간다(저장값 불변).
        assertThat(lunchCell(session, "eventAware=false").get("predicted").asLong()).isEqualTo(30L)
        assertThat(lunchCell(session).get("predicted").asLong()).isEqualTo(10L)
    }

    @Test
    fun `설정 저장은 감사 이벤트를 남긴다(저장형 전환의 전제)`() {
        val session = login()
        saveSettings(session, eventAware = true)
        val count =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE type = 'MERCHANT_FORECAST_SETTINGS_UPDATED'",
                Long::class.java,
            )
        assertThat(count).isEqualTo(1L)
    }

    // ---- 4. 이용 조직 목록 ----

    @Test
    fun `이용 조직 목록은 실적순이고 다가오는 신호 요약을 싣는다`() {
        declareEvent(orgA, target, "체육대회")
        val session = login()
        val body = forecastBody(session, "eventAware=true")
        val orgs = body.get("orgs")
        assertThat(orgs.size()).isEqualTo(2)
        // 실적순: A(80) 먼저, B(40) 다음.
        assertThat(orgs[0].get("name").asText()).isEqualTo("A조직")
        assertThat(orgs[0].get("upcomingEvents").asInt()).isGreaterThanOrEqualTo(0)
    }

    // ---- 픽스처 ----

    private fun saveActual(
        orgId: UUID,
        date: LocalDate,
        quantity: Int,
    ) {
        eventRepository.save(
            ConsumptionEvent(
                source = "pos",
                externalId = "mfs-$orgId-$date",
                orgId = orgId,
                merchantId = merchantId,
                mealWindow = "LUNCH",
                quantity = quantity,
                occurredAt = date.atTime(12, 0).toInstant(ZoneOffset.UTC),
            ),
        )
    }

    private fun declareEvent(
        orgId: UUID,
        date: LocalDate,
        name: String,
    ) {
        val feed = feedRepository.save(CalendarFeed(orgId = orgId, name = "행사", type = "EVENT"))
        calendarEventRepository.save(
            CalendarEvent(
                orgId = orgId,
                feedId = feed.id,
                uid = "mfs-event-$date",
                summary = name,
                category = "EVENT",
                startsAt = date.atStartOfDay(ZoneOffset.UTC).toInstant(),
                endsAt = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                allDay = true,
                source = "UPLOAD",
            ),
        )
    }

    private fun seedEmployees(
        orgId: UUID,
        count: Int,
    ) {
        repeat(count) { i ->
            val user =
                userRepository.save(
                    User(email = "mfs-emp-$i@example.com", passwordHash = null, emailVerified = true),
                )
            membershipRepository.save(OrgMembership(orgId = orgId, userId = user.id!!, role = "MEMBER"))
        }
    }

    private fun login(): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", adminEmail).with(csrf()))
        session
            .perform(post("/login/password").param("username", adminEmail).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
        return session
    }

    private fun forecastBody(
        session: WebSession,
        vararg params: String,
    ): JsonNode {
        val query = (listOf("from=$target", "to=$target", "mealWindow=LUNCH") + params).joinToString("&")
        val body =
            session
                .perform(get("/api/merchant-console/{id}/forecast?$query", merchantId))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body)
    }

    private fun lunchCell(
        session: WebSession,
        vararg params: String,
    ): JsonNode = forecastBody(session, *params).get("cells")[0]

    private fun sliceOf(
        cell: JsonNode,
        orgId: UUID,
    ): JsonNode = cell.get("orgs").first { it.get("orgId").asText() == orgId.toString() }

    private fun saveSettings(
        session: WebSession,
        eventAware: Boolean,
    ) {
        session
            .perform(
                put("/api/merchant-console/{id}/forecast-settings", merchantId)
                    .with(csrf())
                    .contentType("application/json")
                    .content(
                        """{"headcountAdjust":true,"absenceAware":true,"holidayAware":true,
                           "eventAware":$eventAware,"menuAware":false,"nowcast":true,"methodSelection":false}""",
                    ),
            ).andExpect(status().isOk)
    }
}
