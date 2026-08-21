package com.taspa.server.iam

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.iam.IamPrincipalType
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.iam.dto.AttachmentRequest
import com.taspa.server.iam.dto.GroupCreateRequest
import com.taspa.server.iam.dto.GroupMemberRequest
import com.taspa.server.iam.dto.GroupMemberView
import com.taspa.server.iam.dto.GroupView
import com.taspa.server.iam.dto.InlinePolicyRequest
import com.taspa.server.iam.dto.PolicyCreateRequest
import com.taspa.server.iam.dto.PolicyUpdateRequest
import com.taspa.server.iam.dto.PolicyView
import com.taspa.server.iam.dto.PrincipalPolicyView
import com.taspa.server.iam.dto.SimulateRequest
import com.taspa.server.iam.dto.SimulateResponse
import com.taspa.server.stepup.RequireRecentAuth
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * IAM 정책 관리 API — ADMIN 전용(SecurityConfig 의 /api/admin/ 경로 hasRole ADMIN), 변경 작업은
 * step-up(@RequireRecentAuth) + CSRF 헤더 필수. 정책·그룹·부착·inline CRUD 와 정책 시뮬레이터를 제공한다.
 * 모든 상태변경은 감사(ADMIN_IAM_*)로 남긴다.
 */
@RestController
@RequestMapping("/api/admin/iam")
class IamController(
    private val iamPolicyService: IamPolicyService,
    private val auditEventService: AuditEventService,
    private val userRepository: UserRepository,
) {
    // ── 정책 ────────────────────────────────────────────────────────────

    @PlatformAction(
        action = IamActions.IAM_LIST_POLICIES,
        resource = "trn:taspa:iam::policy/*",
    )
    @GetMapping("/policies")
    fun listPolicies(
        @RequestParam(required = false) orgId: UUID?,
    ): ResponseEntity<List<PolicyView>> = ResponseEntity.ok(iamPolicyService.listPolicies(orgId))

    @PlatformAction(
        action = IamActions.IAM_READ_POLICY,
        resource = "trn:taspa:iam::policy/{id}",
    )
    @GetMapping("/policies/{id}")
    fun getPolicy(
        @PathVariable id: UUID,
    ): ResponseEntity<PolicyView> = ResponseEntity.ok(iamPolicyService.getPolicy(id))

    @PlatformAction(
        action = IamActions.IAM_CREATE_POLICY,
        resource = "trn:taspa:iam::policy/*",
    )
    @RequireRecentAuth
    @PostMapping("/policies")
    fun createPolicy(
        authentication: Authentication,
        @RequestBody request: PolicyCreateRequest,
    ): ResponseEntity<PolicyView> {
        val view = iamPolicyService.createPolicy(request)
        auditEventService.record(
            "ADMIN_IAM_POLICY_CREATED",
            actorId(authentication),
            view.orgId,
            mapOf("policyId" to view.id.toString(), "name" to view.name, "orgId" to view.orgId?.toString()),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(view)
    }

    @PlatformAction(
        action = IamActions.IAM_UPDATE_POLICY,
        resource = "trn:taspa:iam::policy/{id}",
    )
    @RequireRecentAuth
    @PutMapping("/policies/{id}")
    fun updatePolicy(
        authentication: Authentication,
        @PathVariable id: UUID,
        @RequestBody request: PolicyUpdateRequest,
    ): ResponseEntity<PolicyView> {
        val view = iamPolicyService.updatePolicy(id, request)
        auditEventService.record(
            "ADMIN_IAM_POLICY_UPDATED",
            actorId(authentication),
            view.orgId,
            mapOf("policyId" to view.id.toString(), "name" to view.name),
        )
        return ResponseEntity.ok(view)
    }

    @PlatformAction(
        action = IamActions.IAM_DELETE_POLICY,
        resource = "trn:taspa:iam::policy/{id}",
    )
    @RequireRecentAuth
    @DeleteMapping("/policies/{id}")
    fun deletePolicy(
        authentication: Authentication,
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        val view = iamPolicyService.deletePolicy(id)
        auditEventService.record(
            "ADMIN_IAM_POLICY_DELETED",
            actorId(authentication),
            view.orgId,
            mapOf("policyId" to view.id.toString(), "name" to view.name),
        )
        return ResponseEntity.noContent().build()
    }

    // ── 그룹 · 멤버십 ─────────────────────────────────────────────────────
    //
    // ★그룹 조작은 핸들러마다 action 이 다르다. 하나로 묶으면("iam:ManageGroup") 생성·삭제·멤버 부여·
    //   멤버 회수를 따로 제어할 방법이 사라지는데, **그룹 멤버 부여는 곧 정책 부여 경로**라(정책이
    //   그룹에 붙는다) 별도 Deny 수요가 가장 높은 지점이다.

    @PlatformAction(
        action = IamActions.IAM_LIST_GROUPS,
        resource = "trn:taspa:iam::group/*",
    )
    @GetMapping("/groups")
    fun listGroups(
        @RequestParam(required = false) orgId: UUID?,
    ): ResponseEntity<List<GroupView>> = ResponseEntity.ok(iamPolicyService.listGroups(orgId))

    @PlatformAction(
        action = IamActions.IAM_CREATE_GROUP,
        resource = "trn:taspa:iam::group/*",
    )
    @RequireRecentAuth
    @PostMapping("/groups")
    fun createGroup(
        authentication: Authentication,
        @RequestBody request: GroupCreateRequest,
    ): ResponseEntity<GroupView> {
        val view = iamPolicyService.createGroup(request)
        auditEventService.record(
            "ADMIN_IAM_GROUP_CREATED",
            actorId(authentication),
            view.orgId,
            mapOf("groupId" to view.id.toString(), "name" to view.name, "orgId" to view.orgId?.toString()),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(view)
    }

    @PlatformAction(
        action = IamActions.IAM_DELETE_GROUP,
        resource = "trn:taspa:iam::group/{id}",
    )
    @RequireRecentAuth
    @DeleteMapping("/groups/{id}")
    fun deleteGroup(
        authentication: Authentication,
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        val view = iamPolicyService.deleteGroup(id)
        auditEventService.record(
            "ADMIN_IAM_GROUP_DELETED",
            actorId(authentication),
            view.orgId,
            mapOf("groupId" to view.id.toString(), "name" to view.name),
        )
        return ResponseEntity.noContent().build()
    }

    @PlatformAction(
        action = IamActions.IAM_LIST_GROUP_MEMBERS,
        resource = "trn:taspa:iam::group/{id}",
    )
    @GetMapping("/groups/{id}/members")
    fun listGroupMembers(
        @PathVariable id: UUID,
    ): ResponseEntity<List<GroupMemberView>> = ResponseEntity.ok(iamPolicyService.listGroupMembers(id))

    @PlatformAction(
        action = IamActions.IAM_ADD_GROUP_MEMBER,
        resource = "trn:taspa:iam::group/{id}",
    )
    @RequireRecentAuth
    @PostMapping("/groups/{id}/members")
    fun addGroupMember(
        authentication: Authentication,
        @PathVariable id: UUID,
        @RequestBody request: GroupMemberRequest,
    ): ResponseEntity<Void> {
        iamPolicyService.addGroupMember(id, request.userId)
        auditEventService.record(
            "ADMIN_IAM_GROUP_MEMBER_ADDED",
            actorId(authentication),
            mapOf("groupId" to id.toString(), "userId" to request.userId.toString()),
        )
        return ResponseEntity.noContent().build()
    }

    @PlatformAction(
        action = IamActions.IAM_REMOVE_GROUP_MEMBER,
        resource = "trn:taspa:iam::group/{id}",
    )
    @RequireRecentAuth
    @DeleteMapping("/groups/{id}/members/{userId}")
    fun removeGroupMember(
        authentication: Authentication,
        @PathVariable id: UUID,
        @PathVariable userId: UUID,
    ): ResponseEntity<Void> {
        iamPolicyService.removeGroupMember(id, userId)
        auditEventService.record(
            "ADMIN_IAM_GROUP_MEMBER_REMOVED",
            actorId(authentication),
            mapOf("groupId" to id.toString(), "userId" to userId.toString()),
        )
        return ResponseEntity.noContent().build()
    }

    // ── 부착 · inline ────────────────────────────────────────────────────

    @PlatformAction(
        action = IamActions.IAM_ATTACH_POLICY,
        resource = "trn:taspa:iam::policy/*",
    )
    @RequireRecentAuth
    @PostMapping("/attachments")
    fun attach(
        authentication: Authentication,
        @RequestBody request: AttachmentRequest,
    ): ResponseEntity<Void> {
        iamPolicyService.attach(request)
        auditEventService.record(
            "ADMIN_IAM_POLICY_ATTACHED",
            actorId(authentication),
            mapOf(
                "policyId" to request.policyId.toString(),
                "principalType" to request.principalType.name,
                "principalId" to request.principalId.toString(),
            ),
        )
        return ResponseEntity.noContent().build()
    }

    @PlatformAction(
        action = IamActions.IAM_DETACH_POLICY,
        resource = "trn:taspa:iam::policy/*",
    )
    @RequireRecentAuth
    @DeleteMapping("/attachments")
    fun detach(
        authentication: Authentication,
        @RequestBody request: AttachmentRequest,
    ): ResponseEntity<Void> {
        iamPolicyService.detach(request)
        auditEventService.record(
            "ADMIN_IAM_POLICY_DETACHED",
            actorId(authentication),
            mapOf(
                "policyId" to request.policyId.toString(),
                "principalType" to request.principalType.name,
                "principalId" to request.principalId.toString(),
            ),
        )
        return ResponseEntity.noContent().build()
    }

    @PlatformAction(
        action = IamActions.IAM_READ_PRINCIPAL_POLICIES,
        resource = "trn:taspa:iam::principal/{type}/{id}",
    )
    @GetMapping("/principals/{type}/{id}/policies")
    fun principalPolicies(
        @PathVariable type: IamPrincipalType,
        @PathVariable id: UUID,
    ): ResponseEntity<List<PrincipalPolicyView>> = ResponseEntity.ok(iamPolicyService.principalPolicies(type, id))

    @PlatformAction(
        action = IamActions.IAM_SET_INLINE_POLICY,
        resource = "trn:taspa:iam::principal/{type}/{id}",
    )
    @RequireRecentAuth
    @PutMapping("/principals/{type}/{id}/inline/{name}")
    fun setInlinePolicy(
        authentication: Authentication,
        @PathVariable type: IamPrincipalType,
        @PathVariable id: UUID,
        @PathVariable name: String,
        @RequestBody request: InlinePolicyRequest,
    ): ResponseEntity<Void> {
        iamPolicyService.setInlinePolicy(type, id, name, request.document)
        auditEventService.record(
            "ADMIN_IAM_INLINE_SET",
            actorId(authentication),
            mapOf("principalType" to type.name, "principalId" to id.toString(), "name" to name),
        )
        return ResponseEntity.noContent().build()
    }

    @PlatformAction(
        action = IamActions.IAM_REMOVE_INLINE_POLICY,
        resource = "trn:taspa:iam::principal/{type}/{id}",
    )
    @RequireRecentAuth
    @DeleteMapping("/principals/{type}/{id}/inline/{name}")
    fun removeInlinePolicy(
        authentication: Authentication,
        @PathVariable type: IamPrincipalType,
        @PathVariable id: UUID,
        @PathVariable name: String,
    ): ResponseEntity<Void> {
        iamPolicyService.removeInlinePolicy(type, id, name)
        auditEventService.record(
            "ADMIN_IAM_INLINE_REMOVED",
            actorId(authentication),
            mapOf("principalType" to type.name, "principalId" to id.toString(), "name" to name),
        )
        return ResponseEntity.noContent().build()
    }

    // ── 시뮬레이터 ─────────────────────────────────────────────────────────

    @PlatformAction(
        action = IamActions.IAM_SIMULATE,
        resource = "trn:taspa:iam::simulation",
    )
    @PostMapping("/simulate")
    fun simulate(
        @RequestBody request: SimulateRequest,
    ): ResponseEntity<SimulateResponse> = ResponseEntity.ok(iamPolicyService.simulate(request))

    private fun actorId(authentication: Authentication): UUID =
        userRepository.findByEmail(authentication.name)?.id
            ?: throw AuthException(ErrorCode.USER_NOT_FOUND)
}
