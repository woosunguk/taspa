package com.taspa.server.calendar.dto

import com.taspa.server.domain.calendar.CalendarEvent
import com.taspa.server.domain.calendar.CalendarFeed
import java.time.Instant
import java.util.UUID

/** 피드 생성 요청. sourceUrl 이 있으면 구독형, 없으면 업로드형. */
data class FeedCreateRequest(
    val name: String = "",
    val type: String = "",
    val sourceUrl: String? = null,
)

/** 피드 부분 수정 요청(관리 콘솔). 현재는 활성 토글만 — null 이면 변경하지 않는다. */
data class FeedUpdateRequest(
    val enabled: Boolean? = null,
)

data class FeedView(
    val id: UUID,
    val orgId: UUID,
    val name: String,
    val type: String,
    val sourceUrl: String?,
    val subscription: Boolean,
    val enabled: Boolean,
    val lastSyncedAt: Instant?,
    val lastSyncStatus: String?,
    val createdAt: Instant,
) {
    companion object {
        fun from(f: CalendarFeed) =
            FeedView(
                id = f.id!!,
                orgId = f.orgId,
                name = f.name,
                type = f.type,
                sourceUrl = f.sourceUrl,
                subscription = f.isSubscription(),
                enabled = f.enabled,
                lastSyncedAt = f.lastSyncedAt,
                lastSyncStatus = f.lastSyncStatus,
                createdAt = f.createdAt,
            )
    }
}

/** 동기화/업로드 결과 요약. */
data class SyncResultView(
    val feedId: UUID,
    val status: String,
    val imported: Int,
)

data class CalendarEventView(
    val id: UUID,
    val uid: String,
    val summary: String?,
    val category: String?,
    val startsAt: Instant,
    val endsAt: Instant?,
    val allDay: Boolean,
    val source: String,
) {
    companion object {
        fun from(e: CalendarEvent) =
            CalendarEventView(
                id = e.id!!,
                uid = e.uid,
                summary = e.summary,
                category = e.category,
                startsAt = e.startsAt,
                endsAt = e.endsAt,
                allDay = e.allDay,
                source = e.source,
            )
    }
}

/**
 * 이벤트 조회 응답(페이징). items = 현재 페이지, total = 윈도우 전체 건수, hasNext = 다음 페이지 존재 여부.
 * 무제한 리스트 반환을 막고(윈도우 상한 + 행 수 상한) 소비자가 커서 없이 순회할 수 있게 메타를 노출한다.
 */
data class CalendarEventPage(
    val items: List<CalendarEventView>,
    val page: Int,
    val size: Int,
    val total: Long,
    val hasNext: Boolean,
)
