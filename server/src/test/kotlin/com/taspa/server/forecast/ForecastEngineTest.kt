package com.taspa.server.forecast

import com.taspa.server.forecast.dto.ForecastMethod
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * 예측 엔진 단위 테스트 — **DB 없이** 돈다(seam 3개만 주입).
 *
 * 여기서 고정하는 것은 "표본이 이럴 때 무엇을 고르는가"다. 통합 테스트로 같은 조합을 검증하면 픽스처가
 * 규칙보다 길어지고 케이스마다 수 초가 들어 조합을 포기하게 된다 — 그래서 조합은 전부 이 파일에 있다.
 */
class ForecastEngineTest {
    private val engine = ForecastEngine(ForecastProperties())

    /** 방법 자동 선택은 **기본 비활성**이다(근거는 ForecastProperties KDoc 의 실측). 기계 자체를 검증할 때만 켠다. */
    private val selecting = ForecastEngine(ForecastProperties(methodSelectionEnabled = true))
    private val window = "LUNCH"

    /** 타깃: 2026-08-26 (수요일). 모든 basis 후보는 여기서 7일 단위로 뺀 날짜다. */
    private val target = LocalDate.of(2026, 8, 26)

    // ---- 도입 전 동작 보존 (표본이 적으면 새 로직은 발동하지 않는다) ----

    @Test
    fun `전주 동요일 하나만 있으면 기존대로 재실 비율을 곱한 SEASONAL_NAIVE_ADJUSTED 다`() {
        val actuals = FakeActuals().put(target.minusDays(7), 42)

        val p = predict(actuals, headcounts = { 7L }, hcRef = 6L)

        assertThat(p.method).isEqualTo(ForecastMethod.SEASONAL_NAIVE_ADJUSTED)
        assertThat(p.predicted).isEqualTo(36L) // 42 × 6/7
        assertThat(p.basis.holdoutPoints).isNull() // 채점판 미발동 = 기존 경로
        assertThat(p.p90).isNull() // 산포 근거 없음 → 숫자를 지어내지 않는다
    }

    @Test
    fun `재실 비율이 신뢰 범위 밖이면 보정을 생략하고 SEASONAL_NAIVE 로 강등한다`() {
        val actuals = FakeActuals().put(target.minusDays(7), 42)

        val p = predict(actuals, headcounts = { 2L }, hcRef = 100L) // 비율 50 → 범위 밖

        assertThat(p.method).isEqualTo(ForecastMethod.SEASONAL_NAIVE)
        assertThat(p.predicted).isEqualTo(42L)
    }

    @Test
    fun `전주가 없으면 4주 같은 요일 평균이고 그것도 없으면 NO_DATA 다`() {
        val avg = predict(FakeActuals().put(target.minusDays(14), 30).put(target.minusDays(21), 20), { null })
        assertThat(avg.method).isEqualTo(ForecastMethod.FOUR_WEEK_AVG)
        assertThat(avg.predicted).isEqualTo(25L)

        val none = predict(FakeActuals(), { null })
        assertThat(none.method).isEqualTo(ForecastMethod.NO_DATA)
        assertThat(none.predicted).isNull() // ★0 이 아니다
    }

    // ---- 방법 선택(holdout) ----

    @Test
    fun `표본이 충분하면 후보를 채점해 방법을 고르고 그 근거를 응답에 싣는다`() {
        val actuals = weekly(target, List(10) { 100L })

        val p = predict(actuals, headcounts = { 100L }, hcRef = 100L, engine = selecting)

        assertThat(p.basis.holdoutPoints).isEqualTo(4)
        assertThat(p.basis.candidates).isNotNull
        assertThat(p.basis.candidates!!.map { it.method })
            .contains(ForecastMethod.SEASONAL_NAIVE_ADJUSTED, ForecastMethod.TRIMMED_SEASONAL)
        assertThat(p.basis.holdoutWape).isNotNull
        assertThat(p.predicted).isEqualTo(100L)
    }

