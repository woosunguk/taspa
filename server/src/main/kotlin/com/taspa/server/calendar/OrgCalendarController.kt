package com.taspa.server.calendar

import com.taspa.server.audit.AuditEventService
import com.taspa.server.calendar.dto.CalendarEventPage
import com.taspa.server.calendar.dto.FeedCreateRequest
import com.taspa.server.calendar.dto.FeedUpdateRequest
import com.taspa.server.calendar.dto.FeedView
import com.taspa.server.calendar.dto.SyncResultView
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.Trn
import com.taspa.server.org.OrgConsoleSurface
import com.taspa.server.org.OrgSessionAuthorizer
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
 * 조직 캘린더 **피드 관리** API — `/api/orgs` 세션 체인(조직관리자 자율).
 *
 * 그전까지 피드 등록은 플랫폼 ADMIN(`/api/admin/orgs/{org}/calendar`) 전용이었다. 그래서 휴일·사내 행사
 * 신호를 쓰려면 조직이 매번 플랫폼 운영자에게 요청해야 했고, 실제로는 **아무 조직도 캘린더를 갖지 않았다** —
 * 예측의 휴일 인지가 코드에는 있는데 데이터가 없어 항상 비활성이었다는 뜻이다.
 *
 * ★**행사·휴일은 iCalendar(RFC 5545) 표준으로만 들어온다** — 자체 이벤트 CRUD 를 만들지 않았다.
 * 근거: (1) 조직의 일정은 이미 Google Workspace·Outlook·사내 그룹웨어에 있고 그것들이 공통으로 내보내는
 * 형식이 .ics 다. 우리 화면에 손으로 다시 입력하게 하면 두 곳이 갈라지고, 갈라진 순간 예측은 **틀린 쪽**을
 * 믿는다. (2) 반복 규칙(RRULE)·연휴(DTEND 배타)·종일 이벤트의 의미를 이미 [IcalendarParser] 가 표준대로
 * 해석한다 — 자체 모델을 만들면 그 의미론을 다시 구현해야 하고 미묘하게 달라진다.
 *
 * 입력 경로는 두 가지다(둘 다 표준):
 *  - **구독**: 캘린더의 공개 .ics URL 을 등록하면 [CalendarSyncJob] 이 주기적으로 동기화한다.
 *    URL 은 [IcsUrlSecurity] 의 SSRF 방어를 통과해야 한다.
 *  - **업로드**: .ics 본문을 그대로 POST 한다(내부망 캘린더·수동 관리 조직).
 *
 * 인가는 다른 org 콘솔 표면과 같은 단일 진입점, 변경은 step-up + CSRF + org 결속 감사.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/calendar")
class OrgCalendarController(
    private val calendarService: CalendarService,
    private val auditEventService: AuditEventService,
    private val orgSessionAuthorizer: OrgSessionAuthorizer,
) {
    @GetMapping("/feeds")
    fun listFeeds(
        authentication: Authentication,
        @PathVariable orgId: UUID,
    ): ResponseEntity<List<FeedView>> {
        authorize(authentication, orgId, IamActions.CALENDAR_LIST_FEEDS, "listFeeds")
        return ResponseEntity.ok(calendarService.listFeeds(orgId))
    }

    @GetMapping("/feeds/{feedId}/events")
    fun feedEvents(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable feedId: UUID,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false) size: Int?,
    ): ResponseEntity<CalendarEventPage> {
        authorize(authentication, orgId, IamActions.CALENDAR_LIST_FEEDS, "feedEvents")
        return ResponseEntity.ok(calendarService.listFeedEvents(orgId, feedId, page, size))
    }

    @RequireRecentAuth
    @PostMapping("/feeds")
    fun createFeed(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestBody request: FeedCreateRequest,
    ): ResponseEntity<FeedView> {
        val actor = authorize(authentication, orgId, IamActions.CALENDAR_MANAGE_FEEDS, "createFeed")
        val view = calendarService.createFeed(orgId, request)
        audit(
            actor.id,
            orgId,
            "ORG_CALENDAR_FEED_CREATED",
            mapOf(
                "feedId" to view.id.toString(),
                "type" to view.type,
                "subscription" to view.subscription,
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(view)
    }

    @RequireRecentAuth
    @PatchMapping("/feeds/{feedId}")
    fun updateFeed(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable feedId: UUID,
        @RequestBody request: FeedUpdateRequest,
    ): ResponseEntity<FeedView> {
        val actor = authorize(authentication, orgId, IamActions.CALENDAR_MANAGE_FEEDS, "updateFeed")
        val enabled =
            request.enabled
                ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "enabled 를 명시해야 합니다")
        val view = calendarService.setFeedEnabled(orgId, feedId, enabled)
        audit(actor.id, orgId, "ORG_CALENDAR_FEED_UPDATED", mapOf("feedId" to feedId.toString(), "enabled" to enabled))
        return ResponseEntity.ok(view)
    }

    @RequireRecentAuth
    @DeleteMapping("/feeds/{feedId}")
    fun deleteFeed(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable feedId: UUID,
    ): ResponseEntity<Void> {
        val actor = authorize(authentication, orgId, IamActions.CALENDAR_MANAGE_FEEDS, "deleteFeed")
        calendarService.deleteFeed(orgId, feedId)
        audit(actor.id, orgId, "ORG_CALENDAR_FEED_DELETED", mapOf("feedId" to feedId.toString()))
        return ResponseEntity.noContent().build()
    }

    @RequireRecentAuth
    @PostMapping("/feeds/{feedId}/sync")
    fun sync(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable feedId: UUID,
    ): ResponseEntity<SyncResultView> {
        val actor = authorize(authentication, orgId, IamActions.CALENDAR_MANAGE_FEEDS, "sync")
        val result = calendarService.syncFeed(orgId, feedId)
        audit(
            actor.id,
            orgId,
            "ORG_CALENDAR_FEED_SYNCED",
            mapOf(
                "feedId" to feedId.toString(),
                "status" to result.status,
                "imported" to result.imported,
            ),
        )
        return ResponseEntity.ok(result)
    }

    /** .ics 본문 업로드(RFC 5545). Content-Type 은 표준 `text/calendar` 를 우선한다. */
    @RequireRecentAuth
    @PostMapping("/feeds/{feedId}/import", consumes = ["text/calendar", "text/plain", "application/octet-stream"])
    fun import(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable feedId: UUID,
        @RequestBody icsBody: String,
    ): ResponseEntity<SyncResultView> {
        val actor = authorize(authentication, orgId, IamActions.CALENDAR_MANAGE_FEEDS, "import")
        val result = calendarService.importUpload(orgId, feedId, icsBody)
        audit(
            actor.id,
            orgId,
            "ORG_CALENDAR_FEED_IMPORTED",
            mapOf(
                "feedId" to feedId.toString(),
                "status" to result.status,
                "imported" to result.imported,
            ),
        )
        return ResponseEntity.ok(result)
    }

    private fun authorize(
        authentication: Authentication,
        orgId: UUID,
        action: String,
        label: String,
    ) = orgSessionAuthorizer.authorize(
        authentication,
        orgId,
        action,
        Trn.calendarFeeds(orgId),
        "OrgCalendarController.$label",
        OrgConsoleSurface.CALENDAR,
    )

    private fun audit(
        actorId: UUID?,
        orgId: UUID,
        type: String,
        detail: Map<String, Any?>,
    ) = auditEventService.record(type, actorId, orgId, detail)
}
