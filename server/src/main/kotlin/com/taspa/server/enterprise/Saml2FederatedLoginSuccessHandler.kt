package com.taspa.server.enterprise

import com.taspa.server.audit.AuditEventService
import com.taspa.server.credential.AccountLockoutService
import com.taspa.server.domain.sso.SsoConnection
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserStatus
import com.taspa.server.federation.FederationService
import com.taspa.server.federation.SocialAttributes
import com.taspa.server.login.LoginFlowSupport
import com.taspa.server.login.PendingAuthStage
import com.taspa.server.org.OrgAutoJoinService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.core.Authentication
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.stereotype.Component

/**
 * saml2Login 성공 핸들러 — FederatedLoginSuccessHandler(oauth2Login) 미러링.
 *
 * Saml2WebSsoAuthenticationFilter 는 성공 시 세션에 Saml2Authentication 을 먼저 저장한 뒤다.
 * 어떤 분기든 그 토큰을 세션에 남기지 않는다: 실패는 clearSecurityContext, 성공은 로컬 UserDetails
 * 완전 인증(LoginFlowSupport.completeAuthentication)으로 덮어쓴다.
 *
 * 보안 핵심(정책 5): 조직 IdP 가 주장하는 이메일의 도메인이 커넥션의 verified 도메인과 일치할 때만
 * 진행한다 — 조직 IdP 가 타 도메인 이메일을 주장해 남의 계정을 탈취하는 것을 차단한다.
 *
 * SAML 은 소셜과 달리 계정-연결/재인증 의도가 없다(로그인 진입만). 이메일 미제공(도메인 불일치 포함)은
 * 게이트가 아니라 일반 실패로 수렴한다.
 */
