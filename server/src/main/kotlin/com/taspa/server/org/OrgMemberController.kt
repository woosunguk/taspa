package com.taspa.server.org

import com.taspa.server.audit.AuditEventService
import com.taspa.server.domain.org.OrgRole
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRole
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.Trn
import com.taspa.server.org.dto.MemberAssignmentRequest
import com.taspa.server.org.dto.MemberAttributesRequest
import com.taspa.server.org.dto.MembershipHistoryView
import com.taspa.server.org.dto.MembershipRoleRequest
import com.taspa.server.org.dto.MembershipView
import com.taspa.server.stepup.RequireRecentAuth
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 자율 콘솔용 멤버 관리 API — /api/orgs 이하 전용 리소스 서버 체인(세션 인증 전용). ORG_ADMIN 이
 * 플랫폼 ADMIN 없이 자기 조직의 멤버를 열람·역할변경·제거한다. 인가는 OrgInvitationController 와
 * 동일하게 **위임 베어러 거부 + (플랫폼 ADMIN ∨ 해당 org 활성 ORG_ADMIN)** 이며, 타 org 접근은 403 이다.
 * 변경(역할·제거)은 민감 작업이라 step-up(@RequireRecentAuth) 을 요구한다. 마지막 ORG_ADMIN 강등/제거는
 * OrganizationService 의 guardLastAdmin 이 차단한다(조직 잠금 방지) — 예외는 그대로 전파한다.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/members")
