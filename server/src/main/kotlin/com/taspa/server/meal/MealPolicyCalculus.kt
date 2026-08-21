package com.taspa.server.meal

import com.taspa.server.domain.consumption.MealWindow
import com.taspa.server.domain.meal.MealPolicyValues
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 식대 정책 계산의 **단일 출처**(끼니창·일 경계·월 경계).
 *
 * ★별도 object 로 뽑은 이유는 재사용 편의가 아니라 **어긋나면 안 되기 때문**이다. 승인(POS redeem)이
 * 쓰는 판정과 자격 조회(직원 화면이 "지금 결제되나"를 말하는 근거)가 다른 코드로 계산되면, 화면은
 * "가능"인데 계산대에서 MEAL_WINDOW_CLOSED/DAILY_MEAL_LIMIT 로 거절되는 일이 생긴다 — 그 순간을
 * 겪는 건 줄 서 있는 직원이다. 두 경로가 같은 함수를 부르게 해서 그 어긋남을 구조적으로 없앤다.
 *
 * 정책 시각은 전부 **org 로컬 시각**으로 판정한다(organizations.timezone 앵커).
 */
object MealPolicyCalculus {
    /** organizations.timezone 은 저장 시 검증되지만, 혹시 모를 손상 행은 UTC 로 폴백한다(fail-safe). */
    fun zoneOf(timezone: String): ZoneId = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("UTC"))

    /** 끼니창 판정 — [start, end) 반개구간, breakfast → lunch → dinner 순서 평가. */
    fun resolveWindow(
        policy: MealPolicyValues,
        localNow: ZonedDateTime,
    ): MealWindow? {
        val time = localNow.toLocalTime()
        return when {
            time >= policy.breakfastStart && time < policy.breakfastEnd -> MealWindow.BREAKFAST
            time >= policy.lunchStart && time < policy.lunchEnd -> MealWindow.LUNCH
            time >= policy.dinnerStart && time < policy.dinnerEnd -> MealWindow.DINNER
            else -> null
        }
    }

    /** org 로컬 일 경계 [from, to) — daily_meal_count 판정 창. */
    fun dayBounds(localNow: ZonedDateTime): Pair<Instant, Instant> {
        val date = localNow.toLocalDate()
        return date.atStartOfDay(localNow.zone).toInstant() to
            date.plusDays(1).atStartOfDay(localNow.zone).toInstant()
    }

    /** org 로컬 월 경계 [from, to) — monthly_cap 판정 창. */
    fun monthBounds(localNow: ZonedDateTime): Pair<Instant, Instant> {
        val first = localNow.toLocalDate().withDayOfMonth(1)
        return first.atStartOfDay(localNow.zone).toInstant() to
            first.plusMonths(1).atStartOfDay(localNow.zone).toInstant()
    }

    /** 정책이 정의한 한 끼니창(로컬 시각 구간 + 그 회차의 절대 시각 구간). */
    data class WindowOccurrence(
        val window: MealWindow,
        val start: LocalTime,
        val end: LocalTime,
        val startsAt: Instant,
        val endsAt: Instant,
    )

    /**
     * 지금 열려 있는 끼니창을 회차(오늘 날짜)로 실체화한다. `resolveWindow` 와 같은 판정을 쓰므로
     * 승인 경로와 어긋날 수 없다.
     */
    fun currentOccurrence(
        policy: MealPolicyValues,
        localNow: ZonedDateTime,
    ): WindowOccurrence? {
        val window = resolveWindow(policy, localNow) ?: return null
        val bounds = openWindows(policy).first { it.first == window }
        return occurrenceOf(bounds, localNow.toLocalDate(), localNow.zone)
    }

    /**
     * 다음에 열릴 끼니창 회차. 오늘 남은 창이 있으면 그중 가장 이른 것, 없으면 내일의 첫 창이다.
     * 지금 창 안이면 그 다음 창을 가리킨다(시작 시각이 지금보다 뒤인 창만 후보).
     */
    fun nextOccurrence(
        policy: MealPolicyValues,
        localNow: ZonedDateTime,
    ): WindowOccurrence? {
        val windows = openWindows(policy)
        if (windows.isEmpty()) return null
        val time = localNow.toLocalTime()
        val today = windows.firstOrNull { it.second > time }
        val date = if (today != null) localNow.toLocalDate() else localNow.toLocalDate().plusDays(1)
        return occurrenceOf(today ?: windows.first(), date, localNow.zone)
    }

    private fun occurrenceOf(
        bounds: Triple<MealWindow, LocalTime, LocalTime>,
        date: java.time.LocalDate,
        zone: ZoneId,
    ): WindowOccurrence =
        WindowOccurrence(
            window = bounds.first,
            start = bounds.second,
            end = bounds.third,
            startsAt = date.atTime(bounds.second).atZone(zone).toInstant(),
            endsAt = date.atTime(bounds.third).atZone(zone).toInstant(),
        )

    /**
     * 시작 시각 순으로 정렬한 끼니창 목록.
     *
     * start >= end 인 창은 뺀다 — `resolveWindow` 의 반개구간 판정상 **열리는 순간이 없는** 창이라,
     * 후보에 남기면 "다음 끼니"가 영원히 오지 않을 시각을 가리키게 된다.
     */
    private fun openWindows(policy: MealPolicyValues): List<Triple<MealWindow, LocalTime, LocalTime>> =
        listOf(
            Triple(MealWindow.BREAKFAST, policy.breakfastStart, policy.breakfastEnd),
            Triple(MealWindow.LUNCH, policy.lunchStart, policy.lunchEnd),
            Triple(MealWindow.DINNER, policy.dinnerStart, policy.dinnerEnd),
        ).filter { it.second < it.third }
            .sortedBy { it.second }
}
