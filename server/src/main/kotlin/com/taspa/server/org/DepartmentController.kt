package com.taspa.server.org

import com.taspa.server.audit.AuditEventService
import com.taspa.server.domain.user.User
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.Trn
import com.taspa.server.org.dto.DepartmentCreateRequest
import com.taspa.server.org.dto.DepartmentRenameRequest
import com.taspa.server.org.dto.DepartmentView
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
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 계층형 부서(조직도) 관리 API — /api/orgs 이하 세션 인증 전용 체인. 인가는 OrgMemberController.authorize 와
 * 동일(위임 베어러 거부 + 플랫폼 ADMIN ∨ 해당 org 활성 ORG_ADMIN, 타 org 403). 조회는 authorize 만,
 * 변경(생성·이름변경·삭제)은 step-up(@RequireRecentAuth) + org 결속 audit(ADMIN_ORG_DEPARTMENT_*)를 남긴다.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/departments")
class DepartmentController(
    private val departmentService: DepartmentService,
    private val auditEventService: AuditEventService,
    private val orgSessionAuthorizer: OrgSessionAuthorizer,
) {
    @GetMapping
    fun list(
        authentication: Authentication,
        @PathVariable orgId: UUID,
    ): ResponseEntity<List<DepartmentView>> {
        authorize(
            authentication,
            orgId,
            IamActions.ORG_LIST_DEPARTMENTS,
            Trn.departments(orgId),
            "DepartmentController.list",
        )
        return ResponseEntity.ok(departmentService.listTree(orgId))
    }

    @RequireRecentAuth
    @PostMapping
    fun create(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestBody request: DepartmentCreateRequest,
    ): ResponseEntity<DepartmentView> {
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_CREATE_DEPARTMENT,
                Trn.departments(orgId),
                "DepartmentController.create",
            )
        val view = departmentService.create(orgId, request.name, request.parentId)
        auditEventService.record(
            "ADMIN_ORG_DEPARTMENT_CREATED",
            actor.id,
            orgId,
            mapOf(
                "orgId" to orgId.toString(),
                "departmentId" to view.id.toString(),
                "parentId" to view.parentId?.toString(),
                "name" to view.name,
            ),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(view)
    }

    @RequireRecentAuth
    @PutMapping("/{deptId}")
    fun rename(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable deptId: UUID,
        @RequestBody request: DepartmentRenameRequest,
    ): ResponseEntity<DepartmentView> {
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_UPDATE_DEPARTMENT,
                Trn.department(orgId, deptId),
                "DepartmentController.rename",
            )
        val view = departmentService.rename(orgId, deptId, request.name)
        auditEventService.record(
            "ADMIN_ORG_DEPARTMENT_RENAMED",
            actor.id,
            orgId,
            mapOf("orgId" to orgId.toString(), "departmentId" to deptId.toString(), "name" to view.name),
        )
        return ResponseEntity.ok(view)
    }

    @RequireRecentAuth
    @DeleteMapping("/{deptId}")
    fun delete(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable deptId: UUID,
    ): ResponseEntity<Void> {
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_DELETE_DEPARTMENT,
                Trn.department(orgId, deptId),
                "DepartmentController.delete",
            )
        departmentService.delete(orgId, deptId)
        auditEventService.record(
            "ADMIN_ORG_DEPARTMENT_DELETED",
            actor.id,
            orgId,
            mapOf("orgId" to orgId.toString(), "departmentId" to deptId.toString()),
        )
        return ResponseEntity.noContent().build()
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
    ): User = orgSessionAuthorizer.authorize(authentication, orgId, action, resource, label, OrgConsoleSurface.DEPARTMENT)
}
