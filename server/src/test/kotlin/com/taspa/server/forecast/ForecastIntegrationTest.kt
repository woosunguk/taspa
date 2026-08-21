package com.taspa.server.forecast

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.ninjasquad.springmockk.MockkBean
import com.taspa.server.domain.calendar.CalendarEvent
import com.taspa.server.domain.calendar.CalendarEventRepository
import com.taspa.server.domain.calendar.CalendarFeed
import com.taspa.server.domain.calendar.CalendarFeedRepository
import com.taspa.server.domain.consumption.ConsumptionEvent
import com.taspa.server.domain.consumption.ConsumptionEventRepository
import com.taspa.server.domain.org.MembershipHistory
import com.taspa.server.domain.org.MembershipHistoryRepository
import com.taspa.server.domain.org.OrgMembership
import com.taspa.server.domain.org.OrgMembershipRepository
import com.taspa.server.domain.org.Organization
import com.taspa.server.domain.org.OrganizationRepository
import com.taspa.server.domain.org.Site
import com.taspa.server.domain.org.SiteRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.support.IntegrationTestBase
import com.taspa.server.support.WebSession
import com.taspa.server.token.JwkStorageService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 식수예측 P0 베이스라인 통합 테스트 — 산술 정확성·폴백 체인·site 축·org 격리·타임존 앵커·창 상한·
 * 이중 인가(세션 ORG_ADMIN∨플랫폼 ADMIN / M2M meal.forecast.read+org 결속)·백테스트 지표.
 *
 * 재실 모수 시드: orgA 활성 멤버 7명(현재), 그중 6명은 이력이 30일 전 스냅샷·1명은 3일 전 스냅샷 —
 * 전주 시점(타깃-7일) 복원값은 6 이라 headcountRatio = 7/6 이 된다.
 * 날짜는 org 타임존(Asia/Seoul) 기준 "어제"를 타깃으로 상대 계산한다(백테스트 과거 요건과 공용).
 */
class ForecastIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var organizationRepository: OrganizationRepository

    @Autowired lateinit var membershipRepository: OrgMembershipRepository

    @Autowired lateinit var historyRepository: MembershipHistoryRepository

    @Autowired lateinit var siteRepository: SiteRepository

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var eventRepository: ConsumptionEventRepository

    @Autowired lateinit var calendarFeedRepository: CalendarFeedRepository

    @Autowired lateinit var calendarEventRepository: CalendarEventRepository

    @Autowired lateinit var passwordEncoder: PasswordEncoder

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var jwkSource: JWKSource<SecurityContext>

    @Autowired lateinit var jwkStorageService: JwkStorageService

    @Value("\${taspa.issuer-uri}")
    lateinit var issuerUri: String

    @MockkBean(relaxed = true)
    lateinit var mailSender: JavaMailSender

    private val password = "SecureP@ssw0rd123"
    private val zone = ZoneId.of("Asia/Seoul")

    private lateinit var orgA: UUID
    private lateinit var orgB: UUID
    private lateinit var site1: UUID
    private lateinit var orgAdminEmail: String
    private lateinit var plainMemberEmail: String

    /** 타깃일 = org-로컬(KST) 어제 — 예측·백테스트 공용(백테스트는 어제까지만 허용). */
    private lateinit var target: LocalDate
    private lateinit var lastWeek: LocalDate

    @BeforeEach
    fun setUp() {
        eventRepository.deleteAll()
        calendarEventRepository.deleteAll()
        calendarFeedRepository.deleteAll()
        historyRepository.deleteAll()
        membershipRepository.deleteAll()
        siteRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()

        target = LocalDate.now(zone).minusDays(1)
        lastWeek = target.minusDays(7)

        orgA = organizationRepository.save(Organization(slug = "fc-a", name = "FC A", timezone = "Asia/Seoul")).id!!
        orgB = organizationRepository.save(Organization(slug = "fc-b", name = "FC B", timezone = "Asia/Seoul")).id!!
        site1 = siteRepository.save(Site(orgId = orgA, name = "본사식당", timezone = "Asia/Seoul")).id!!

        orgAdminEmail = "fc-admin@example.com"
        plainMemberEmail = "fc-member@example.com"
        val admin = saveUser(orgAdminEmail)
        val member = saveUser(plainMemberEmail)
        val fillers = (1..5).map { saveUser("fc-filler-$it@example.com") }

        // 현재 활성·재직 멤버십 7명(admin+member+filler 5).
        membershipRepository.save(OrgMembership(orgId = orgA, userId = admin.id!!, role = "ORG_ADMIN"))
        membershipRepository.save(OrgMembership(orgId = orgA, userId = member.id!!, role = "MEMBER"))
        fillers.forEach { membershipRepository.save(OrgMembership(orgId = orgA, userId = it.id!!, role = "MEMBER")) }

        // 이력(SCD): 6명은 30일 전 JOINED 스냅샷(전주 시점 복원에 포함), 1명(filler5)은 3일 전 —
        // 전주 시점(타깃-7일의 그 날 끝)엔 아직 없어 headcountLastWeek=6, 현재는 7 → 비율 7/6.
        val oldRecorded = Instant.now().minus(30, ChronoUnit.DAYS)
        val recentRecorded =
            target
                .minusDays(2)
                .atTime(12, 0)
                .atZone(zone)
                .toInstant()
        (listOf(admin, member) + fillers.take(4)).forEach {
            historyRepository.save(joinedSnapshot(orgA, it.id!!, oldRecorded))
        }
        historyRepository.save(joinedSnapshot(orgA, fillers[4].id!!, recentRecorded))
    }

    // ---- 예측 산술·site 축 ----

    @Test
    fun `전주 동요일 실적에 재실 비율을 곱해 예측한다(42 × 7÷6 = 49)`() {
        seedEvents(orgA, lastWeek, "LUNCH", site1, 20, 20, 2) // 전주 동요일 합 42
        val session = login(orgAdminEmail)
        val json =
            perform(session, forecastGet(orgA).param("mealWindow", "LUNCH"))
                .andExpect(status().isOk)
                .json()

        val orgCell = cell(json, siteId = null, window = "LUNCH")
        assertThat(orgCell.get("predicted").asLong()).isEqualTo(49)
        assertThat(orgCell.get("method").asText()).isEqualTo("SEASONAL_NAIVE_ADJUSTED")
        assertThat(orgCell.get("basis").get("lastWeekActual").asLong()).isEqualTo(42)
        assertThat(orgCell.get("basis").get("headcountNow").asLong()).isEqualTo(7)
        assertThat(orgCell.get("basis").get("headcountLastWeek").asLong()).isEqualTo(6)

        // site 분해 목록 — 실적이 관측된 site1 셀도 함께 반환된다.
        val siteCell = cell(json, siteId = site1, window = "LUNCH")
        assertThat(siteCell.get("predicted").asLong()).isEqualTo(49)
    }

    @Test
    fun `siteId 필터는 그 site 실적만 쓰고, 미지정 org 전체는 site 미지정 이벤트까지 합산한다`() {
        seedEvents(orgA, lastWeek, "LUNCH", site1, 20, 20, 2) // site1 = 42
        seedEvents(orgA, lastWeek, "LUNCH", null, 8) // site 미지정 = 8 → org 전체 50
        val session = login(orgAdminEmail)

        val filtered =
            perform(session, forecastGet(orgA).param("mealWindow", "LUNCH").param("siteId", site1.toString()))
                .andExpect(status().isOk)
                .json()
        assertThat(filtered.get("cells")).hasSize(1) // 필터 시 그 site 축만
        assertThat(
            filtered
                .get("cells")
                .get(0)
                .get("predicted")
                .asLong(),
        ).isEqualTo(49) // 42×7/6

        val total = perform(session, forecastGet(orgA).param("mealWindow", "LUNCH")).andExpect(status().isOk).json()
        assertThat(cell(total, null, "LUNCH").get("predicted").asLong()).isEqualTo(58) // round(50×7/6)
    }

    @Test
    fun `타 org 의 site 는 404 다(교차 org site 탐침 차단)`() {
        val foreignSite = siteRepository.save(Site(orgId = orgB, name = "B식당", timezone = "Asia/Seoul")).id!!
        val session = login(orgAdminEmail)
        perform(session, forecastGet(orgA).param("siteId", foreignSite.toString()))
            .andExpect(status().isNotFound)
    }

    // ---- 폴백 체인 ----

    @Test
    fun `전주가 없으면 4주 같은 요일 평균, 그것도 없으면 NO_DATA(predicted=null)다`() {
        // LUNCH: 전주 없음, 2주 전 30·3주 전 40 → FOUR_WEEK_AVG 35. DINNER: 아무것도 없음 → NO_DATA.
        seedEvents(orgA, target.minusDays(14), "LUNCH", null, 30)
        seedEvents(orgA, target.minusDays(21), "LUNCH", null, 40)
        val session = login(orgAdminEmail)
        val json = perform(session, forecastGet(orgA)).andExpect(status().isOk).json()

        val lunch = cell(json, null, "LUNCH")
        assertThat(lunch.get("predicted").asLong()).isEqualTo(35)
        assertThat(lunch.get("method").asText()).isEqualTo("FOUR_WEEK_AVG")

        val dinner = cell(json, null, "DINNER")
        assertThat(dinner.get("predicted").isNull).isTrue()
        assertThat(dinner.get("method").asText()).isEqualTo("NO_DATA")
    }

    @Test
    fun `이력이 없는 조직은 비율 보정 없이 전주 실적 그대로다(SEASONAL_NAIVE)`() {
        // orgB — 멤버십은 있으나 SCD 이력이 없어 전주 시점 복원 불가 → ratio 1.0, method 라벨로 구분.
        val someone = saveUser("fc-b-user@example.com")
        membershipRepository.save(OrgMembership(orgId = orgB, userId = someone.id!!, role = "MEMBER"))
        seedEvents(orgB, lastWeek, "LUNCH", null, 20)

        val token = signedToken("meal.forecast.read", orgB)
        val json =
            performBearer(forecastGet(orgB).param("mealWindow", "LUNCH"), token)
                .andExpect(status().isOk)
                .json()
        val node = cell(json, null, "LUNCH")
        assertThat(node.get("predicted").asLong()).isEqualTo(20)
        assertThat(node.get("method").asText()).isEqualTo("SEASONAL_NAIVE")
        assertThat(node.get("basis").get("headcountLastWeek").isNull).isTrue()
    }

    // ---- org 격리 · 타임존 ----

    @Test
    fun `타 org 소비는 혼입되지 않는다(org 격리)`() {
        seedEvents(orgA, lastWeek, "LUNCH", null, 42)
        seedEvents(orgB, lastWeek, "LUNCH", null, 99) // 같은 날·같은 끼니의 타 org 실적
        val session = login(orgAdminEmail)
        val json = perform(session, forecastGet(orgA).param("mealWindow", "LUNCH")).andExpect(status().isOk).json()
        assertThat(cell(json, null, "LUNCH").get("basis").get("lastWeekActual").asLong()).isEqualTo(42)
    }

    @Test
    fun `KST 아침 이벤트는 UTC 로 요일이 밀려도 org-로컬 날짜로 앵커링된다`() {
        // KST 08:00 = 전날 23:00Z — UTC 절단이면 전날로 새어 전주 동요일 매칭이 깨진다(NO_DATA 가 된다).
        val kstMorning = lastWeek.atTime(8, 0).atZone(zone).toInstant()
        eventRepository.save(event(orgA, "tz-1", "BREAKFAST", null, 10, kstMorning))
        val session = login(orgAdminEmail)
        val json =
            perform(session, forecastGet(orgA).param("mealWindow", "BREAKFAST"))
                .andExpect(status().isOk)
                .json()
        val node = cell(json, null, "BREAKFAST")
        assertThat(node.get("method").asText()).isEqualTo("SEASONAL_NAIVE_ADJUSTED")
        assertThat(node.get("basis").get("lastWeekActual").asLong()).isEqualTo(10)
        assertThat(node.get("predicted").asLong()).isEqualTo(12) // round(10×7/6)
    }

    // ---- 창 상한 ----

    @Test
    fun `예측 조회 창 31일 초과는 400 이다`() {
        val session = login(orgAdminEmail)
        session
            .perform(
                get("/api/orgs/{orgId}/forecast", orgA)
                    .param("from", target.toString())
                    .param("to", target.plusDays(31).toString()), // 32일 창
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `백테스트 창 92일 초과는 400 이다`() {
        val session = login(orgAdminEmail)
        session
            .perform(
                get("/api/orgs/{orgId}/forecast/backtest", orgA)
                    .param("from", target.minusDays(92).toString())
                    .param("to", target.toString()), // 93일 창
            ).andExpect(status().isBadRequest)
    }

    // ---- 인가 ----

    @Test
    fun `비관리 멤버 세션은 403 이다`() {
        val session = login(plainMemberEmail)
        session.perform(forecastGet(orgA)).andExpect(status().isForbidden)
    }

    @Test
    fun `미인증 조회는 401 이다`() {
        mockMvc.perform(forecastGet(orgA)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `org 결속 forecast read 토큰은 본인 org 만 조회하고 타 org·비결속·타 scope 는 403 이다`() {
        performBearer(forecastGet(orgA), signedToken("meal.forecast.read", orgA)).andExpect(status().isOk)
        performBearer(forecastGet(orgA), signedToken("meal.forecast.read", orgB)).andExpect(status().isForbidden)
        performBearer(forecastGet(orgA), signedToken("meal.forecast.read", null)).andExpect(status().isForbidden)
        performBearer(forecastGet(orgA), signedToken("meal.consumption.read", orgA)).andExpect(status().isForbidden)
    }

    @Test
    fun `사용자 대면 토큰은 forecast scope 가 있어도 거부된다(M2M 전용)`() {
        val user = userRepository.findByEmail(orgAdminEmail)!!
        val token = signedToken("meal.forecast.read", orgA, subject = user.id.toString())
        performBearer(forecastGet(orgA), token).andExpect(status().isForbidden)
    }

    // ---- 백테스트 ----

    @Test
    fun `백테스트는 그 시점 예측 vs 실적으로 MAPE·WAPE·bias 를 계산한다`() {
        // 전주 42 → 그 시점 예측 = 42×(타깃 전일(D-1) 끝 인원 7 ÷ 전주 인원 6) = 49. 실적 56.
        // (filler5 스냅샷은 타깃-2일 12:00 recorded — D-1 끝 기준 복원에 포함된다.)
        seedEvents(orgA, lastWeek, "LUNCH", null, 42)
        seedEvents(orgA, target, "LUNCH", null, 56)
        val session = login(orgAdminEmail)
        val json =
            perform(
                session,
                get("/api/orgs/{orgId}/forecast/backtest", orgA)
                    .param("from", target.toString())
                    .param("to", target.toString())
                    .param("mealWindow", "LUNCH"),
            ).andExpect(status().isOk).json()

        assertThat(json.get("cells")).hasSize(1)
        val cell = json.get("cells").get(0)
        assertThat(cell.get("predicted").asLong()).isEqualTo(49)
        assertThat(cell.get("actual").asLong()).isEqualTo(56)
        assertThat(cell.get("method").asText()).isEqualTo("SEASONAL_NAIVE_ADJUSTED")

        val summary = json.get("summary")
        assertThat(summary.get("scoredCells").asInt()).isEqualTo(1)
        assertThat(summary.get("mape").asDouble()).isCloseTo(7.0 / 56, within(1e-9))
        assertThat(summary.get("wape").asDouble()).isCloseTo(7.0 / 56, within(1e-9))
        assertThat(summary.get("bias").asDouble()).isCloseTo(-7.0 / 56, within(1e-9))
        assertThat(summary.get("mapeExcludedZeroActual").asInt()).isZero()
    }

    @Test
    fun `백테스트에서 실적 0 셀은 MAPE 분모에서 제외되고 제외 수로 노출된다`() {
        // 전주 LUNCH 42 → 예측 49, 타깃일 실적 없음(=0). MAPE 는 분모 0 이라 제외, WAPE/bias 는 산출 불가(null).
        seedEvents(orgA, lastWeek, "LUNCH", null, 42)
        val session = login(orgAdminEmail)
        val json =
            perform(
                session,
                get("/api/orgs/{orgId}/forecast/backtest", orgA)
                    .param("from", target.toString())
                    .param("to", target.toString())
                    .param("mealWindow", "LUNCH"),
            ).andExpect(status().isOk).json()

        val summary = json.get("summary")
        assertThat(summary.get("scoredCells").asInt()).isEqualTo(1)
        assertThat(summary.get("mapeExcludedZeroActual").asInt()).isEqualTo(1)
        assertThat(summary.get("mape").isNull).isTrue()
    }

    // ---- 캘린더 휴일 인지 ----

    @Test
    fun `캘린더 이벤트가 없으면 휴일 플래그도 basis 필터도 작동하지 않는다(회귀 방지의 핵심)`() {
        seedEvents(orgA, lastWeek, "LUNCH", null, 42)
        val session = login(orgAdminEmail)
        val node =
            cell(
                perform(session, forecastGet(orgA).param("mealWindow", "LUNCH")).andExpect(status().isOk).json(),
                null,
                "LUNCH",
            )
        // 캘린더 도입 전과 완전히 동일한 산출 — 방법·값·근거 모두.
        assertThat(node.get("predicted").asLong()).isEqualTo(49)
        assertThat(node.get("method").asText()).isEqualTo("SEASONAL_NAIVE_ADJUSTED")
        assertThat(node.get("holiday").asBoolean()).isFalse()
        assertThat(node.get("holidayName").isNull).isTrue()
        assertThat(node.get("basis").get("excludedHolidayBasis").asInt()).isZero()
    }

    @Test
    fun `타깃일이 휴일이면 셀이 그 사실을 드러내되 예측값을 0 으로 단정하지 않는다`() {
        val feed = seedHolidayFeed(orgA)
        seedAllDay(orgA, feed, target, "창립기념일")
        seedAllDay(orgA, feed, lastWeek, "개천절") // 전주도 휴일 → 휴일끼리 비교 가능
        seedEvents(orgA, lastWeek, "LUNCH", null, 42) // 휴일에도 당직 식사가 있었다
        val session = login(orgAdminEmail)
        val node =
            cell(
                perform(session, forecastGet(orgA).param("mealWindow", "LUNCH")).andExpect(status().isOk).json(),
                null,
                "LUNCH",
            )
        assertThat(node.get("holiday").asBoolean()).isTrue()
        assertThat(node.get("holidayName").asText()).isEqualTo("창립기념일")
        // 휴일이라는 사실과 예측값은 별개 — 휴일 basis 가 있으므로 평소와 같은 산술로 예측한다.
        assertThat(node.get("predicted").asLong()).isEqualTo(49)
        assertThat(node.get("method").asText()).isEqualTo("SEASONAL_NAIVE_ADJUSTED")
        assertThat(node.get("basis").get("excludedHolidayBasis").asInt()).isZero()
    }

    @Test
    fun `전주 동요일이 휴일이었으면 그 실적을 basis 로 쓰지 않고 다음 후보로 강등한다`() {
        val feed = seedHolidayFeed(orgA)
        seedAllDay(orgA, feed, lastWeek, "설날") // 타깃은 평일, 전주만 휴일
        seedEvents(orgA, lastWeek, "LUNCH", null, 42) // 휴일 실적(급감) — 평일 예측에 쓰면 크게 과소예측된다
        seedEvents(orgA, target.minusDays(14), "LUNCH", null, 30)
        val session = login(orgAdminEmail)
        val node =
            cell(
                perform(session, forecastGet(orgA).param("mealWindow", "LUNCH")).andExpect(status().isOk).json(),
                null,
                "LUNCH",
            )
        assertThat(node.get("method").asText()).isEqualTo("FOUR_WEEK_AVG")
        assertThat(node.get("predicted").asLong()).isEqualTo(30) // 42(휴일)를 배제하고 D-14 만 사용
        assertThat(node.get("basis").get("lastWeekActual").isNull).isTrue()
        assertThat(node.get("basis").get("excludedHolidayBasis").asInt()).isEqualTo(1)
        assertThat(node.get("holiday").asBoolean()).isFalse()
    }

    @Test
    fun `휴일 타깃에 맞는 휴일 실적이 없으면 평일 실적으로 대체하지 않고 NO_DATA 다`() {
        val feed = seedHolidayFeed(orgA)
        seedAllDay(orgA, feed, target, "추석")
        seedEvents(orgA, lastWeek, "LUNCH", null, 42) // 평일 실적뿐 — 휴일 예측 근거가 못 된다
        val session = login(orgAdminEmail)
        val node =
            cell(
                perform(session, forecastGet(orgA).param("mealWindow", "LUNCH")).andExpect(status().isOk).json(),
                null,
                "LUNCH",
            )
        assertThat(node.get("predicted").isNull).isTrue()
        assertThat(node.get("method").asText()).isEqualTo("NO_DATA")
        assertThat(node.get("holiday").asBoolean()).isTrue()
        assertThat(node.get("basis").get("excludedHolidayBasis").asInt()).isEqualTo(1)
    }

    @Test
    fun `휴일 판정은 피드 유형 또는 category 로만 하고 요약 텍스트로 추측하지 않는다`() {
        val eventFeed = seedHolidayFeed(orgA, type = "EVENT")
        seedAllDay(orgA, eventFeed, target, "전사 워크숍") // all-day 지만 EVENT 피드 → 휴일 아님
        seedEvents(orgA, lastWeek, "LUNCH", null, 42)
        val session = login(orgAdminEmail)

        val plain =
            cell(
                perform(session, forecastGet(orgA).param("mealWindow", "LUNCH")).andExpect(status().isOk).json(),
                null,
                "LUNCH",
            )
        assertThat(plain.get("holiday").asBoolean()).isFalse()
        assertThat(plain.get("predicted").asLong()).isEqualTo(49)

        // 같은 EVENT 피드라도 CATEGORIES 가 HOLIDAY 로 명시되면 휴일이다(대소문자 무시).
        seedAllDay(orgA, eventFeed, target, "임시공휴일", category = "holiday")
        val tagged =
            cell(
                perform(session, forecastGet(orgA).param("mealWindow", "LUNCH")).andExpect(status().isOk).json(),
                null,
                "LUNCH",
            )
        assertThat(tagged.get("holiday").asBoolean()).isTrue()
    }

    @Test
    fun `연휴는 DTEND 배타 규칙대로 시작일부터 종료 전날까지만 덮는다`() {
        val feed = seedHolidayFeed(orgA)
        seedAllDay(orgA, feed, target.minusDays(2), "설 연휴", days = 3) // target-2·target-1·target
        val session = login(orgAdminEmail)
        val json =
            perform(
                session,
                forecastRange(orgA, target.minusDays(1), target.plusDays(1)).param("mealWindow", "LUNCH"),
            ).andExpect(status().isOk).json()

        assertThat(cellOn(json, target.minusDays(1), "LUNCH").get("holiday").asBoolean()).isTrue()
        assertThat(cellOn(json, target, "LUNCH").get("holiday").asBoolean()).isTrue()
        assertThat(cellOn(json, target.plusDays(1), "LUNCH").get("holiday").asBoolean()).isFalse()
    }

    @Test
    fun `백테스트 셀도 휴일을 드러내고 휴일 basis 를 거른다`() {
        val feed = seedHolidayFeed(orgA)
        seedAllDay(orgA, feed, lastWeek, "석가탄신일")
        seedEvents(orgA, lastWeek, "LUNCH", null, 42) // 휴일 실적 — basis 에서 제외되어야 한다
        seedEvents(orgA, target, "LUNCH", null, 56) // 채점용 실적
        val session = login(orgAdminEmail)
        val json =
            perform(
                session,
                get("/api/orgs/{orgId}/forecast/backtest", orgA)
                    .param("from", target.toString())
                    .param("to", target.toString())
                    .param("mealWindow", "LUNCH"),
            ).andExpect(status().isOk).json()

        val node = json.get("cells").get(0)
        assertThat(node.get("holiday").asBoolean()).isFalse() // 타깃(어제)은 평일
        assertThat(node.get("method").asText()).isEqualTo("NO_DATA") // 유일한 후보가 휴일이라 제외
        assertThat(node.get("basis").get("excludedHolidayBasis").asInt()).isEqualTo(1)
        assertThat(node.get("actual").asLong()).isEqualTo(56)
        assertThat(json.get("summary").get("scoredCells").asInt()).isZero() // NO_DATA 는 채점 제외
    }

    @Test
    fun `타 org 의 휴일 캘린더는 이 org 예측에 영향을 주지 않는다(org 격리)`() {
        val foreignFeed = seedHolidayFeed(orgB)
        seedAllDay(orgB, foreignFeed, lastWeek, "B사 창립기념일")
        seedEvents(orgA, lastWeek, "LUNCH", null, 42)
        val session = login(orgAdminEmail)
        val node =
            cell(
                perform(session, forecastGet(orgA).param("mealWindow", "LUNCH")).andExpect(status().isOk).json(),
                null,
                "LUNCH",
            )
        assertThat(node.get("predicted").asLong()).isEqualTo(49)
        assertThat(node.get("basis").get("excludedHolidayBasis").asInt()).isZero()
    }

    // ---- helpers ----

    private fun seedHolidayFeed(
        orgId: UUID,
        type: String = "HOLIDAY",
    ): UUID =
        calendarFeedRepository
            .save(CalendarFeed(orgId = orgId, name = "cal-$type-${UUID.randomUUID()}", type = type))
            .id!!

    /**
     * all-day 이벤트 시드. all-day 는 달력 날짜 자체가 값이라 파서와 동일하게 **UTC 자정**으로 저장하고,
     * DTEND 는 RFC 5545 대로 **배타적**이다(days=1 이면 그 하루).
     */
    private fun seedAllDay(
        orgId: UUID,
        feedId: UUID,
        date: LocalDate,
        summary: String,
        days: Long = 1,
        category: String? = null,
    ) {
        calendarEventRepository.save(
            CalendarEvent(
                orgId = orgId,
                feedId = feedId,
                uid = "hol-${UUID.randomUUID()}",
                summary = summary,
                category = category,
                startsAt = date.atStartOfDay(ZoneOffset.UTC).toInstant(),
                endsAt = date.plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant(),
                allDay = true,
                source = "UPLOAD",
            ),
        )
    }

    private fun forecastRange(
        orgId: UUID,
        from: LocalDate,
        to: LocalDate,
    ) = get("/api/orgs/{orgId}/forecast", orgId)
        .param("from", from.toString())
        .param("to", to.toString())

    /** org 전체(총식수) 축에서 특정 날짜 셀을 찾는다. */
    private fun cellOn(
        json: JsonNode,
        date: LocalDate,
        window: String,
    ): JsonNode =
        json.get("cells").first {
            it.get("date").asText() == date.toString() &&
                it.get("mealWindow").asText() == window &&
                it.get("siteId").isNull
        }

    private fun forecastGet(orgId: UUID) =
        get("/api/orgs/{orgId}/forecast", orgId)
            .param("from", target.toString())
            .param("to", target.toString())

    private fun perform(
        session: WebSession,
        request: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder,
    ): ResultActions = session.perform(request)

    private fun performBearer(
        request: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder,
        token: String,
    ): ResultActions = mockMvc.perform(request.header("Authorization", "Bearer $token"))

    private fun ResultActions.json(): JsonNode = objectMapper.readTree(andReturn().response.contentAsString)

    /** cells 에서 (siteId, mealWindow) 셀을 찾는다 — 응답 순서에 의존하지 않는다. */
    private fun cell(
        json: JsonNode,
        siteId: UUID?,
        window: String,
    ): JsonNode =
        json.get("cells").first {
            it.get("mealWindow").asText() == window &&
                (if (siteId == null) it.get("siteId").isNull else it.get("siteId").asText() == siteId.toString())
        }

    /** 해당 날짜 정오(KST) LUNCH 등으로 CONFIRMED 이벤트를 직접 시드한다(수량 목록 = 이벤트별 quantity). */
    private fun seedEvents(
        orgId: UUID,
        date: LocalDate,
        window: String,
        siteId: UUID?,
        vararg quantities: Int,
    ) {
        val occurredAt = date.atTime(12, 0).atZone(zone).toInstant()
        quantities.forEach { q ->
            eventRepository.save(event(orgId, "fc-${UUID.randomUUID()}", window, siteId, q, occurredAt))
        }
    }

    private fun event(
        orgId: UUID,
        externalId: String,
        window: String,
        siteId: UUID?,
        quantity: Int,
        occurredAt: Instant,
    ) = ConsumptionEvent(
        source = "pos",
        externalId = externalId,
        orgId = orgId,
        siteId = siteId,
        mealWindow = window,
        quantity = quantity,
        occurredAt = occurredAt,
    )

    private fun joinedSnapshot(
        orgId: UUID,
        userId: UUID,
        recordedAt: Instant,
    ) = MembershipHistory(
        orgId = orgId,
        userId = userId,
        role = "MEMBER",
        employmentStatus = "EMPLOYED",
        changeType = "JOINED",
        recordedAt = recordedAt,
    )

    private fun saveUser(email: String): User =
        userRepository.save(
            User(email = email, passwordHash = passwordEncoder.encode(password), emailVerified = true),
        )

    private fun login(email: String): WebSession {
        val session = webSession()
        session.perform(post("/login/identifier").param("email", email).with(csrf()))
        session
            .perform(post("/login/password").param("username", email).param("password", password).with(csrf()))
            .andExpect(status().is3xxRedirection)
        return session
    }

    /** 앱의 JWKSource 로 RS256 서명한 M2M 토큰(iss·exp·scope[·org_id]) — 예측 소비자 프로비저닝 토큰 모사. */
    private fun signedToken(
        scope: String,
        orgId: UUID?,
        subject: String = "svc-forecast",
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
}
