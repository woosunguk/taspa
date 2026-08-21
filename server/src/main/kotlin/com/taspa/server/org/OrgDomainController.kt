package com.taspa.server.org

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.user.User
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.Trn
import com.taspa.server.org.dto.AutoJoinToggleRequest
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 자율 콘솔용 자동가입 도메인 관리 API — /api/orgs 세션 체인. ORG_ADMIN 이 자기 조직의 도메인을
 * 등록(공용 도메인 차단·전역 중복 409)·DNS TXT 자가검증·삭제하고, opt-in 토글을 켠다/끈다.
 * 인가는 OrgMemberController.authorize 와 동일: 위임 베어러 거부 + (플랫폼 ADMIN ∨ 그 org 활성
 * ORG_ADMIN), 타 org 403. 변경은 전부 step-up(@RequireRecentAuth) + CSRF + org 결속 감사.
 * GET 목록은 verificationToken(TXT 안내)을 포함하지만 자기 org 조회에서만 내려간다(인가가 격리).
 */
@RestController
@RequestMapping("/api/orgs/{orgId}")
class OrgDomainController(
    private val orgDomainService: OrgDomainService,
    private val organizationService: OrganizationService,
    private val auditEventService: AuditEventService,
    private val orgSessionAuthorizer: OrgSessionAuthorizer,
) {
    @GetMapping("/domains")
    fun list(
        authentication: Authentication,
        @PathVariable orgId: UUID,
    ): ResponseEntity<OrgDomainSettingsView> {
        authorize(
            authentication,
            orgId,
            IamActions.ORG_LIST_DOMAINS,
            Trn.orgDomains(orgId),
            "OrgDomainController.list",
        )
        val org = organizationService.requireOrg(orgId)
        return ResponseEntity.ok(
            OrgDomainSettingsView(
                autoJoinEnabled = org.autoJoinEnabled,
                domains = orgDomainService.list(orgId),
            ),
        )
    }

    @RequireRecentAuth
    @PostMapping("/domains")
    fun register(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestBody request: OrgDomainRegisterRequest,
    ): ResponseEntity<OrgDomainView> {
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_REGISTER_DOMAIN,
                Trn.orgDomains(orgId),
                "OrgDomainController.register",
            )
        val view = orgDomainService.register(orgId, request.domain)
        auditEventService.record(
            "ORG_DOMAIN_ADDED",
            actor.id,
            orgId,
            mapOf("orgId" to orgId.toString(), "domain" to view.domain),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(view)
    }

    @RequireRecentAuth
    @PostMapping("/domains/{domainId}/verify")
    fun verify(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable domainId: UUID,
    ): ResponseEntity<OrgDomainView> {
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_VERIFY_DOMAIN,
                Trn.orgDomain(orgId, domainId),
                "OrgDomainController.verify",
            )
        val result = orgDomainService.verify(orgId, domainId)
        // 실제 전이가 일어난 호출만 감사 — 이미 검증된 행의 멱등 재호출은 DNS 확인이 없으므로
        // ORG_DOMAIN_VERIFIED 를 반복 기록하면 검증 시각·횟수 판정이 오염된다.
        if (result.verifiedNow) {
            auditEventService.record(
                "ORG_DOMAIN_VERIFIED",
                actor.id,
                orgId,
                mapOf("orgId" to orgId.toString(), "domain" to result.view.domain, "method" to "dns-txt"),
            )
        }
        return ResponseEntity.ok(result.view)
    }

    @RequireRecentAuth
    @DeleteMapping("/domains/{domainId}")
    fun remove(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @PathVariable domainId: UUID,
    ): ResponseEntity<Void> {
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_REMOVE_DOMAIN,
                Trn.orgDomain(orgId, domainId),
                "OrgDomainController.remove",
            )
        val domain = orgDomainService.delete(orgId, domainId)
        auditEventService.record(
            "ORG_DOMAIN_REMOVED",
            actor.id,
            orgId,
            mapOf("orgId" to orgId.toString(), "domain" to domain),
        )
        return ResponseEntity.noContent().build()
    }

    /**
     * opt-in 토글 전용 엔드포인트 — updateProfile(PUT /api/orgs/{orgId})에 섞지 않는다
     * (status·slug 불변 불변식과 동형: 프로필 저장이 자동가입 정책을 건드릴 수 없다).
     */
    @RequireRecentAuth
    @PutMapping("/auto-join")
    fun toggleAutoJoin(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestBody request: AutoJoinToggleRequest,
    ): ResponseEntity<Map<String, Boolean>> {
        val actor =
            authorize(
                authentication,
                orgId,
                IamActions.ORG_CONFIGURE_AUTO_JOIN,
                Trn.organization(orgId),
                "OrgDomainController.toggleAutoJoin",
            )
        val enabled =
            request.enabled
                ?: throw AuthException(ErrorCode.VALIDATION_ERROR, "enabled 값이 필요합니다")
        orgDomainService.setAutoJoinEnabled(orgId, enabled)
        auditEventService.record(
            "ORG_AUTO_JOIN_TOGGLED",
            actor.id,
            orgId,
            mapOf("orgId" to orgId.toString(), "enabled" to enabled),
        )
        return ResponseEntity.ok(mapOf("autoJoinEnabled" to enabled))
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
    ): User = orgSessionAuthorizer.authorize(authentication, orgId, action, resource, label, OrgConsoleSurface.DOMAIN)
}
