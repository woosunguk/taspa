package com.taspa.server.org

import com.taspa.server.domain.user.User
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.Trn
import com.taspa.server.org.dto.OrgDashboardView
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 조직 개요 대시보드 API — /api/orgs 세션 체인(@Order(2)). 읽기전용 집계라 step-up 은 불요하고,
 * 인가는 멤버 관리 API 와 동일하게 **위임 베어러 거부 + (플랫폼 ADMIN ∨ 해당 org 활성 ORG_ADMIN)**,
 * 타 org 는 403 이다. 응답은 집계 카운트만 담는다(개별 멤버·이메일 미노출 — PII 최소화).
 *
 * 라우팅: /api/orgs/{orgId}/dashboard 는 기존 하위 경로(members·invitations·departments·sites·audit·
 * consumption-events)와 충돌하지 않고, 정확 경로(/mine·/memberships)보다 뒤에 매칭돼 오인이 없다.
 */
@RestController
class OrgDashboardController(
    private val orgDashboardService: OrgDashboardService,
    private val orgSessionAuthorizer: OrgSessionAuthorizer,
) {
    @GetMapping("/api/orgs/{orgId}/dashboard")
    fun dashboard(
        authentication: Authentication,
        @PathVariable orgId: UUID,
    ): ResponseEntity<OrgDashboardView> {
        authorize(
            authentication,
            orgId,
            IamActions.ORG_READ_DASHBOARD,
            Trn.dashboard(orgId),
            "OrgDashboardController.dashboard",
        )
        return ResponseEntity.ok(orgDashboardService.dashboard(orgId))
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
    ): User = orgSessionAuthorizer.authorize(authentication, orgId, action, resource, label, OrgConsoleSurface.DASHBOARD)
}
