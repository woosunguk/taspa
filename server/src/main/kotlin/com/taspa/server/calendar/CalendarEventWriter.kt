package com.taspa.server.calendar

import com.taspa.server.domain.calendar.CalendarEvent
import com.taspa.server.domain.calendar.CalendarEventRepository
import com.taspa.server.domain.calendar.CalendarEventSource
import com.taspa.server.domain.calendar.CalendarFeedRepository
import com.taspa.server.domain.calendar.CalendarSyncStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 캘린더 이벤트 쓰기 단위(Phase 0-E). CalendarService(오케스트레이션)와 **별도 빈**으로 분리해, 각 메서드가
 * 프록시를 경유한 **독립 짧은 트랜잭션**으로 실행되게 한다. 이 분리가 두 결함을 함께 해소한다:
 *  - 느린 외부 fetch 는 이 트랜잭션 밖(CalendarService)에서 수행 → fetch 동안 DB 커넥션 미점유(풀 고갈 방지).
 *  - 업서트 트랜잭션이 자기완결적이라 경합(UNIQUE 위반) 시 **전체가 깨끗이 롤백**되고 예외가 그대로 전파된다
 *    → 상위(비트랜잭션)에서 잡아 ERROR 상태를 **별도 트랜잭션**으로 기록한다(false OK·rollback-only 오염 없음).
 */
@Service
class CalendarEventWriter(
    private val eventRepository: CalendarEventRepository,
    private val feedRepository: CalendarFeedRepository,
) {
    /**
     * feed 단위 대체 동기화(mark-and-sweep). (uid, starts_at) 키로 업서트하고, **이번 실행에서 보지 못한**
     * 기존 행(소스에서 삭제·DTSTART 변경·RRULE 변경으로 사라진 occurrence)을 삭제해 고아·중복 누적을 막는다.
     * 성공 시 feed 상태를 OK 로 스탬프한다. 반환: 신규+갱신 처리 건수.
     */
    @Transactional
    fun upsertFeedEvents(
        feedId: UUID,
        orgId: UUID,
        source: CalendarEventSource,
        parsed: List<ParsedEvent>,
    ): Int {
        val existing = eventRepository.findByFeedId(feedId)
        val existingByKey = existing.associateBy { it.uid to it.startsAt }
        val seen = HashSet<Pair<String, Instant>>()
        var count = 0
        for (p in parsed) {
            val key = p.uid to p.startsAt
            // 같은 .ics 내 중복 (uid, starts_at)은 첫 건만 처리한다 — 단일 트랜잭션 내 중복 flush(UNIQUE 위반) 회피.
            if (!seen.add(key)) continue
            val row = existingByKey[key]
            if (row != null) {
                row.summary = p.summary
                row.category = p.category
                row.endsAt = p.endsAt
                row.allDay = p.allDay
                row.source = source.name
                eventRepository.save(row)
            } else {
                eventRepository.save(
                    CalendarEvent(
                        orgId = orgId,
                        feedId = feedId,
                        uid = p.uid,
                        summary = p.summary,
                        category = p.category,
                        startsAt = p.startsAt,
                        endsAt = p.endsAt,
                        allDay = p.allDay,
                        source = source.name,
                    ),
                )
            }
            count++
        }
        // sweep — 이번 실행에서 보지 못한 feed 소속 행 삭제(고아 정리).
        val orphans = existing.filterNot { (it.uid to it.startsAt) in seen }
        if (orphans.isNotEmpty()) {
            eventRepository.deleteAll(orphans)
        }
        stamp(feedId, CalendarSyncStatus.OK)
        return count
    }

    /** feed 동기화 상태만 별도 트랜잭션으로 기록한다(fetch·파싱·업서트 실패 시 ERROR 스탬프에 사용). */
    @Transactional
    fun markStatus(
        feedId: UUID,
        status: CalendarSyncStatus,
    ) {
        stamp(feedId, status)
    }

    private fun stamp(
        feedId: UUID,
        status: CalendarSyncStatus,
    ) {
        val feed = feedRepository.findById(feedId).orElse(null) ?: return
        feed.lastSyncedAt = Instant.now()
        feed.lastSyncStatus = status.name
        feedRepository.save(feed)
    }
}
