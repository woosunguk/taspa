package com.taspa.server.org

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.Trn
import com.taspa.server.org.dto.GrantableActionView
import com.taspa.server.org.dto.OrgRoleAssignRequest
import com.taspa.server.org.dto.OrgRoleDetailView
import com.taspa.server.org.dto.OrgRoleView
import com.taspa.server.org.dto.SaveOrgRoleRequest
import com.taspa.server.stepup.RequireRecentAuth
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 조직 커스텀 역할 API — 자율 콘솔(세션 인증 전용). 인가·감사·step-up 규약은 다른 org 콘솔 표면과 같다:
 * **위임 베어러 거부 + (플랫폼 ADMIN ∨ 해당 org 활성 ORG_ADMIN)**, 상태변경은 `@RequireRecentAuth`.
 *
 * ★역할 생성·수정은 **권한을 만드는 행위**라 조직 안에서 가장 민감하다. 그래서 `org:ManageRoles` 는
 * 커스텀 역할에 **부여할 수 없는 action**(`ROLE_NON_GRANTABLE_ACTIONS`)이다 — 그러지 않으면 한 번
 * 부여된 역할이 새 역할을 만들며 스스로 자란다.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/roles")
class OrgRoleController(
    private val orgRoleService: OrgRoleService,
    private val orgSessionAuthorizer: OrgSessionAuthorizer,
) {
    @GetMapping
    fun list(
        authentication: Authentication,
        @PathVariable orgId: UUID,
    ): ResponseEntity<List<OrgRoleView>> {
        authorize(authentication, orgId, IamActions.ORG_LIST_ROLES, Trn.roles(orgId), "OrgRoleController.list")
        return ResponseEntity.ok(orgRoleService.list(orgId))
    }

    /**
     * 부여 가능한 능력 카탈로그. 화면이 체크박스를 그릴 때 쓰는 **유일한 출처**다 —
     * 프런트가 목록을 따로 들고 있으면 서버에 action 이 추가돼도 화면에는 영영 안 나타난다.
     */
    @GetMapping("/grantable-actions")
    fun grantableActions(
        authentication: Authentication,
        @PathVariable orgId: UUID,
    ): ResponseEntity<List<GrantableActionView>> {
        authorize(authentication, orgId, IamActions.ORG_LIST_ROLES, Trn.roles(orgId), "OrgRoleController.grantable")
        return ResponseEntity.ok(orgRoleService.grantableActions().map { GrantableActionView(it, groupOf(it), labelOf(it)) })
    }

    @GetMapping("/{roleId}")
    fun detail(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable roleId: UUID,
    ): ResponseEntity<OrgRoleDetailView> {
        authorize(authentication, orgId, IamActions.ORG_LIST_ROLES, Trn.role(orgId, roleId), "OrgRoleController.detail")
        return ResponseEntity.ok(orgRoleService.detail(orgId, roleId))
    }

    @RequireRecentAuth
    @PostMapping
    fun create(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestBody request: SaveOrgRoleRequest,
    ): ResponseEntity<OrgRoleDetailView> {
        val actor = authorize(authentication, orgId, IamActions.ORG_MANAGE_ROLES, Trn.roles(orgId), "OrgRoleController.create")
        return ResponseEntity.ok(orgRoleService.create(orgId, request, actor))
    }

    @RequireRecentAuth
    @PutMapping("/{roleId}")
    fun update(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable roleId: UUID,
        @RequestBody request: SaveOrgRoleRequest,
    ): ResponseEntity<OrgRoleDetailView> {
        val actor = authorize(authentication, orgId, IamActions.ORG_MANAGE_ROLES, Trn.role(orgId, roleId), "OrgRoleController.update")
        return ResponseEntity.ok(orgRoleService.update(orgId, roleId, request, actor))
    }

    @RequireRecentAuth
    @DeleteMapping("/{roleId}")
    fun delete(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable roleId: UUID,
    ): ResponseEntity<Void> {
        val actor = authorize(authentication, orgId, IamActions.ORG_MANAGE_ROLES, Trn.role(orgId, roleId), "OrgRoleController.delete")
        orgRoleService.delete(orgId, roleId, actor)
        return ResponseEntity.noContent().build()
    }

    @RequireRecentAuth
    @PostMapping("/{roleId}/members")
    fun assign(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable roleId: UUID,
        @RequestBody request: OrgRoleAssignRequest,
    ): ResponseEntity<OrgRoleDetailView> {
        val actor = authorize(authentication, orgId, IamActions.ORG_MANAGE_ROLES, Trn.role(orgId, roleId), "OrgRoleController.assign")
        val userId = request.userId ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "userId 가 필요합니다")
        orgRoleService.assign(orgId, roleId, userId, actor)
        return ResponseEntity.ok(orgRoleService.detail(orgId, roleId))
    }

    @RequireRecentAuth
    @DeleteMapping("/{roleId}/members/{userId}")
    fun unassign(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable roleId: UUID,
        @PathVariable userId: UUID,
    ): ResponseEntity<OrgRoleDetailView> {
        val actor = authorize(authentication, orgId, IamActions.ORG_MANAGE_ROLES, Trn.role(orgId, roleId), "OrgRoleController.unassign")
        orgRoleService.unassign(orgId, roleId, userId, actor)
        return ResponseEntity.ok(orgRoleService.detail(orgId, roleId))
    }

    // ---- 내부 ----

    private fun authorize(
        authentication: Authentication,
        orgId: UUID,
        action: String,
        resource: String,
        label: String,
    ): UUID =
        orgSessionAuthorizer
            .authorize(authentication, orgId, action, resource, label, OrgConsoleSurface.ROLE)
            .id!!

    /** action 을 화면 묶음으로 분류 — `service:Action` 의 service 부분을 그대로 쓴다(새 action 도 자동 분류). */
    private fun groupOf(action: String): String =
        when (action.substringBefore(":")) {
            "org" -> "조직"
            "meal" -> "식대"
            "billing" -> "정산"
            "forecast" -> "예측"
            else -> "기타"
        }

    /**
     * 사람이 읽는 라벨. 목록에 없으면 action 문자열을 그대로 보여준다 — **감추지 않는다**.
     * 라벨이 없다고 항목을 빼면 서버가 부여 가능하다고 한 능력이 화면에서만 사라진다.
     */
    private fun labelOf(action: String): String = LABELS[action] ?: action

    private companion object {
        val LABELS: Map<String, String> =
            mapOf(
                IamActions.ORG_LIST_MEMBERS to "구성원 조회",
                IamActions.ORG_REMOVE_MEMBER to "구성원 제거",
                IamActions.ORG_ASSIGN_MEMBER to "부서·사업장 배정",
                IamActions.ORG_UPDATE_MEMBER_ATTRIBUTES to "인사 속성 편집",
                IamActions.ORG_READ_MEMBER_HISTORY to "구성원 이력 조회",
                IamActions.ORG_CREATE_INVITATION to "초대 보내기",
                IamActions.ORG_LIST_INVITATIONS to "초대 목록 조회",
                IamActions.ORG_RESEND_INVITATION to "초대 재발송",
                IamActions.ORG_REVOKE_INVITATION to "초대 취소",
                IamActions.ORG_BULK_INVITE to "CSV 대량 초대",
                IamActions.ORG_LIST_DOMAINS to "도메인 조회",
                IamActions.ORG_REGISTER_DOMAIN to "도메인 등록",
                IamActions.ORG_VERIFY_DOMAIN to "도메인 검증",
                IamActions.ORG_REMOVE_DOMAIN to "도메인 삭제",
                IamActions.ORG_CONFIGURE_AUTO_JOIN to "자동 가입 설정",
                IamActions.ORG_LIST_DEPARTMENTS to "부서 조회",
                IamActions.ORG_CREATE_DEPARTMENT to "부서 생성",
                IamActions.ORG_UPDATE_DEPARTMENT to "부서 수정",
                IamActions.ORG_DELETE_DEPARTMENT to "부서 삭제",
                IamActions.ORG_LIST_SITES to "사업장 조회",
                IamActions.ORG_CREATE_SITE to "사업장 생성",
                IamActions.ORG_UPDATE_SITE to "사업장 수정",
                IamActions.ORG_DELETE_SITE to "사업장 삭제",
                IamActions.ORG_UPDATE_PROFILE to "조직 프로필 수정",
                IamActions.ORG_READ_AUDIT to "활동 로그 조회",
                IamActions.ORG_READ_DASHBOARD to "대시보드 조회",
                IamActions.ORG_LIST_ROLES to "역할 조회",
                IamActions.ORG_LIST_DELEGATIONS to "부서 위임 조회",
                IamActions.MEAL_READ_POLICY to "식대 정책 조회",
                IamActions.MEAL_UPDATE_POLICY to "식대 정책 수정",
                IamActions.MEAL_READ_POLICY_HISTORY to "식대 정책 이력 조회",
                IamActions.MEAL_READ_POLICY_OVERRIDES to "부서·사업장 재정의 조회",
                IamActions.MEAL_MANAGE_POLICY_OVERRIDES to "부서·사업장 재정의 편집",
                IamActions.BILLING_GENERATE_INVOICE to "청구서 생성",
                IamActions.BILLING_READ_INVOICE to "청구서 조회",
                IamActions.BILLING_FINALIZE_INVOICE to "청구서 확정",
                IamActions.BILLING_READ_SPEND to "지출 현황 조회",
                IamActions.BILLING_RECONCILE to "정합성 대사",
                IamActions.FORECAST_READ to "식수 예측 조회",
                IamActions.FORECAST_BACKTEST to "예측 백테스트",
            )
    }
}
