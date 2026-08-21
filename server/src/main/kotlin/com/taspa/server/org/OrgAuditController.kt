package com.taspa.server.org

import com.taspa.server.domain.user.User
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.Trn
import com.taspa.server.org.dto.OrgAuditEventView
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 조직 스코프 활동로그 조회 API — 자율 콘솔용. ORG_ADMIN 이 플랫폼 ADMIN 없이 자기 조직의 감사 이벤트
 * (멤버 역할변경/제거, 초대 생성/수락/취소/재발송, 조직 업데이트 등)를 열람한다. 인가는 멤버/초대 관리
 * API 와 동일하게 **위임 베어러 거부 + (플랫폼 ADMIN ∨ 해당 org 활성 ORG_ADMIN)** 이며, 타 org 접근은 403.
 * 읽기 전용이라 step-up 은 걸지 않는다(관리자 audit 과 동일). org 격리는 OrgAuditService 의 org_id 정확
 * 일치가 강제한다 — 절대 타 org·전역 이벤트를 반환하지 않는다.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/audit")
class OrgAuditController(
    private val orgAuditService: OrgAuditService,
    private val orgSessionAuthorizer: OrgSessionAuthorizer,
) {
    @GetMapping
    fun list(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): ResponseEntity<List<OrgAuditEventView>> {
        authorize(authentication, orgId, IamActions.ORG_READ_AUDIT, Trn.audit(orgId), "OrgAuditController.list")
        return ResponseEntity.ok(orgAuditService.listForOrg(orgId, limit, offset))
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
    ): User = orgSessionAuthorizer.authorize(authentication, orgId, action, resource, label, OrgConsoleSurface.AUDIT)
}