    @Test
    fun `이상치가 섞인 이력에서는 트림 계절이 전주 동요일을 채점으로 이긴다`() {
        // 3주 전에 전사 행사로 300 인분이 나갔다. 전주 동요일 방식은 그 값을 다음 주 예측에 그대로
        // 옮기고, 그 다음 주에는 정상값으로 되돌아가며 두 번 크게 틀린다.
        val history = List(10) { if (it == 2) 300L else 100L }
        val actuals = weekly(target, history)

        val p = predict(actuals, headcounts = { 100L }, hcRef = 100L, engine = selecting)

        assertThat(p.method).isEqualTo(ForecastMethod.TRIMMED_SEASONAL)
        assertThat(p.predicted).isEqualTo(100L) // 이상치를 흡수한 값
        val scores = p.basis.candidates!!.associate { it.method to it.wape }
        assertThat(scores[ForecastMethod.TRIMMED_SEASONAL]!!)
            .isLessThan(scores[ForecastMethod.SEASONAL_NAIVE_ADJUSTED]!!)
    }

    @Test
    fun `기본 설정에서는 방법 선택이 꺼져 있어 같은 이력에도 도입 전 방법을 쓴다(대조군)`() {
        // 위 테스트와 **같은 이상치 이력**이다. 다른 것은 설정뿐이다.
        val actuals = weekly(target, List(10) { if (it == 2) 300L else 100L })

        val off = predict(actuals, headcounts = { 100L }, hcRef = 100L)
        val on = predict(actuals, headcounts = { 100L }, hcRef = 100L, engine = selecting)

        assertThat(off.method).isEqualTo(ForecastMethod.SEASONAL_NAIVE_ADJUSTED)
        assertThat(on.method).isEqualTo(ForecastMethod.TRIMMED_SEASONAL)
        // 기본값은 산포(p90)만 새로 제공하고 점예측은 건드리지 않는다.
        assertThat(off.predicted).isEqualTo(100L)
        assertThat(off.p90).isNotNull
    }

    @Test
    fun `준비량 분위수는 점예측보다 작지 않고 산포가 클수록 커진다`() {
        val stable = predict(weekly(target, List(10) { 100L }), { 100L }, hcRef = 100L)
        val volatile = predict(weekly(target, listOf(100L, 60L, 140L, 90L, 130L, 70L, 120L, 100L, 110L, 95L)), { 100L }, hcRef = 100L)

        assertThat(stable.p90).isNotNull
        assertThat(stable.p90!!).isGreaterThanOrEqualTo(stable.predicted!!)
        assertThat(volatile.p90!! - volatile.predicted!!).isGreaterThan(stable.p90!! - stable.predicted!!)
    }

    // ---- 커버리지 (참여율) ----

    @Test
    fun `동요일 실적이 4주 창 밖에만 있으면 참여율로 채운다(기존엔 NO_DATA)`() {
        // 수요일 실적은 5주 전 하나뿐 — 기존 체인은 전주도 4주 평균도 만들 수 없다.
        val actuals = FakeActuals().put(target.minusDays(35), 50)
        // 다른 요일은 최근 2주간 충분히 관측됐다(참여 수준을 여기서 배운다).
        var d = target.minusDays(14)
        while (d.isBefore(target)) {
            if (d.dayOfWeek.value <= 5 && d.dayOfWeek != target.dayOfWeek) actuals.put(d, 50)
            d = d.plusDays(1)
        }

        val p = predict(actuals, headcounts = { 100L }, hcRef = 100L)

        assertThat(p.method).isEqualTo(ForecastMethod.PARTICIPATION_RATE)
        assertThat(p.predicted).isEqualTo(50L)
    }

    @Test
    fun `배식한 적 없는 요일은 참여율로도 예측하지 않는다(주말에 평일 수준을 내는 사고 방지)`() {
        val saturday = LocalDate.of(2026, 8, 29)
        val actuals = FakeActuals()
        var d = saturday.minusDays(28)
        while (d.isBefore(saturday)) {
            if (d.dayOfWeek.value <= 5) actuals.put(d, 50) // 평일만 배식
            d = d.plusDays(1)
        }

        val p = predict(actuals, headcounts = { 100L }, hcRef = 100L, date = saturday)

        assertThat(p.method).isEqualTo(ForecastMethod.NO_DATA)
        assertThat(p.predicted).isNull()
    }

