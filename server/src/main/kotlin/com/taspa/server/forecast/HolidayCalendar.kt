package com.taspa.server.forecast

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.calendar.CalendarEvent
import com.taspa.server.domain.calendar.CalendarEventRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 조직 캘린더(`calendar_events`)에서 **휴일 날짜 집합**을 뽑아 예측에 공급한다.
 *
 * **휴일 판정 규칙(둘 다 만족)**:
 *  1. `all_day = true` — 시각이 붙은 이벤트(회의·교육)는 그 날 하루를 쉬게 만들지 않는다.
 *  2. 피드 `type = 'HOLIDAY'` **또는** 이벤트 `category = 'HOLIDAY'`(대소문자 무시).
 *
 * 2번이 핵심이다 — summary 텍스트("창립기념일")로 의미를 추측하지 않고, **조직관리자가 피드를 등록할 때
 * 선언한 유형**만 믿는다. 추측은 언어·표기·조직마다 달라 예측을 조용히 틀리게 만들지만, 피드 유형은
 * 감사 가능한 명시적 선언이다. `category` 를 함께 보는 이유는 파서가 VEVENT 의 `CATEGORIES` 를 우선 채택해
 * HOLIDAY 피드의 이벤트라도 category 가 다른 값이 될 수 있고(그래서 피드 유형이 주 신호), 반대로 EVENT/WORK
 * 피드 안에 `CATEGORIES:HOLIDAY` 로 명시된 하루가 있을 수 있기 때문이다.
 *
 * ★**all-day 이벤트는 instant 가 아니라 달력 날짜다.** `IcalendarParser` 가 DATE·floating 값의 벽시계를
 * **UTC 로 고정**해 저장하므로(그 클래스의 타임존 정규화 불변식), 여기서도 **UTC 벽시계 날짜**를 그대로 읽는다.
 * org 타임존으로 변환하면 UTC 서편 존(예: America/Los_Angeles)에서 `2026-08-10T00:00Z` 가 08-09 로 밀려
 * 휴일이 하루 어긋난다 — 소비 실적의 date 버킷이 org 타임존 앵커인 것과 **의도적으로 다르다**(그쪽은 실제
 * 발생 시각, 이쪽은 날짜 자체가 값이다).
 *
 * DTEND 는 RFC 5545 에서 **배타적**이다 — `20260810`~`20260813` 은 10·11·12 사흘이다.
 */
@Component
class HolidayCalendar(
    private val eventRepository: CalendarEventRepository,
) {
    /**
     * [from]~[to](양끝 포함) 구간에 걸치는 휴일 날짜를 한 번에 읽어 인덱싱한다.
     * 구간 시작 이전에 시작한 연휴도 잡히도록 조회 하한을 [MAX_SPAN_DAYS] 만큼 앞으로 넓힌다.
     */
    fun load(
        orgId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): HolidayIndex {
        if (to.isBefore(from)) return HolidayIndex.EMPTY
        val queryFrom = from.minusDays(MAX_SPAN_DAYS).atStartOfDay(ZoneOffset.UTC).toInstant()
        val queryTo = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val rows =
            eventRepository.findHolidayWindow(
                orgId,
                queryFrom,
                queryTo,
                PageRequest.of(0, ROW_LIMIT, Sort.by(Sort.Direction.ASC, "startsAt")),
            )
        if (rows.size >= ROW_LIMIT) {
            // 조용히 잘리면 잘린 날의 휴일이 "평일"로 보여 basis 선택이 틀린다 — 그 왜곡은 응답에 드러나지
            // 않으므로 fail-loud 한다(실적 집계 상한과 동일 사상). 한 구간의 서로 다른 날짜 수보다 훨씬 큰
            // 상한이라 정상 운영에서는 도달하지 않는다.
            throw AuthException(
                ErrorCode.VALIDATION_ERROR,
                "휴일 캘린더 규모가 상한(${ROW_LIMIT}건)에 도달했습니다 — 조회 창(from/to)을 줄여 다시 시도하세요",
            )
        }
        val byDate = HashMap<LocalDate, String?>()
        rows.forEach { event ->
            val name = event.summary?.trim()?.takeIf { it.isNotEmpty() }
            datesOf(event).forEach { date ->
                if (!date.isBefore(from) && !date.isAfter(to)) {
                    // 같은 날 휴일이 여럿이면 먼저 시작한 이벤트의 이름을 남긴다(정렬이 결정적이라 안정적).
                    byDate.putIfAbsent(date, name)
                }
            }
        }
        return HolidayIndex(byDate)
    }

    /** 이벤트가 덮는 날짜들(UTC 벽시계 기준, DTEND 배타). 손상된 장기 이벤트는 [MAX_SPAN_DAYS] 로 잘라 폭주를 막는다. */
    private fun datesOf(event: CalendarEvent): List<LocalDate> {
        val start = event.startsAt.atZone(ZoneOffset.UTC).toLocalDate()
        val endExclusive = event.endsAt?.atZone(ZoneOffset.UTC)?.toLocalDate()
        val span =
            if (endExclusive != null && endExclusive.isAfter(start)) {
                ChronoUnit.DAYS.between(start, endExclusive)
            } else {
                1L
            }
        return (0 until minOf(span, MAX_SPAN_DAYS)).map { start.plusDays(it) }
    }

    private companion object {
        /** 단일 휴일 이벤트가 덮을 수 있는 최대 일수(연휴 상한 겸 손상 데이터 방어선). */
        const val MAX_SPAN_DAYS = 31L

        /** 휴일 이벤트 행 상한 — 조회 창(최대 124일)의 날짜 수보다 훨씬 크다(그룹 폭주 방어선). */
        const val ROW_LIMIT = 5_000
    }
}

/**
 * 날짜 → 휴일명(없으면 null) 인덱스. **키의 존재 여부가 휴일 판정**이고 값(이름)은 설명용이다 —
 * 이름 없는 휴일 이벤트(SUMMARY 누락)도 휴일로 취급해야 하므로 값 null 과 키 부재를 구분한다.
 */
class HolidayIndex(
    private val byDate: Map<LocalDate, String?>,
) {
    fun isHoliday(date: LocalDate): Boolean = byDate.containsKey(date)

    fun nameOf(date: LocalDate): String? = byDate[date]

    companion object {
        /** 캘린더가 없는 조직의 인덱스 — 모든 날짜가 평일이라 예측은 캘린더 도입 전과 정확히 같아진다. */
        val EMPTY = HolidayIndex(emptyMap())
    }
}
