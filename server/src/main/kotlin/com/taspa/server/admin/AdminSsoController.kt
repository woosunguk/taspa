package com.taspa.server.admin

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.enterprise.SsoConnectionService
import com.taspa.server.enterprise.dto.SsoConnectionRequest
import com.taspa.server.enterprise.dto.SsoConnectionView
import com.taspa.server.enterprise.dto.SsoDomainVerifyRequest
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.PlatformAction
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

/** 기업 SSO 커넥션 관리 API — ADMIN 전용(SecurityConfig), 변경 작업은 step-up + CSRF 헤더 필수. */
@RestController
@RequestMapping("/api/admin/sso")
class AdminSsoController(
    private val ssoConnectionService: SsoConnectionService,
    private val auditEventService: AuditEventService,
    private val userRepository: UserRepository,
) {
    @PlatformAction(
        action = IamActions.PLATFORM_LIST_SSO_CONNECTIONS,
        resource = "trn:taspa:platform::sso-connection/*",
    )
    @GetMapping
    fun list(): ResponseEntity<List<SsoConnectionView>> = ResponseEntity.ok(ssoConnectionService.list())

    @PlatformAction(
        action = IamActions.PLATFORM_READ_SSO_CONNECTION,
        resource = "trn:taspa:platform::sso-connection/{id}",
    )
    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<SsoConnectionView> = ResponseEntity.ok(ssoConnectionService.get(id))

    @PlatformAction(
        action = IamActions.PLATFORM_CREATE_SSO_CONNECTION,
        resource = "trn:taspa:platform::sso-connection/*",
    )
    @RequireRecentAuth
    @PostMapping
    fun create(
        authentication: Authentication,
        @RequestBody request: SsoConnectionRequest,
    ): ResponseEntity<SsoConnectionView> {
        val view = ssoConnectionService.create(request)
        audit("ADMIN_SSO_CREATED", authentication, view)
        return ResponseEntity.status(HttpStatus.CREATED).body(view)
    }

    @PlatformAction(
        action = IamActions.PLATFORM_UPDATE_SSO_CONNECTION,
        resource = "trn:taspa:platform::sso-connection/{id}",
    )
    @RequireRecentAuth
    @PutMapping("/{id}")
    fun update(
        authentication: Authentication,
        @PathVariable id: UUID,
        @RequestBody request: SsoConnectionRequest,
    ): ResponseEntity<SsoConnectionView> {
        val view = ssoConnectionService.update(id, request)
        audit("ADMIN_SSO_UPDATED", authentication, view)
        return ResponseEntity.ok(view)
    }

    @PlatformAction(
        action = IamActions.PLATFORM_DELETE_SSO_CONNECTION,
        resource = "trn:taspa:platform::sso-connection/{id}",
    )
    @RequireRecentAuth
    @DeleteMapping("/{id}")
    fun delete(
        authentication: Authentication,
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        val view = ssoConnectionService.get(id)
        ssoConnectionService.delete(id)
        audit("ADMIN_SSO_DELETED", authentication, view)
        return ResponseEntity.noContent().build()
    }

    @PlatformAction(
        action = IamActions.PLATFORM_SET_SSO_DOMAIN_VERIFIED,
        resource = "trn:taspa:platform::sso-connection/{id}",
    )
    @RequireRecentAuth
    @PostMapping("/{id}/domain/verify")
    fun verifyDomain(
        authentication: Authentication,
        @PathVariable id: UUID,
        @RequestBody request: SsoDomainVerifyRequest,
    ): ResponseEntity<SsoConnectionView> {
        ssoConnectionService.setDomainVerified(id, request.domain, request.verified)
        val view = ssoConnectionService.get(id)
        auditEventService.record(
            "ADMIN_SSO_DOMAIN_VERIFIED",
            actorId(authentication),
            mapOf(
                "registrationId" to view.registrationId,
                "domain" to request.domain.trim().lowercase(),
                "verified" to request.verified,
            ),
        )
        return ResponseEntity.ok(view)
    }

    private fun audit(
        type: String,
        authentication: Authentication,
        view: SsoConnectionView,
    ) {
        auditEventService.record(
            type,
            actorId(authentication),
            mapOf("registrationId" to view.registrationId, "protocol" to view.protocol),
        )
    }

    private fun actorId(authentication: Authentication): UUID =
        userRepository.findByEmail(authentication.name)?.id
            ?: throw AuthException(ErrorCode.USER_NOT_FOUND)
}
