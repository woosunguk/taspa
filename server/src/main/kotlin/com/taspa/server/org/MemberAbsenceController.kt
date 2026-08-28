package com.taspa.server.org

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.Trn
import com.taspa.server.org.dto.AbsenceDaySummary
import com.taspa.server.org.dto.AbsenceUpsertRequest
import com.taspa.server.org.dto.AbsenceUpsertResponse
import com.taspa.server.org.dto.AbsenceView
import com.taspa.server.stepup.RequireRecentAuth
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * 구성원 부재(연차·반차·출장·병가) API — `/api/orgs` 세션 체인.
 *
 * 인사 정보라 조회조차 조직관리자·위임 역할에 한정한다(다른 org 표면과 같은 단일 인가 진입점).
 * 변경은 step-up + CSRF + org 결속 감사 — 부재 기록은 예측 모수를 직접 바꾸고, 예측은 발주로 이어진다.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/absences")
class MemberAbsenceController(
    private val absenceService: MemberAbsenceService,
    private val auditEventService: AuditEventService,
    private val orgSessionAuthorizer: OrgSessionAuthorizer,
) {
    @GetMapping
    fun list(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestParam from: String,
        @RequestParam to: String,
    ): ResponseEntity<List<AbsenceView>> {
        authorize(authentication, orgId, IamActions.ORG_LIST_ABSENCES, "list")
        return ResponseEntity.ok(absenceService.list(orgId, parseDate(from, "from"), parseDate(to, "to")))
    }

    @GetMapping("/summary")
    fun summary(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestParam from: String,
        @RequestParam to: String,
    ): ResponseEntity<List<AbsenceDaySummary>> {
        authorize(authentication, orgId, IamActions.ORG_LIST_ABSENCES, "summary")
        return ResponseEntity.ok(absenceService.summary(orgId, parseDate(from, "from"), parseDate(to, "to")))
    }

    @RequireRecentAuth
    @PostMapping
    fun upsert(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestBody request: AbsenceUpsertRequest,
    ): ResponseEntity<AbsenceUpsertResponse> {
        val actor = authorize(authentication, orgId, IamActions.ORG_MANAGE_ABSENCES, "upsert")
        val result = absenceService.upsert(orgId, request)
        auditEventService.record(
            "ORG_ABSENCE_UPSERTED",
            actor.id,
            orgId,
            mapOf(
                "targetUserId" to request.userId.toString(),
                "from" to request.from.toString(),
                "to" to (request.to ?: request.from).toString(),
                "type" to request.type.name,
                "created" to result.created,
                "updated" to result.updated,
            ),
        )
        return ResponseEntity.ok(result)
    }

    @RequireRecentAuth
    @DeleteMapping("/{userId}/{date}")
    fun delete(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable userId: UUID,
        @PathVariable date: String,
    ): ResponseEntity<Void> {
        val actor = authorize(authentication, orgId, IamActions.ORG_MANAGE_ABSENCES, "delete")
        val day = parseDate(date, "date")
        absenceService.delete(orgId, userId, day)
        auditEventService.record(
            "ORG_ABSENCE_DELETED",
            actor.id,
            orgId,
            mapOf("targetUserId" to userId.toString(), "date" to day.toString()),
        )
        return ResponseEntity.noContent().build()
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
        Trn.orgAbsences(orgId),
        "MemberAbsenceController.$label",
        OrgConsoleSurface.ABSENCE,
    )

    private fun parseDate(
        raw: String,
        field: String,
    ): LocalDate =
        try {
            LocalDate.parse(raw.trim())
        } catch (e: DateTimeParseException) {
            throw AuthException(ErrorCode.VALIDATION_ERROR, "$field 는 YYYY-MM-DD 형식이어야 합니다")
        }
}
