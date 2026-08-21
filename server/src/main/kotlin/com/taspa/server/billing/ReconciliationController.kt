package com.taspa.server.billing

import com.taspa.server.billing.dto.ReconciliationReport
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserRole
import com.taspa.server.iam.AuthorizationRequest
import com.taspa.server.iam.IamActions
import com.taspa.server.iam.IamAuthorizationService
import com.taspa.server.iam.IamContextFactory
import com.taspa.server.iam.IamPrincipalKind
import com.taspa.server.iam.Trn
import com.taspa.server.org.OrganizationService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 3-way 대사 조회 — 원장·장부·소비이벤트가 같은 사실을 말하는지.
 *
 * 읽기 전용이라 step-up 을 걸지 않는다(OrgSpendController 와 같은 판단 — 대시보드를 열 때마다
 * 재인증을 요구하면 화면이 쓰이지 않는다). 인가 계약도 동일하다: 위임 베어러 거부 +
 * (플랫폼 ADMIN ∨ 그 org 활성 ORG_ADMIN), 타 org 403.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}/reconciliation")
class ReconciliationController(
    private val reconciliationService: ReconciliationService,
    private val organizationService: OrganizationService,
    private val userRepository: UserRepository,
    private val iamAuthorizationService: IamAuthorizationService,
    private val iamContextFactory: IamContextFactory,
) {
    /** @param period 'YYYY-MM'(org 타임존 달력). 생략하면 org 타임존 기준 이번 달. */
    @GetMapping
    fun reconcile(
        authentication: Authentication,
        @PathVariable orgId: UUID,
        @RequestParam(required = false) period: String?,
    ): ResponseEntity<ReconciliationReport> {
        authorize(authentication, orgId)
        val org = organizationService.requireOrg(orgId)
        val target = period?.takeIf { it.isNotBlank() } ?: reconciliationService.currentPeriod(org.timezone)
        return ResponseEntity.ok(reconciliationService.reconcile(orgId, target))
    }

    private fun authorize(
        authentication: Authentication,
        orgId: UUID,
    ) {
        if (authentication is JwtAuthenticationToken) {
            throw AuthException(ErrorCode.FORBIDDEN, "대사 조회는 세션 인증에서만 가능합니다")
        }
        val user = userRepository.findByEmail(authentication.name) ?: throw AuthException(ErrorCode.FORBIDDEN)
        val userId = user.id ?: throw AuthException(ErrorCode.FORBIDDEN)
        val platformAdmin = user.role == UserRole.ADMIN.name
        val orgAdmin = !platformAdmin && organizationService.isOrgAdmin(orgId, userId)

        val resource = Trn.build(Trn.SERVICE_BILLING, orgId, "reconciliation")
        val request =
            AuthorizationRequest(
                IamActions.BILLING_RECONCILE,
                resource,
                iamContextFactory.build(IamPrincipalKind.SESSION, orgId),
            )
        val allowed =
            iamAuthorizationService.authorize(request, "ReconciliationController.reconcile") {
                iamAuthorizationService.decideSession(
                    userId = userId,
                    platformAdmin = platformAdmin,
                    orgAdminOf = if (orgAdmin) orgId else null,
                    memberOf = if (orgAdmin) orgId else null,
                    request = request,
                )
            }
        if (!allowed) throw AuthException(ErrorCode.FORBIDDEN)
    }
}