    // ---- 휴일 불변식 ----

    @Test
    fun `휴일 타깃에 비교 가능한 과거 휴일이 없으면 평일 실적을 대입하지 않고 NO_DATA 다`() {
        val actuals = weekly(target, List(10) { 100L })

        val p = predict(actuals, headcounts = { 100L }, hcRef = 100L, holidays = FakeHolidays(target to "창립기념일"))

        assertThat(p.method).isEqualTo(ForecastMethod.NO_DATA)
        assertThat(p.holiday).isTrue
        assertThat(p.holidayName).isEqualTo("창립기념일")
    }

    @Test
    fun `휴일 타깃은 과거 휴일 실적만 basis 로 쓴다`() {
        val pastHoliday = target.minusDays(7)
        val actuals = weekly(target, List(10) { 100L }).put(pastHoliday, 9)

        val p =
            predict(
                actuals,
                headcounts = { 100L },
                hcRef = 100L,
                holidays = FakeHolidays(target to "휴무", pastHoliday to "휴무"),
            )

        assertThat(p.predicted).isEqualTo(9L) // 평일 100 이 아니라 과거 휴일 9
    }

    // ---- 사내 행사(iCalendar EVENT) 대칭 ----

    @Test
    fun `행사일은 과거 행사일 실적만 basis 로 쓴다(평일 실적을 대입하지 않는다)`() {
        val pastEvent = target.minusDays(7)
        val actuals = weekly(target, List(10) { 100L }).put(pastEvent, 40)

        val p = predict(actuals, { 100L }, hcRef = 100L, holidays = FakeDays(events = setOf(target, pastEvent)))

        assertThat(p.predicted).isEqualTo(40L) // 평일 100 이 아니라 과거 행사 40
        assertThat(p.event).isTrue
        assertThat(p.holiday).isFalse // 행사는 휴무가 아니다 — 화면이 다르게 말해야 한다
    }

    @Test
    fun `행사일에 비교 가능한 과거 행사가 없으면 평일 실적을 대입하지 않고 NO_DATA 다`() {
        val actuals = weekly(target, List(10) { 100L })

        val p = predict(actuals, { 100L }, hcRef = 100L, holidays = FakeDays(events = setOf(target)))

        assertThat(p.method).isEqualTo(ForecastMethod.NO_DATA)
        assertThat(p.predicted).isNull()
    }

    @Test
    fun `평일 타깃은 과거 행사일을 basis 에서 제외한다(대칭 — 과대예측 방지)`() {
        // 전주 동요일이 전사 행사로 40 이었다. 그 값을 평일에 옮기면 크게 과소예측한다.
        val eventDay = target.minusDays(7)
        val actuals = weekly(target, List(10) { 100L }).put(eventDay, 40)

        val p = predict(actuals, { 100L }, hcRef = 100L, holidays = FakeDays(events = setOf(eventDay)))

        assertThat(p.predicted).isNotEqualTo(40L)
        assertThat(p.basis.excludedHolidayBasis).isGreaterThan(0)
    }

    @Test
    fun `휴일이 행사보다 우선한다(둘 다 선언된 날)`() {
        val both = target
        val pastHoliday = target.minusDays(7)
        val actuals = weekly(target, List(10) { 100L }).put(pastHoliday, 9)

        val p =
            predict(
                actuals,
                { 100L },
                hcRef = 100L,
                holidays = FakeDays(holidays = mapOf(both to "창립기념일", pastHoliday to "휴무"), events = setOf(both)),
            )

        assertThat(p.predicted).isEqualTo(9L) // 휴일 basis 를 썼다
        assertThat(p.holiday).isTrue
    }