class OrgMemberController(
    private val organizationService: OrganizationService,
    private val auditEventService: AuditEventService,
    private val orgSessionAuthorizer: OrgSessionAuthorizer,
    private val departmentPathService: DepartmentPathService,
    private val delegationService: DepartmentDelegationService,
) {
    @GetMapping
    fun list(
        authentication: Authentication,
        @PathVariable orgId: UUID,
    ): ResponseEntity<List<MembershipView>> {
        // 목록은 대상이 여럿이라 리소스에 부서 앵커를 넣을 수 없다. 그래서 컬렉션 TRN 에 **부서 위임자의
        // 부서 경로**를 스코프로 실어 준다 — 위임자는 자기 서브트리를 근거로 통과하고, 응답은 아래에서
        // 그 서브트리로 좁혀진다. 인가와 응답 범위가 같은 근거를 쓰는 것이 중요하다.
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_LIST_MEMBERS,
                members(orgId),
                "OrgMemberController.list",
                scope = delegationScopeOf(authentication, orgId),
            )
        // ★응답 범위는 **통과의 근거**를 따라간다. 위임자가 나중에 플랫폼 관리자나 조직관리자로
        // 승격되면 위임 행은 남아 있지만 통과 근거는 더 넓은 쪽이다 — 그때도 위임으로 좁히면
        // 관리자가 **잘린 명단을 전체로 오인**한다(화면에 아무 표시도 없다). 넓은 근거가 있으면
        // 위임을 아예 조회하지 않는다.
        //   (부여 시점에는 상호배제가 막지만, "위임 부여 → 이후 플랫폼 승격" 순서는 막히지 않는다.
        //    플랫폼 역할은 org 밖에서 바뀌므로 detachOnPromotion 이 닿지 않는다.)
        val broaderBasis =
            actor.role == UserRole.ADMIN.name ||
                (actor.id?.let { organizationService.isOrgAdmin(orgId, it) } ?: false)
        val visible =
            if (broaderBasis) {
                null
            } else {
                delegationService
                    .delegatedDepartmentOf(orgId, actor.id)
                    ?.let { departmentPathService.subtreeIds(orgId, it) }
            }
        return ResponseEntity.ok(organizationService.listMembers(orgId, visible))
    }

    @RequireRecentAuth
    @PutMapping("/{userId}/role")
    fun changeRole(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable userId: UUID,
        @RequestBody request: MembershipRoleRequest,
    ): ResponseEntity<MembershipView> {
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_CHANGE_MEMBER_ROLE,
                Trn.member(orgId, userId),
                "OrgMemberController.changeRole",
                // 대상 멤버의 부서 위치 — 부서 위임자는 자기 서브트리 안에서만 통과한다.
                scope = departmentPathService.scopeOfMember(orgId, userId),
            )
        val view = organizationService.changeRole(orgId, userId, request.role, actor.id)
        // 상호배제 — 전사 권한을 받은 사람의 부분 위임은 의미가 없고, 남겨 두면 "위임을 회수했으니
        // 안전하다"는 오해를 만든다. 승격 직후 같은 요청 안에서 해제한다.
        if (view.role == OrgRole.ORG_ADMIN.name) {
            delegationService.detachOnPromotion(orgId, userId)
        }
        // AdminOrgController 와 동일한 이벤트 타입·detail 키를 재사용해 감사 뷰가 행위자와 무관하게 균일하다
        // (행위자 식별은 actor.id 로 보존). PII 최소화 — userId(UUID)·orgId·새 역할만 담는다.
        auditEventService.record(
            "ADMIN_ORG_MEMBER_ROLE_CHANGED",
            actor.id,
            orgId,
            mapOf("orgId" to orgId.toString(), "userId" to userId.toString(), "role" to view.role),
        )
        return ResponseEntity.ok(view)
    }

    @RequireRecentAuth
    @DeleteMapping("/{userId}")
    fun remove(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable userId: UUID,
    ): ResponseEntity<Void> {
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_REMOVE_MEMBER,
                Trn.member(orgId, userId),
                "OrgMemberController.remove",
            )
        organizationService.removeMember(orgId, userId, actor.id)
        auditEventService.record(
            "ADMIN_ORG_MEMBER_REMOVED",
            actor.id,
            orgId,
            mapOf("orgId" to orgId.toString(), "userId" to userId.toString()),
        )
        return ResponseEntity.noContent().build()
    }

    /**
     * 멤버 구조적 배정(부서·사업장) — full-replace. body 의 departmentId/siteId 가 null 이면 그 배정을 해제한다.
     * 대상 부서·사업장은 그 org 소속일 때만 허용된다(타 org 배정 금지 — OrganizationService.assignMember 격리).
     * 민감 작업이라 step-up + org 결속 audit(ADMIN_ORG_MEMBER_ASSIGNED, deptId·siteId 포함)를 남긴다.
     */
    @RequireRecentAuth
    @PutMapping("/{userId}/assignment")
    fun assign(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable userId: UUID,
        @RequestBody request: MemberAssignmentRequest,
    ): ResponseEntity<MembershipView> {
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_ASSIGN_MEMBER,
                Trn.member(orgId, userId),
                "OrgMemberController.assign",
                // 대상 멤버의 부서 위치 — 부서 위임자는 자기 서브트리 안에서만 통과한다.
                scope = departmentPathService.scopeOfMember(orgId, userId),
            )
        // 엔진이 표현할 수 없는 두 가드(대상 자원이 둘이거나, 주체와 대상이 같은 경우).
        delegationService.requireNotSelfWhenDelegated(orgId, actor.id, userId)
        delegationService.requireDestinationInScope(orgId, actor.id, request.departmentId)
        val view = organizationService.assignMember(orgId, userId, request.departmentId, request.siteId, actor.id)
        auditEventService.record(
            "ADMIN_ORG_MEMBER_ASSIGNED",
            actor.id,
            orgId,
            mapOf(
                "orgId" to orgId.toString(),
                "userId" to userId.toString(),
                "departmentId" to request.departmentId?.toString(),
                "siteId" to request.siteId?.toString(),
            ),
        )
        return ResponseEntity.ok(view)
    }

    /**
     * 임직원 HR 속성 갱신(사번·직함·고용형태·입사일·재직상태) — full-replace. 대상은 그 org 멤버여야 한다
     * (비멤버 → 404; 멤버십 status 는 강제하지 않음). enum/날짜 검증 실패는 400. 민감 작업이라 step-up + org 결속 audit 를 남긴다.
     * 라우팅: /{userId}/attributes 는 /{userId}/role·/assignment 와 충돌하지 않는다(서로 다른 하위 경로).
     */
    @RequireRecentAuth
    @PutMapping("/{userId}/attributes")
    fun updateAttributes(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable userId: UUID,
        @RequestBody request: MemberAttributesRequest,
    ): ResponseEntity<MembershipView> {
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_UPDATE_MEMBER_ATTRIBUTES,
                Trn.member(orgId, userId),
                "OrgMemberController.updateAttributes",
                // 대상 멤버의 부서 위치 — 부서 위임자는 자기 서브트리 안에서만 통과한다.
                scope = departmentPathService.scopeOfMember(orgId, userId),
            )
        delegationService.requireNotSelfWhenDelegated(orgId, actor.id, userId)
        val view = organizationService.updateAttributes(orgId, userId, request, actor.id)
        auditEventService.record(
            "ADMIN_ORG_MEMBER_ATTRS_UPDATED",
            actor.id,
            orgId,
            // PII 최소화 — 값 자체는 담지 않고 어떤 필드가 세팅됐는지(존재 여부)만 감사에 남긴다.
            mapOf(
                "orgId" to orgId.toString(),
                "userId" to userId.toString(),
                "employmentStatus" to view.employmentStatus,
                "employmentType" to view.employmentType,
            ),
        )
        return ResponseEntity.ok(view)
    }

    /**
     * 멤버십 변경 이력(SCD) 조회 — 최신순. 조회이므로 step-up 불요. 이력 뷰는 멤버십 상태 스냅샷만 담고
     * user_sub·email 등 민감정보는 노출하지 않는다. org 격리는 orgId 조건이 강제한다(타 org 이력 미노출).
     */
    @GetMapping("/{userId}/history")
    fun history(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable userId: UUID,
    ): ResponseEntity<List<MembershipHistoryView>> {
        authorize(
            authentication,
            orgId,
            IamActions.ORG_READ_MEMBER_HISTORY,
            Trn.member(orgId, userId),
            "OrgMemberController.history",
            // 대상 멤버의 부서 위치 — 부서 위임자는 자기 서브트리 안에서만 통과한다.
            scope = departmentPathService.scopeOfMember(orgId, userId),
        )
        return ResponseEntity.ok(organizationService.listMembershipHistory(orgId, userId))
    }

    /**
     * 목록 요청의 스코프 — 호출자가 위임자면 **자기 부서 경로**, 아니면 [ResourceScope.NONE].
     *
     * 자기 부서를 스코프로 넣는 것이 자기 권한을 넓히는 게 아닌 이유: 위임 정책의 글롭은
     * "이 경로에 내 부서가 들어 있는가"를 보는데, 여기 들어가는 경로는 **DB 에 저장된 자기 위임 부서**에서
     * 계산된 값이다. 남의 부서를 지어내도 그 부서로 위임받지 않았으므로 조건이 통과하지 않는다.
     */
    private fun delegationScopeOf(
        authentication: Authentication,
        orgId: UUID,
    ): com.taspa.server.iam.ResourceScope {
        val userId = orgSessionAuthorizer.resolveUserId(authentication) ?: return com.taspa.server.iam.ResourceScope.NONE
        val delegated = delegationService.delegatedDepartmentOf(orgId, userId) ?: return com.taspa.server.iam.ResourceScope.NONE
        return departmentPathService.scopeOfDepartment(orgId, delegated)
    }

    /** 멤버 컬렉션 TRN — 단건 대상이 없는 목록 조회용(Trn.invitations 등과 동일한 와일드카드 규약). */
    private fun members(orgId: UUID): String = Trn.build(Trn.SERVICE_ORG, orgId, "member", "*")

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
        scope: com.taspa.server.iam.ResourceScope = com.taspa.server.iam.ResourceScope.NONE,
    ): User =
        orgSessionAuthorizer.authorize(
            authentication,
            orgId,
            action,
            resource,
            label,
            OrgConsoleSurface.MEMBER,
            scope,
        )
}
