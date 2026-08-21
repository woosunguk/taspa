package com.taspa.server.domain.calendar

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface CalendarEventRepository : JpaRepository<CalendarEvent, UUID> {
    /** upsert 키 조회 — (feed_id, uid, starts_at). */
    fun findByFeedIdAndUidAndStartsAt(
        feedId: UUID,
        uid: String,
        startsAt: Instant,
    ): CalendarEvent?

    /** feed 단위 전체 조회 — 재동기화 mark-and-sweep(고아 정리)에 사용. */
    fun findByFeedId(feedId: UUID): List<CalendarEvent>

    /** feed 단위 페이징 조회 — 관리 콘솔의 최근 이벤트 미리보기에 사용(org 격리는 호출부가 feed 소유를 검증). */
    fun findByFeedId(
        feedId: UUID,
        pageable: Pageable,
    ): Page<CalendarEvent>

    /**
     * org 스코프 조회 윈도우([from, to)) — starts_at 오름차순, ★페이징 강제(행 수 상한). org 격리의 핵심(orgId 필수).
     * 정렬은 Pageable(Sort)로 지정한다. countQuery 로 total 을 함께 산출해 응답 페이지 메타를 노출한다.
     */
    @Query(
        value = "SELECT e FROM CalendarEvent e WHERE e.orgId = :orgId AND e.startsAt >= :from AND e.startsAt < :to",
        countQuery = "SELECT COUNT(e) FROM CalendarEvent e WHERE e.orgId = :orgId AND e.startsAt >= :from AND e.startsAt < :to",
    )
    fun findWindow(
        @Param("orgId") orgId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
        pageable: Pageable,
    ): Page<CalendarEvent>

    /**
     * 식수예측용 **휴일 후보** 조회([from, to)) — all-day 이면서 피드 유형이 HOLIDAY 이거나 이벤트 category 가
     * HOLIDAY 인 이벤트만. 판정 근거는 [com.taspa.server.forecast.HolidayCalendar] 참조(요약 텍스트로 의미를
     * 추측하지 않는다). feedId 는 FK 가 아니라 원시 UUID 라 연관 대신 서브쿼리로 피드 유형을 본다.
     * 행 수 상한은 Pageable 로 강제한다(호출부가 상한 도달을 fail-loud 처리).
     */
    @Query(
        """
        SELECT e FROM CalendarEvent e
        WHERE e.orgId = :orgId
          AND e.allDay = true
          AND e.startsAt >= :from AND e.startsAt < :to
          AND (
            UPPER(e.category) = 'HOLIDAY'
            OR e.feedId IN (SELECT f.id FROM CalendarFeed f WHERE f.orgId = :orgId AND f.type = 'HOLIDAY')
          )
        """,
    )
    fun findHolidayWindow(
        @Param("orgId") orgId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
        pageable: Pageable,
    ): List<CalendarEvent>
}
