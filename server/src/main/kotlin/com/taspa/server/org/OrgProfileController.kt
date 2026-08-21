package com.taspa.server.org

import com.taspa.server.audit.AuditEventService
import com.taspa.server.domain.user.User
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.Trn
import com.taspa.server.org.dto.OrgProfileRequest
import com.taspa.server.org.dto.OrgView
import com.taspa.server.stepup.RequireRecentAuth
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 조직 프로필 자율 편집 API — ORG_ADMIN 이 플랫폼 ADMIN 없이 **자기 조직의 이름·타임존만** 수정한다.
 * 인가는 OrgMemberController 와 동일: 위임 베어러 거부 + (플랫폼 ADMIN ∨ 해당 org 활성 ORG_ADMIN),
 * 타 org 는 403. 변경이므로 step-up(@RequireRecentAuth) + CSRF(전용 /api/orgs 체인)를 요구한다.
 *
 * 불변식: 이 엔드포인트로는 status·slug 를 절대 바꿀 수 없다 — OrgProfileRequest 에 그 필드가 없고
 * OrganizationService.updateProfile 이 name·timezone 만 반영한다(정지 해제·slug 탈취는 플랫폼 관리자 전용).
 *
 * 경로 주의: PUT /api/orgs/{orgId} 는 GET /api/orgs/mine(고정 경로)·/api/orgs/{orgId}/members 등
 * 하위 경로와 충돌하지 않는다(메서드·세그먼트 수 상이).
 */
@RestController
@RequestMapping("/api/orgs")
class OrgProfileController(
    private val organizationService: OrganizationService,
    private val auditEventService: AuditEventService,
    private val orgSessionAuthorizer: OrgSessionAuthorizer,
) {
    @RequireRecentAuth
    @PutMapping("/{orgId}")
    fun updateProfile(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestBody request: OrgProfileRequest,
    ): ResponseEntity<OrgView> {
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_UPDATE_PROFILE,
                Trn.organization(orgId),
                "OrgProfileController.updateProfile",
            )
        val view = organizationService.updateProfile(orgId, request.name, request.timezone)
        // 플랫폼 ADMIN 경로(AdminOrgController.update)와 동일 이벤트 타입·orgId 로 감사 뷰를 균일하게 유지한다.
        // status 는 이 경로로 불변이므로 detail 에 담지 않는다(변경된 필드만 기록).
        auditEventService.record(
            "ADMIN_ORG_UPDATED",
            actor.id,
            orgId,
            mapOf("orgId" to orgId.toString(), "name" to view.name, "timezone" to view.timezone),
        )
        return ResponseEntity.ok(view)
    }

    /**
     * 인가 위임 — 판정은 전적으로 [OrgSessionAuthorizer](→ 정책 엔진)가 내린다. 여기에는 "누가 통과하는가"
     * 규칙이 하나도 없고 표면 식별자만 고정한다(거부 문구 선택용). 베어러 하드 게이트도 authorizer 안에 있다.
     */
    private fun authorize(
        authentication: Authentication,
        orgId: UUID,
        action: String,
        resource: String,
        label: String,
    ): User = orgSessionAuthorizer.authorize(authentication, orgId, action, resource, label, OrgConsoleSurface.PROFILE)
}
