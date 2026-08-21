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
 * 정규화된 캘린더 이벤트(Phase 0-E). VEVENT 하나(또는 반복 occurrence 하나)당 한 행.
 * upsert 키 = (feedId, uid, startsAt). 반복 occurrence 는 uid 동일·startsAt 상이로 구분된다.
 */
@Entity
@Table(name = "calendar_events")
class CalendarEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "org_id", nullable = false)
    val orgId: UUID,
    @Column(name = "feed_id")
    val feedId: UUID? = null,
    @Column(name = "uid", nullable = false, length = 512)
    var uid: String,
    @Column(name = "summary", length = 500)
    var summary: String? = null,
    @Column(name = "category", length = 64)
    var category: String? = null,
    @Column(name = "starts_at", nullable = false)
    var startsAt: Instant,
    @Column(name = "ends_at")
    var endsAt: Instant? = null,
    @Column(name = "all_day", nullable = false)
    var allDay: Boolean = false,
    @Column(name = "source", nullable = false, length = 16)
    var source: String,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
