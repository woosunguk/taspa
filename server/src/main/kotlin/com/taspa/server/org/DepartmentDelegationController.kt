package com.taspa.server.org

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.Trn
import com.taspa.server.org.dto.DepartmentDelegationRequest
import com.taspa.server.org.dto.DepartmentDelegationView
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
 * 부서 서브트리 위임 관리 — **조직관리자 전용**.
 *
 * 위임자에게는 `org:ManageDelegation` 이 명시 Deny 로 닫혀 있다. 위임자가 위임을 줄 수 있으면
 * 자기 부하에게 자기 부서를 재위임하고 그 사람이 다시… 로 경계가 무한 증식하기 때문이다.
 *
 * 리소스 TRN 에 부서 앵커를 넣지 않는다(`Trn.delegations(org)`) — 이건 조직 전체 설정이지 특정
 * 부서의 자원이 아니고, 앵커가 없으면 위임 정책의 양성 조건이 미적용돼 위임자는 자연히 거부된다.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/delegations")
class DepartmentDelegationController(
    private val delegationService: DepartmentDelegationService,
    private val auditEventService: AuditEventService,
    private val orgSessionAuthorizer: OrgSessionAuthorizer,
) {
    @GetMapping
    fun list(
        authentication: Authentication,
        @PathVariable orgId: UUID,
    ): ResponseEntity<List<DepartmentDelegationView>> {
        orgSessionAuthorizer.authorize(
            authentication,
            orgId,
            IamActions.ORG_LIST_DELEGATIONS,
            Trn.delegations(orgId),
            "DepartmentDelegationController.list",
            OrgConsoleSurface.MEMBER,
        )
        return ResponseEntity.ok(delegationService.list(orgId))
    }

    @RequireRecentAuth
    @PostMapping
    fun grant(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestBody request: DepartmentDelegationRequest,
    ): ResponseEntity<DepartmentDelegationView> {
        val actor =
            orgSessionAuthorizer.authorize(
                authentication,
                orgId,
                IamActions.ORG_MANAGE_DELEGATION,
                Trn.delegations(orgId),
                "DepartmentDelegationController.grant",
                OrgConsoleSurface.MEMBER,
            )
        val userId = request.userId ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "userId 가 필요합니다")
        val departmentId =
            request.departmentId
                ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "departmentId 가 필요합니다")

        val view = delegationService.grant(orgId, userId, departmentId, actor.id)
        auditEventService.record(
            "ORG_DELEGATION_GRANTED",
            actor.id,
            orgId,
            mapOf(
                "orgId" to orgId.toString(),
                "userId" to userId.toString(),
                "departmentId" to departmentId.toString(),
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(view)
    }

    @RequireRecentAuth
    @DeleteMapping("/{userId}")
    fun revoke(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable userId: UUID,
    ): ResponseEntity<Void> {
        val actor =
            orgSessionAuthorizer.authorize(
                authentication,
                orgId,
                IamActions.ORG_MANAGE_DELEGATION,
                Trn.delegations(orgId),
                "DepartmentDelegationController.revoke",
                OrgConsoleSurface.MEMBER,
            )
        delegationService.revoke(orgId, userId)
        auditEventService.record(
            "ORG_DELEGATION_REVOKED",
            actor.id,
            orgId,
            mapOf("orgId" to orgId.toString(), "userId" to userId.toString()),
        )
        return ResponseEntity.noContent().build()
    }
}
