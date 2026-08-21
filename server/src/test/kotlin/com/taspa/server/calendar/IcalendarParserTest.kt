package com.taspa.server.calendar

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.TimeZone

/**
 * iCal 파싱 순수 단위 테스트(Phase 0-E). 정상 파싱·all-day·CATEGORIES, ★RRULE 윈도우/개수 상한,
 * malformed 안전 실패(빈 목록)를 검증한다. 컨테이너 불필요.
 */
class IcalendarParserTest {
    private fun parser(
        expansionDays: Long = 400,
        maxOcc: Int = 1000,
    ) = IcalendarParser(CalendarProperties(expansionWindowDays = expansionDays, maxOccurrencesPerEvent = maxOcc))

    @Test
    fun `단일 all-day 이벤트를 파싱한다`() {
        val ics =
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//taspa//test//EN
            BEGIN:VEVENT
            UID:holiday-newyear-2026
            SUMMARY:New Year
            DTSTART;VALUE=DATE:20260101
            DTEND;VALUE=DATE:20260102
            CATEGORIES:PUBLIC_HOLIDAY
            END:VEVENT
            END:VCALENDAR
            """.trimIndent()

        val events = parser().parse(ics, defaultCategory = "HOLIDAY")

        assertThat(events).hasSize(1)
        val e = events.single()
        assertThat(e.uid).isEqualTo("holiday-newyear-2026")
        assertThat(e.summary).isEqualTo("New Year")
        assertThat(e.allDay).isTrue()
        assertThat(e.category).isEqualTo("PUBLIC_HOLIDAY")
    }

    @Test
    fun `CATEGORIES 가 없으면 feed 타입 기본값을 쓴다`() {
        val ics =
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:work-1
            SUMMARY:Company Day
            DTSTART:20260310T090000Z
            DTEND:20260310T180000Z
            END:VEVENT
            END:VCALENDAR
            """.trimIndent()

        val events = parser().parse(ics, defaultCategory = "WORK")

        assertThat(events).hasSize(1)
        assertThat(events.single().category).isEqualTo("WORK")
        assertThat(events.single().allDay).isFalse()
    }

    @Test
    fun `RRULE 은 조회 윈도우와 개수 상한 내로만 확장한다`() {
        // 과거 시작 + 매일 반복 → advanceTo(now) 후 개수 상한(5)까지만 확장돼야 한다(무한 폭발 방지).
        val ics =
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:daily-standup
            SUMMARY:Daily Standup
            DTSTART:20200101T000000Z
            RRULE:FREQ=DAILY
            END:VEVENT
            END:VCALENDAR
            """.trimIndent()

        val events = parser(expansionDays = 30, maxOcc = 5).parse(ics, defaultCategory = "WORK")

        // 개수 상한(5)이 30일 윈도우(약 31회)보다 작으므로 정확히 5 로 잘린다.
        assertThat(events).hasSize(5)
        assertThat(events.map { it.uid }.distinct()).containsExactly("daily-standup")
        // occurrence 마다 startsAt 이 달라야 한다(upsert 키 (feed,uid,startsAt) 구분).
        assertThat(events.map { it.startsAt }.distinct()).hasSize(5)
    }

    @Test
    fun `RRULE 은 윈도우가 개수상한보다 좁으면 윈도우로 잘린다`() {
        val ics =
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:daily-2
            SUMMARY:Daily
            DTSTART:20200101T000000Z
            RRULE:FREQ=DAILY
            END:VEVENT
            END:VCALENDAR
            """.trimIndent()

        // 윈도우 3일(≈4회) < 개수상한 1000 → 윈도우가 상한이 된다.
        val events = parser(expansionDays = 3, maxOcc = 1000).parse(ics, defaultCategory = "WORK")

        assertThat(events.size).isLessThanOrEqualTo(6)
        assertThat(events).isNotEmpty()
    }

    @Test
    fun `malformed 입력은 빈 목록으로 안전 실패한다`() {
        assertThat(parser().parse("this is not a calendar", null)).isEmpty()
        assertThat(parser().parse("", null)).isEmpty()
        assertThat(parser().parse("BEGIN:VCALENDAR\ngarbage\n", null)).isEmpty()
    }

    @Test
    fun `all-day 와 floating 이벤트는 서버 TZ 와 무관하게 UTC 로 정규화된다`() {
        // 비-UTC(KST) JVM 에서도 DATE·floating 값이 UTC 벽시계로 고정돼야 한다(하루/시간 어긋남 방지).
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
            val ics =
                """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:allday
                SUMMARY:Holiday
                DTSTART;VALUE=DATE:20260810
                END:VEVENT
                BEGIN:VEVENT
                UID:floating
                SUMMARY:Floating
                DTSTART:20260810T090000
                END:VEVENT
                BEGIN:VEVENT
                UID:utc
                SUMMARY:Utc
                DTSTART:20260810T090000Z
                END:VEVENT
                END:VCALENDAR
                """.trimIndent()

            val events = parser().parse(ics, "HOLIDAY").associateBy { it.uid }

            // DATE(all-day) → UTC 자정, floating → UTC 벽시계, 명시 UTC(Z) → 그대로. 모두 서버 TZ 무관.
            assertThat(events.getValue("allday").startsAt).isEqualTo(Instant.parse("2026-08-10T00:00:00Z"))
            assertThat(events.getValue("allday").allDay).isTrue()
            assertThat(events.getValue("floating").startsAt).isEqualTo(Instant.parse("2026-08-10T09:00:00Z"))
            assertThat(events.getValue("utc").startsAt).isEqualTo(Instant.parse("2026-08-10T09:00:00Z"))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `RRULE all-day 는 서버 TZ 와 무관하게 UTC 자정으로 정규화된다`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
            val ics =
                """
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:daily-allday
                SUMMARY:Daily Holiday
                DTSTART;VALUE=DATE:20200101
                RRULE:FREQ=DAILY
                END:VEVENT
                END:VCALENDAR
                """.trimIndent()

            val events = parser(expansionDays = 30, maxOcc = 5).parse(ics, "HOLIDAY")

            assertThat(events).isNotEmpty()
            // 모든 occurrence 의 시각 성분이 UTC 자정이어야 한다(하루 어긋남 없음).
            assertThat(events.map { it.startsAt.toString().substring(11) }.distinct()).containsExactly("00:00:00Z")
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `UID 없는 VEVENT 는 건너뛴다`() {
        val ics =
            """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            SUMMARY:No UID
            DTSTART:20260310T090000Z
            END:VEVENT
            END:VCALENDAR
            """.trimIndent()

        assertThat(parser().parse(ics, "EVENT")).isEmpty()
    }
}
