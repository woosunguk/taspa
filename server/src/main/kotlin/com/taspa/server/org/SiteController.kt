package com.taspa.server.org

import com.taspa.server.audit.AuditEventService
import com.taspa.server.domain.user.User
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.Trn
import com.taspa.server.org.dto.SiteCreateRequest
import com.taspa.server.org.dto.SiteUpdateRequest
import com.taspa.server.org.dto.SiteView
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
 * 사업장(구내식당) 관리 API — /api/orgs 이하 세션 인증 전용 체인. 인가는 DepartmentController 와 동일
 * (베어러 거부 + 플랫폼 ADMIN ∨ 활성 ORG_ADMIN, 타 org 403). 변경은 step-up + org 결속 audit(ADMIN_ORG_SITE_*).
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/sites")
class SiteController(
    private val siteService: SiteService,
    private val auditEventService: AuditEventService,
    private val orgSessionAuthorizer: OrgSessionAuthorizer,
) {
    @GetMapping
    fun list(
        authentication: Authentication,
        @PathVariable orgId: UUID,
    ): ResponseEntity<List<SiteView>> {
        authorize(authentication, orgId, IamActions.ORG_LIST_SITES, Trn.sites(orgId), "SiteController.list")
        return ResponseEntity.ok(siteService.list(orgId))
    }

    @RequireRecentAuth
    @PostMapping
    fun create(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestBody request: SiteCreateRequest,
    ): ResponseEntity<SiteView> {
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_CREATE_SITE,
                Trn.sites(orgId),
                "SiteController.create",
            )
        val view = siteService.create(orgId, request.name, request.address, request.timezone)
        auditEventService.record(
            "ADMIN_ORG_SITE_CREATED",
            actor.id,
            orgId,
            mapOf("orgId" to orgId.toString(), "siteId" to view.id.toString(), "name" to view.name),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(view)
    }

    @RequireRecentAuth
    @PutMapping("/{siteId}")
    fun update(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable siteId: UUID,
        @RequestBody request: SiteUpdateRequest,
    ): ResponseEntity<SiteView> {
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_UPDATE_SITE,
                Trn.site(orgId, siteId),
                "SiteController.update",
            )
        val view = siteService.update(orgId, siteId, request.name, request.address, request.timezone)
        auditEventService.record(
            "ADMIN_ORG_SITE_UPDATED",
            actor.id,
            orgId,
            mapOf("orgId" to orgId.toString(), "siteId" to siteId.toString(), "name" to view.name),
        )
        return ResponseEntity.ok(view)
    }

    @RequireRecentAuth
    @DeleteMapping("/{siteId}")
    fun delete(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable siteId: UUID,
    ): ResponseEntity<Void> {
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_DELETE_SITE,
                Trn.site(orgId, siteId),
                "SiteController.delete",
            )
        siteService.delete(orgId, siteId)
        auditEventService.record(
            "ADMIN_ORG_SITE_DELETED",
            actor.id,
            orgId,
            mapOf("orgId" to orgId.toString(), "siteId" to siteId.toString()),
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
    ): User = orgSessionAuthorizer.authorize(authentication, orgId, action, resource, label, OrgConsoleSurface.SITE)
}
