package com.taspa.server.domain.calendar

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 조직 캘린더 피드(Phase 0-E). sourceUrl 이 있으면 구독형(주기 동기화 대상), NULL 이면 업로드형(.ics 본문 POST).
 * type 은 이벤트 카테고리 파생용(HOLIDAY | WORK | EVENT).
 */
@Entity
@Table(name = "calendar_feeds")
class CalendarFeed(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "org_id", nullable = false)
    val orgId: UUID,
    @Column(name = "name", nullable = false, length = 120)
    var name: String,
    @Column(name = "type", nullable = false, length = 16)
    var type: String,
    @Column(name = "source_url", length = 1024)
    var sourceUrl: String? = null,
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,
    @Column(name = "last_synced_at")
    var lastSyncedAt: Instant? = null,
    @Column(name = "last_sync_status", length = 16)
    var lastSyncStatus: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) {
    fun typeEnum(): CalendarFeedType = CalendarFeedType.valueOf(type)

    fun isSubscription(): Boolean = !sourceUrl.isNullOrBlank()
}
