package com.taspa.server.admin

import com.taspa.server.audit.AuditEventService
import com.taspa.server.calendar.CalendarService
import com.taspa.server.calendar.dto.CalendarEventPage
import com.taspa.server.calendar.dto.FeedCreateRequest
import com.taspa.server.calendar.dto.FeedUpdateRequest
import com.taspa.server.calendar.dto.FeedView
import com.taspa.server.calendar.dto.SyncResultView
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.PlatformAction
import com.taspa.server.stepup.RequireRecentAuth
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 조직 캘린더 피드 관리 API(Phase 0-E) — ADMIN 전용(SecurityConfig 의 /api/admin/ 경로 hasRole ADMIN),
 * 변경 작업은 step-up(@RequireRecentAuth) + CSRF 헤더 필수. 피드 등록·수동 동기화·.ics 업로드 제공.
 * org 격리는 CalendarService 가 orgId 스코프로 강제한다.
 */
@RestController
@RequestMapping("/api/admin/orgs/{orgId}/calendar")
class AdminCalendarController(
    private val calendarService: CalendarService,
    private val auditEventService: AuditEventService,
    private val userRepository: UserRepository,
) {
    @PlatformAction(
        action = IamActions.PLATFORM_LIST_CALENDAR_FEEDS,
        resource = "trn:taspa:platform:{orgId}:calendar-feed/*",
    )
    @GetMapping("/feeds")
    fun listFeeds(
        @PathVariable orgId: UUID,
    ): ResponseEntity<List<FeedView>> = ResponseEntity.ok(calendarService.listFeeds(orgId))

    @PlatformAction(
        action = IamActions.PLATFORM_CREATE_CALENDAR_FEED,
        resource = "trn:taspa:platform:{orgId}:calendar-feed/*",
    )
    @RequireRecentAuth
    @PostMapping("/feeds")
    fun createFeed(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestBody request: FeedCreateRequest,
    ): ResponseEntity<FeedView> {
        val view = calendarService.createFeed(orgId, request)
        auditEventService.record(
            "ADMIN_CALENDAR_FEED_CREATED",
            actorId(authentication),
            mapOf("orgId" to orgId.toString(), "feedId" to view.id.toString(), "subscription" to view.subscription),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(view)
    }

    /** 피드 활성 토글(관리 콘솔). enabled 를 명시해야 한다. */
    @PlatformAction(
        action = IamActions.PLATFORM_UPDATE_CALENDAR_FEED,
        resource = "trn:taspa:platform:{orgId}:calendar-feed/{feedId}",
    )
    @RequireRecentAuth
    @PatchMapping("/feeds/{feedId}")
    fun updateFeed(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable feedId: UUID,
        @RequestBody request: FeedUpdateRequest,
    ): ResponseEntity<FeedView> {
        val enabled =
            request.enabled
                ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "enabled 를 지정하세요")
        val view = calendarService.setFeedEnabled(orgId, feedId, enabled)
        auditEventService.record(
            "ADMIN_CALENDAR_FEED_UPDATED",
            actorId(authentication),
            mapOf("orgId" to orgId.toString(), "feedId" to feedId.toString(), "enabled" to enabled),
        )
        return ResponseEntity.ok(view)
    }

    /** 피드 삭제(관리 콘솔). 소속 이벤트는 CASCADE 로 함께 삭제된다. */
    @PlatformAction(
        action = IamActions.PLATFORM_DELETE_CALENDAR_FEED,
        resource = "trn:taspa:platform:{orgId}:calendar-feed/{feedId}",
    )
    @RequireRecentAuth
    @DeleteMapping("/feeds/{feedId}")
    fun deleteFeed(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable feedId: UUID,
    ): ResponseEntity<Void> {
        calendarService.deleteFeed(orgId, feedId)
        auditEventService.record(
            "ADMIN_CALENDAR_FEED_DELETED",
            actorId(authentication),
            mapOf("orgId" to orgId.toString(), "feedId" to feedId.toString()),
        )
        return ResponseEntity.noContent().build()
    }

    /** 피드 최근 이벤트 미리보기(관리 콘솔, 페이징). 읽기 전용이라 step-up 없이 조회한다. */
    // calendar:ReadEvents 는 ORG_MEMBER_ACTIONS 라 일반 직원 전원이 가진다 — 관리 표면 전용 action 을 쓴다.
    @PlatformAction(
        action = IamActions.PLATFORM_READ_CALENDAR_FEED_EVENTS,
        resource = "trn:taspa:platform:{orgId}:calendar-feed/{feedId}",
    )
    @GetMapping("/feeds/{feedId}/events")
    fun feedEvents(
        @PathVariable orgId: UUID,
        @PathVariable feedId: UUID,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false) size: Int?,
    ): ResponseEntity<CalendarEventPage> = ResponseEntity.ok(calendarService.listFeedEvents(orgId, feedId, page, size))

    @PlatformAction(
        action = IamActions.PLATFORM_SYNC_CALENDAR_FEED,
        resource = "trn:taspa:platform:{orgId}:calendar-feed/{feedId}",
    )
    @RequireRecentAuth
    @PostMapping("/feeds/{feedId}/sync")
    fun sync(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable feedId: UUID,
    ): ResponseEntity<SyncResultView> {
        val result = calendarService.syncFeed(orgId, feedId)
        auditEventService.record(
            "ADMIN_CALENDAR_FEED_SYNCED",
            actorId(authentication),
            mapOf("orgId" to orgId.toString(), "feedId" to feedId.toString(), "status" to result.status),
        )
        return ResponseEntity.ok(result)
    }

    @PlatformAction(
        action = IamActions.PLATFORM_IMPORT_CALENDAR_FEED,
        resource = "trn:taspa:platform:{orgId}:calendar-feed/{feedId}",
    )
    @RequireRecentAuth
    @PostMapping("/feeds/{feedId}/import", consumes = ["text/calendar", "text/plain", "application/octet-stream"])
    fun import(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable feedId: UUID,
        @RequestBody icsBody: String,
    ): ResponseEntity<SyncResultView> {
        val result = calendarService.importUpload(orgId, feedId, icsBody)
        auditEventService.record(
            "ADMIN_CALENDAR_UPLOADED",
            actorId(authentication),
            mapOf("orgId" to orgId.toString(), "feedId" to feedId.toString(), "imported" to result.imported),
        )
        return ResponseEntity.ok(result)
    }

    private fun actorId(authentication: Authentication): UUID =
        userRepository.findByEmail(authentication.name)?.id
            ?: throw AuthException(ErrorCode.USER_NOT_FOUND)
}
