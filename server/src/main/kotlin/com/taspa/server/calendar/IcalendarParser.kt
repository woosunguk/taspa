package com.taspa.server.calendar

import biweekly.Biweekly
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.TimeZone

/** 파싱된 이벤트 occurrence 한 건. 반복(RRULE)은 이미 개별 occurrence 로 확장된 상태다. */
data class ParsedEvent(
    val uid: String,
    val summary: String?,
    val category: String?,
    val startsAt: Instant,
    val endsAt: Instant?,
    val allDay: Boolean,
)

/**
 * biweekly 로 .ics(RFC 5545)를 파싱한다(Phase 0-E).
 *
 * - VEVENT 의 UID·SUMMARY·DTSTART·DTEND·CATEGORIES·RRULE 을 읽는다.
 * - ★RRULE 반복은 조회 윈도우 내로만 확장한다(무한 반복 폭발 방지): [now-1d, now+expansionWindowDays],
 *   그리고 단일 이벤트가 만드는 occurrence 를 maxOccurrencesPerEvent 로 하드 캡한다(2차 방어).
 * - all-day(DATE, 시간 없음)는 allDay=true 로 표시한다.
 * - malformed 입력은 안전 실패한다(빈 목록 반환) — 서버를 죽이거나 예외를 전파하지 않는다.
 *
 * ★타임존 정규화(불변식): DATE(all-day) 값과 floating(TZID·Z 없음) datetime 값은 biweekly 가 **JVM 기본
 * 타임존**으로 파싱하므로 `toInstant()` 결과가 서버 TZ 에 따라 달라진다(예: KST 서버에서 `VALUE=DATE:20260810`
 * 이 `2026-08-09T15:00:00Z` 로 하루 어긋나 조회 윈도우를 벗어남). 단일·반복 경로 모두에서 이런 값의 **벽시계
 * 성분을 UTC 로 재해석**해 서버 TZ 와 무관하게 고정한다(계약: "floating 은 UTC 로 해석"). 명시적 UTC(Z) 또는
 * TZID 값은 이미 절대 instant 이므로 그대로 둔다.
 */
@Component
class IcalendarParser(
    private val properties: CalendarProperties,
) {
    private val log = LoggerFactory.getLogger(IcalendarParser::class.java)

    fun parse(
        icsText: String,
        defaultCategory: String?,
    ): List<ParsedEvent> {
        val ical =
            try {
                Biweekly.parse(icsText).first()
            } catch (ex: Exception) {
                log.warn("iCal 파싱 실패 — 빈 목록으로 안전 실패", ex)
                return emptyList()
            } ?: return emptyList()

        // 반복 확장 자체는 서버 기준(UTC)으로 순회한다. 실제 저장 instant 는 normalize() 로 UTC 정규화한다.
        val tz = TimeZone.getTimeZone("UTC")
        val now = Instant.now()
        val windowStart = now.minus(Duration.ofDays(1))
        val windowEnd = now.plus(Duration.ofDays(properties.expansionWindowDays))
        val results = mutableListOf<ParsedEvent>()

        for (event in ical.events) {
            // uid 는 calendar_events.uid 컬럼 상한(VARCHAR(512), V16)에 맞춰 절단한다(summary/category 와 동일
            // 규약). RFC 5545 는 UID 길이 상한이 없어 >512자 UID 한 건이 upsert 전체를 롤백시켜 피드를 영구
            // ERROR 로 만드는 것을 막는다.
            val uid =
                event.uid
                    ?.value
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.take(512) ?: continue
            val start = event.dateStart?.value ?: continue
            val summary = event.summary?.value?.take(500)
            val allDay = !start.hasTime()
            val category =
                event.categories
                    .flatMap { it.values }
                    .firstOrNull { it.isNotBlank() }
                    ?.take(64)
                    ?: defaultCategory
            val endValue = event.dateEnd?.value
            val durationMillis = endValue?.let { it.time - start.time }?.takeIf { it >= 0 }

            // DATE/floating(=TZID·Z 없음) 값만 UTC 재해석 대상이다. 명시 UTC(Z)·TZID 값은 절대 instant 라 그대로 둔다.
            val comps = start.rawComponents
            val floating = comps != null && !comps.isUtc && event.dateStart?.parameters?.timezoneId == null

            if (event.recurrenceRule?.value != null) {
                // ★반복 이벤트 — 윈도우 내 + 개수 상한으로만 확장한다.
                val iterator = event.getDateIterator(tz)
                iterator.advanceTo(Date.from(windowStart))
                var count = 0
                while (iterator.hasNext() && count < properties.maxOccurrencesPerEvent) {
                    val occInstant = normalize(iterator.next().toInstant(), floating)
                    if (occInstant.isAfter(windowEnd)) break
                    val occEnd = durationMillis?.let { occInstant.plusMillis(it) }
                    results += ParsedEvent(uid, summary, category, occInstant, occEnd, allDay)
                    count++
                }
            } else {
                // 단일 이벤트 — 폭발 위험이 없으므로 윈도우 필터 없이 그대로 저장한다(UTC 정규화만 적용).
                val startNorm = normalize(start.toInstant(), floating)
                val endNorm = durationMillis?.let { startNorm.plusMillis(it) }
                results += ParsedEvent(uid, summary, category, startNorm, endNorm, allDay)
            }
        }
        return results
    }

    /**
     * floating/DATE 값의 벽시계 성분을 UTC 로 재해석한다. `instant` 는 biweekly 가 JVM 기본 TZ 로 만든 값이므로
     * 같은 TZ 로 되돌려 벽시계(연·월·일·시·분·초)를 얻은 뒤 UTC 로 고정한다 — 서버 TZ 무관·DST 안전(occurrence별).
     * floating 이 아니면(절대 UTC/TZID) 그대로 반환한다.
     */
    private fun normalize(
        instant: Instant,
        floating: Boolean,
    ): Instant {
        if (!floating) return instant
        val z = instant.atZone(ZoneId.systemDefault())
        return LocalDateTime
            .of(z.year, z.monthValue, z.dayOfMonth, z.hour, z.minute, z.second)
            .toInstant(ZoneOffset.UTC)
    }
}