    @Test
    fun `행사 신호가 비어 있으면 도입 전과 결과가 같다(대조군)`() {
        val eventDay = target.minusDays(7)
        val actuals = weekly(target, List(10) { 100L }).put(eventDay, 40)

        val off = predict(actuals, { 100L }, hcRef = 100L) // 행사 미선언 = 신호 OFF 와 같은 상태
        val on = predict(actuals, { 100L }, hcRef = 100L, holidays = FakeDays(events = setOf(eventDay)))

        assertThat(off.predicted).isEqualTo(40L) // 옛 동작: 전주 동요일을 그대로 쓴다
        assertThat(on.predicted).isNotEqualTo(off.predicted)
    }

    // ---- 부재(연차·반차) 반영 ----

    @Test
    fun `연차로 재실이 줄면 예측도 그만큼 줄어든다`() {
        val actuals = FakeActuals().put(target.minusDays(7), 40)

        // 전주에는 40명 전원 재실, 타깃일은 10명이 연차라 30명.
        val full = predict(actuals, headcounts = { 40L }, hcRef = 40L)
        val onLeave = predict(actuals, headcounts = { 40L }, hcRef = 30L)

        assertThat(full.predicted).isEqualTo(40L)
        assertThat(onLeave.predicted).isEqualTo(30L) // 40 × 30/40
        assertThat(onLeave.method).isEqualTo(ForecastMethod.SEASONAL_NAIVE_ADJUSTED)
    }

    // ---- 누수 ----

    @Test
    fun `타깃 당일과 그 이후 실적은 예측에 영향을 주지 않는다(미래정보 누수 차단)`() {
        val base = weekly(target, List(10) { 100L })
        val before = predict(base, { 100L }, hcRef = 100L)

        val polluted = weekly(target, List(10) { 100L }).put(target, 9999).put(target.plusDays(7), 9999)
        val after = predict(polluted, { 100L }, hcRef = 100L)

        assertThat(after.predicted).isEqualTo(before.predicted)
        assertThat(after.method).isEqualTo(before.method)
    }

    // ---- 픽스처 ----

    private fun predict(
        actuals: FakeActuals,
        headcounts: (LocalDate) -> Long?,
        hcRef: Long? = 100L,
        date: LocalDate = target,
        holidays: HolidayLookup = FakeHolidays(),
        engine: ForecastEngine = this.engine,
    ) = engine.predict(actuals, HeadcountsAsOf { headcounts(it) }, holidays, date, null, window, hcRef)

    /** 타깃과 같은 요일로 1주 전부터 N주 전까지 값을 채운다(values[0] = 1주 전). */
    private fun weekly(
        target: LocalDate,
        values: List<Long>,
    ): FakeActuals {
        val a = FakeActuals()
        values.forEachIndexed { i, v -> a.put(target.minusDays(7L * (i + 1)), v) }
        return a
    }

    private class FakeActuals : ActualsAt {
        private val data = HashMap<Pair<LocalDate, String>, Long>()

        fun put(
            date: LocalDate,
            quantity: Long,
        ): FakeActuals {
            data[date to "LUNCH"] = quantity
            return this
        }

        override fun at(
            date: LocalDate,
            site: UUID?,
            window: String,
        ): Long? = data[date to window]
    }

    /** 휴일·행사를 함께 선언하는 픽스처. 행사 인자를 비우면 [FakeHolidays] 와 동일하게 동작한다. */
    private class FakeDays(
        private val holidays: Map<LocalDate, String> = emptyMap(),
        private val events: Set<LocalDate> = emptySet(),
    ) : HolidayLookup {
        override fun isHoliday(date: LocalDate): Boolean = holidays.containsKey(date)

        override fun nameOf(date: LocalDate): String? = holidays[date]

        override fun isEvent(date: LocalDate): Boolean = events.contains(date)

        override fun eventNameOf(date: LocalDate): String? = if (events.contains(date)) "전사 행사" else null
    }

    private class FakeHolidays(
        vararg entries: Pair<LocalDate, String>,
    ) : HolidayLookup {
        private val byDate = entries.toMap()

        override fun isHoliday(date: LocalDate): Boolean = byDate.containsKey(date)

        override fun nameOf(date: LocalDate): String? = byDate[date]
    }
}
