package com.taspa.server.admin

import com.taspa.server.audit.AuditEventService
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.PlatformAction
import com.taspa.server.org.OrgDomainService
import com.taspa.server.org.OrganizationService
import com.taspa.server.org.dto.OrgDomainRegisterRequest
import com.taspa.server.org.dto.OrgDomainSettingsView
import com.taspa.server.org.dto.OrgDomainView
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
 * 자동가입 도메인 플랫폼 관리 API — ADMIN 전용(/api/admin/ 경로 hasRole ADMIN 체인). 콘솔 셀프서비스와
 * 동일한 CRUD + **수동 승인 오버라이드**(force-verify: DNS 자가검증 없이 오프라인 소유 확인으로 승인,
 * unverify: 검증 철회 — 이후 자동 가입 판정에서 즉시 제외). 공용 도메인 차단·전역 유니크는 서비스가
 * 동일하게 강제한다(관리자도 우회 불가). 변경은 step-up + CSRF 헤더 + org 결속 감사.
 */
@RestController
@RequestMapping("/api/admin/orgs/{orgId}/domains")
class AdminOrgDomainController(
    private val orgDomainService: OrgDomainService,
    private val organizationService: OrganizationService,
    private val userRepository: UserRepository,
    private val auditEventService: AuditEventService,
) {
    @PlatformAction(
        action = IamActions.PLATFORM_LIST_ORG_DOMAINS,
        resource = "trn:taspa:platform:{orgId}:domain/*",
    )
    @GetMapping
    fun list(
        @PathVariable orgId: UUID,
    ): ResponseEntity<OrgDomainSettingsView> {
        val org = organizationService.requireOrg(orgId)
        return ResponseEntity.ok(
            OrgDomainSettingsView(
                autoJoinEnabled = org.autoJoinEnabled,
                domains = orgDomainService.list(orgId),
            ),
        )
    }

    @PlatformAction(
        action = IamActions.PLATFORM_REGISTER_ORG_DOMAIN,
        resource = "trn:taspa:platform:{orgId}:domain/*",
    )
    @RequireRecentAuth
    @PostMapping
    fun register(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestBody request: OrgDomainRegisterRequest,
    ): ResponseEntity<OrgDomainView> {
        val view = orgDomainService.register(orgId, request.domain)
        auditEventService.record(
            "ORG_DOMAIN_ADDED",
            actorId(authentication),
            orgId,
            mapOf("orgId" to orgId.toString(), "domain" to view.domain),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(view)
    }

    @PlatformAction(
        action = IamActions.PLATFORM_VERIFY_ORG_DOMAIN,
        resource = "trn:taspa:platform:{orgId}:domain/{domainId}",
    )
    @RequireRecentAuth
    @PostMapping("/{domainId}/verify")
    fun verify(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable domainId: UUID,
    ): ResponseEntity<OrgDomainView> {
        val result = orgDomainService.verify(orgId, domainId)
        // 실제 전이 시에만 감사(콘솔 verify 와 동일) — 멱등 재호출은 DNS 확인 없이 반환된다.
        if (result.verifiedNow) {
            auditEventService.record(
                "ORG_DOMAIN_VERIFIED",
                actorId(authentication),
                orgId,
                mapOf("orgId" to orgId.toString(), "domain" to result.view.domain, "method" to "dns-txt"),
            )
        }
        return ResponseEntity.ok(result.view)
    }

    // DNS 확인 없이 검증 처리 — 자동가입 보안앵커 우회 경로라 verify 와 같은 action 을 재사용하지 않는다.
    @PlatformAction(
        action = IamActions.PLATFORM_FORCE_VERIFY_ORG_DOMAIN,
        resource = "trn:taspa:platform:{orgId}:domain/{domainId}",
    )
    @RequireRecentAuth
    @PostMapping("/{domainId}/force-verify")
    fun forceVerify(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable domainId: UUID,
    ): ResponseEntity<OrgDomainView> {
        val view = orgDomainService.forceVerify(orgId, domainId)
        auditEventService.record(
            "ORG_DOMAIN_FORCE_VERIFIED",
            actorId(authentication),
            orgId,
            mapOf("orgId" to orgId.toString(), "domain" to view.domain),
        )
        return ResponseEntity.ok(view)
    }

    @PlatformAction(
        action = IamActions.PLATFORM_UNVERIFY_ORG_DOMAIN,
        resource = "trn:taspa:platform:{orgId}:domain/{domainId}",
    )
    @RequireRecentAuth
    @PostMapping("/{domainId}/unverify")
    fun unverify(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable domainId: UUID,
    ): ResponseEntity<OrgDomainView> {
        val view = orgDomainService.unverify(orgId, domainId)
        auditEventService.record(
            "ORG_DOMAIN_UNVERIFIED",
            actorId(authentication),
            orgId,
            mapOf("orgId" to orgId.toString(), "domain" to view.domain),
        )
        return ResponseEntity.ok(view)
    }

    @PlatformAction(
        action = IamActions.PLATFORM_REMOVE_ORG_DOMAIN,
        resource = "trn:taspa:platform:{orgId}:domain/{domainId}",
    )
    @RequireRecentAuth
    @DeleteMapping("/{domainId}")
    fun remove(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable domainId: UUID,
    ): ResponseEntity<Void> {
        val domain = orgDomainService.delete(orgId, domainId)
        auditEventService.record(
            "ORG_DOMAIN_REMOVED",
            actorId(authentication),
            orgId,
            mapOf("orgId" to orgId.toString(), "domain" to domain),
        )
        return ResponseEntity.noContent().build()
    }

    private fun actorId(authentication: Authentication): UUID? = userRepository.findByEmail(authentication.name)?.id
}
