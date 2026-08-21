package com.taspa.server.org

import com.taspa.server.domain.user.User
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.Trn
import com.taspa.server.org.dto.BulkInvitationRequest
import com.taspa.server.org.dto.BulkInvitationResponse
import com.taspa.server.org.dto.InvitationCreateRequest
import com.taspa.server.org.dto.InvitationView
import com.taspa.server.stepup.RequireRecentAuth
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 초대 관리 API(Phase 0) — /api/orgs 이하 전용 리소스 서버 체인. **세션 인증 전용**(위임 베어러는
 * confused-deputy 방지를 위해 authorize() 가 거부한다). 인가는 **플랫폼 ADMIN 또는 해당 org 의 활성
 * ORG_ADMIN 만**이며, **타 org 초대/열람/취소 불가**다. 생성/취소는 민감 작업이라 step-up(@RequireRecentAuth)
 * 을 요구한다 — auth_time 신선도가 없으면 REAUTH_REQUIRED(401)로 거절되고, 콘솔 JS 가 /reauth 로 유도한다.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/invitations")
class OrgInvitationController(
    private val orgInvitationService: OrgInvitationService,
    private val orgBulkInvitationService: OrgBulkInvitationService,
    private val orgSessionAuthorizer: OrgSessionAuthorizer,
) {
    @RequireRecentAuth
    @PostMapping
    fun create(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestBody request: InvitationCreateRequest,
    ): ResponseEntity<InvitationView> {
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_CREATE_INVITATION,
                Trn.invitations(orgId),
                "OrgInvitationController.create",
            )
        val view =
            orgInvitationService.invite(
                orgId,
                request.email,
                request.role,
                request.department,
                actor.id,
                request.departmentId,
                mayGrantOrgAdmin = mayGrantOrgAdmin(authentication, orgId),
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(view)
    }

    /**
     * CSV 대량 초대 — 각 행이 invite() 를 그대로 타므로 단건 생성과 동일한 보안 속성(상한·쿨다운·
     * 열거 저항)이 적용되고, 행별 실패는 REJECTED 결과로 보고된다(부분 성공 허용). 인가·step-up 은
     * 단건 생성과 동일하다.
     */
    @RequireRecentAuth
    @PostMapping("/bulk")
    fun bulk(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestBody request: BulkInvitationRequest,
    ): ResponseEntity<BulkInvitationResponse> {
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_BULK_INVITE,
                Trn.invitations(orgId),
                "OrgInvitationController.bulk",
            )
        return ResponseEntity.ok(
            orgBulkInvitationService.bulkInvite(
                orgId,
                request.csv,
                actor.id,
                mayGrantOrgAdmin = mayGrantOrgAdmin(authentication, orgId),
            ),
        )
    }

    @GetMapping
    fun list(
        authentication: Authentication,
        @PathVariable orgId: UUID,
    ): ResponseEntity<List<InvitationView>> {
        authorize(
            authentication,
            orgId,
            IamActions.ORG_LIST_INVITATIONS,
            Trn.invitations(orgId),
            "OrgInvitationController.list",
        )
        return ResponseEntity.ok(orgInvitationService.listPending(orgId))
    }

    @RequireRecentAuth
    @PostMapping("/{invitationId}/resend")
    fun resend(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable invitationId: UUID,
    ): ResponseEntity<InvitationView> {
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_RESEND_INVITATION,
                Trn.invitation(orgId, invitationId),
                "OrgInvitationController.resend",
            )
        val view = orgInvitationService.resend(orgId, invitationId, actor.id)
        return ResponseEntity.ok(view)
    }

    @RequireRecentAuth
    @DeleteMapping("/{invitationId}")
    fun revoke(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable invitationId: UUID,
    ): ResponseEntity<Void> {
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_REVOKE_INVITATION,
                Trn.invitation(orgId, invitationId),
                "OrgInvitationController.revoke",
            )
        orgInvitationService.revoke(orgId, invitationId, actor.id)
        return ResponseEntity.noContent().build()
    }

    /**
     * ORG_ADMIN 으로 **초대할 수 있는가** — 역할 변경과 같은 능력을 요구한다.
     *
     * ★이 검사가 없던 동안, 커스텀 역할에 '초대 보내기'(`org:CreateInvitation`)만 준 사람이 자기가
     * 통제하는 두 번째 주소를 ORG_ADMIN 으로 초대해 **조직 전체 권한을 스스로 획득**할 수 있었다.
     * 자기증식 3종(`org:ManageRoles`·`ChangeMemberRole`·`ManageDelegation`)을 부여 목록에서 뺀 것이
     * 초대 경로로 우회된 셈이다. 부서 위임(V34)이 같은 이유로 초대 action 을 명시 Deny 했다.
     */
    private fun mayGrantOrgAdmin(
        authentication: Authentication,
        orgId: UUID,
    ): Boolean =
        orgSessionAuthorizer.permits(
            authentication,
            orgId,
            IamActions.ORG_CHANGE_MEMBER_ROLE,
            Trn.members(orgId),
            "OrgInvitationController.mayGrantOrgAdmin",
            OrgConsoleSurface.INVITATION,
        )

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
    ): User = orgSessionAuthorizer.authorize(authentication, orgId, action, resource, label, OrgConsoleSurface.INVITATION)
}