@Component
class Saml2FederatedLoginSuccessHandler(
    private val saml2AttributesExtractor: Saml2AttributesExtractor,
    private val ssoConnectionService: SsoConnectionService,
    private val federationService: FederationService,
    private val userRepository: UserRepository,
    private val loginFlowSupport: LoginFlowSupport,
    private val accountLockoutService: AccountLockoutService,
    private val auditEventService: AuditEventService,
    private val orgAutoJoinService: OrgAutoJoinService,
) : AuthenticationSuccessHandler {
    private val log = LoggerFactory.getLogger(Saml2FederatedLoginSuccessHandler::class.java)

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        try {
            handle(request, response, authentication)
        } catch (ex: Exception) {
            log.error("saml login success handling failed", ex)
            if (!response.isCommitted) {
                failSso(request, response)
            }
        }
    }

    private fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        val token = authentication as Saml2Authentication
        val principal = token.principal as Saml2AuthenticatedPrincipal
        val registrationId =
            principal.relyingPartyRegistrationId
                ?: return failSso(request, response)
        val attributes = saml2AttributesExtractor.extract(registrationId, principal)

        val connection = ssoConnectionService.findByRegistrationId(registrationId)
        if (connection == null || !connection.enabled) {
            failSso(request, response)
            return
        }

        // 도메인 일치 강제(정책 5): 이메일 없음/도메인 불일치는 일반 실패로 수렴(계정 탈취 차단).
        if (!ssoConnectionService.isEmailDomainVerified(connection, attributes.email)) {
            auditEventService.record(
                "SSO_DOMAIN_REJECTED",
                null,
                mapOf("provider" to attributes.provider, "email" to attributes.email),
            )
            failSso(request, response)
            return
        }
        val email =
            attributes.email!!.takeIf { it.length <= User.MAX_EMAIL_LENGTH }
                ?: return failSso(request, response)

        // 1) 이미 연결된 조직 신원 → 그 사용자로 로그인. 커넥션 id 로 스코프해 조회한다 —
        //    registration_id 재사용으로 남은 옛 커넥션의 신원(connection_id=NULL)이 상속돼 계정 탈취로
        //    이어지는 것을 차단한다(정책 5 보강).
        val identity =
            federationService.findIdentityForConnection(
                attributes.provider,
                attributes.providerUserId,
                connection.id!!,
            )
        if (identity != null) {
            val user =
                userRepository.findById(identity.userId).orElse(null)
                    ?: return failSso(request, response)
            gateOrComplete(request, response, user, attributes, connection)
            return
        }

        // 2) 같은 이메일의 로컬 계정 → 자동 연결(조직 IdP 보증 + 도메인 강제가 소유를 증명).
        val localUser = userRepository.findByEmail(email)
        if (localUser != null) {
            if (localUser.status != UserStatus.ACTIVE.name) {
                auditEventService.record(
                    "LOGIN_REJECTED",
                    localUser.id,
                    mapOf("provider" to attributes.provider, "reason" to localUser.status),
                )
                failSso(request, response)
                return
            }
            if (!federationService.linkOrConverge(localUser.id!!, attributes)) {
                failSso(request, response)
                return
            }
            // 조직 IdP + 도메인 강제로 소유가 증명됐으므로 미검증 로컬 이메일을 검증 처리한다
            // (EMAIL_VERIFICATION 게이트 회피 — 조직 SSO 는 이메일 소유를 이미 보증).
            if (!localUser.emailVerified) {
                localUser.emailVerified = true
                userRepository.save(localUser)
                // 이메일 인증 성공 전이 — 도메인 자동 조직 가입 판정(실패 비전파, 멱등).
                orgAutoJoinService.evaluate(localUser)
            }
            gateOrComplete(request, response, localUser, attributes, connection)
            return
        }

        // 3) 신규 계정 JIT 프로비저닝(비밀번호 없음, emailVerified=true) 후 연결.
        val created =
            try {
                federationService.createSocialUser(email, attributes)
            } catch (ex: DataIntegrityViolationException) {
                failSso(request, response)
                return
            }
        if (!federationService.linkOrConverge(created.id!!, attributes)) {
            failSso(request, response)
            return
        }
        // JIT 생성 계정은 emailVerified=true 로 태어난다(전이) — 도메인 자동 조직 가입 판정.
        orgAutoJoinService.evaluate(created)
        gateOrComplete(request, response, created, attributes, connection)
    }

    private fun gateOrComplete(
        request: HttpServletRequest,
        response: HttpServletResponse,
        user: User,
        attributes: SocialAttributes,
        connection: SsoConnection,
    ) {
        if (user.status != UserStatus.ACTIVE.name) {
            auditEventService.record(
                "LOGIN_REJECTED",
                user.id,
                mapOf("provider" to attributes.provider, "reason" to user.status),
            )
            failSso(request, response)
            return
        }

        val gate = loginFlowSupport.requiredGate(request, response, user)
        // trust_idp_mfa: 외부 IdP MFA 를 신뢰하는 커넥션은 로컬 MFA 게이트를 건너뛴다(정책 2).
        val effectiveGate = if (gate == PendingAuthStage.MFA && connection.trustIdpMfa) null else gate
        if (effectiveGate != null) {
            loginFlowSupport.startPending(request, response, user.id!!, effectiveGate, attributes.provider)
            auditEventService.record(
                "LOGIN_GATE",
                user.id,
                mapOf("stage" to effectiveGate.name, "provider" to attributes.provider),
            )
            val target =
                when (effectiveGate) {
                    PendingAuthStage.EMAIL_VERIFICATION -> "/login/verify-email"
                    else -> "/login/mfa"
                }
            redirect(request, response, target)
            return
        }

        accountLockoutService.recordSuccessfulLogin(user)
        auditEventService.record(
            "LOGIN_SUCCESS",
            user.id,
            mapOf("email" to user.email, "method" to attributes.provider),
        )
        val target = loginFlowSupport.completeAuthentication(request, response, user.id!!, attributes.provider)
        response.sendRedirect(target.removePrefix("redirect:"))
    }

    private fun failSso(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        loginFlowSupport.clearSecurityContext(request, response)
        redirect(request, response, "/login?error=sso")
    }

    private fun redirect(
        request: HttpServletRequest,
        response: HttpServletResponse,
        target: String,
    ) {
        response.sendRedirect(request.contextPath + target)
    }
}
