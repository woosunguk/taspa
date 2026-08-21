package com.taspa.server.stepup

import com.taspa.server.audit.AuditEventService
import com.taspa.server.config.i18n.MessageResolver
import com.taspa.server.credential.AccountLockoutService
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.federation.FederationService
import com.taspa.server.federation.SocialProviders
import com.taspa.server.federation.SocialReauthIntent
import com.taspa.server.passkey.PasskeyService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.core.Authentication
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody

/**
 * Step-up 재인증. 인증된 세션에서만 접근 가능하며(anyRequest().authenticated()),
 * 성공 시 auth_time 만 갱신한다 — SecurityContext/세션 ID 는 그대로 둔다(이미 완전 인증 상태).
 *
 * 패스키 재인증은 기존 /webauthn/authenticate/options + POST /login/webauthn 을 재사용한다 —
 * 성공하면 PasskeyAuthenticationSuccessHandler 가 auth_time 을 갱신하고, 페이지 JS 가 continue 로 복귀한다.
 */
@Controller
class ReauthController(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val passkeyService: PasskeyService,
    private val federationService: FederationService,
    private val accountLockoutService: AccountLockoutService,
    private val auditEventService: AuditEventService,
    private val stepUpProperties: StepUpProperties,
    private val clientRegistrationRepositoryProvider: ObjectProvider<ClientRegistrationRepository>,
    private val messages: MessageResolver,
) {
    /** 소셜 재인증 버튼용: 이 사용자에게 연결돼 있고 현재 등록된 공급자. */
    data class SocialReauthOption(
        val id: String,
        val label: String,
    )

    @GetMapping("/reauth")
    fun reauthPage(
        @RequestParam(name = "continue", required = false) continuePath: String?,
        @RequestParam(required = false) error: String?,
        request: HttpServletRequest,
        authentication: Authentication,
        model: Model,
    ): String {
        val user = userRepository.findByEmail(authentication.name) ?: return "redirect:/login"
        val sessionContinue = request.getSession(false)?.getAttribute(StepUp.CONTINUE_SESSION_KEY) as? String
        model.addAttribute("continuePath", StepUp.sanitizeContinuePath(continuePath ?: sessionContinue))
        if (error != null) {
            model.addAttribute("error", messages.get("reauth.error.social"))
        }
        fillModel(model, user)
        return "reauth"
    }

    /**
     * 소셜 재인증 시작: 소셜 전용 계정(비밀번호·패스키 없음)도 step-up 을 통과할 수 있도록,
     * 연결된 소셜 계정으로 본인 확인을 허용한다. SocialLinkIntent 와 같은 세션 마커 패턴 —
     * 성공 핸들러(FederatedLoginSuccessHandler.handleSocialReauth)가 auth_time 만 갱신하고 복귀시킨다.
     */
    @GetMapping("/reauth/social/{provider}")
    fun startSocialReauth(
        @PathVariable provider: String,
        @RequestParam(name = "continue", required = false) continuePath: String?,
        request: HttpServletRequest,
        authentication: Authentication,
    ): String {
        val user = userRepository.findByEmail(authentication.name) ?: return "redirect:/login"
        val linked = federationService.linkedProviders(user.id!!)
        if (!registeredProviderIds().contains(provider) || !linked.contains(provider)) {
            return "redirect:/reauth?error=social"
        }
        request.getSession(true).setAttribute(
            SocialReauthIntent.SESSION_KEY,
            SocialReauthIntent(
                userId = user.id,
                provider = provider,
                continuePath = StepUp.sanitizeContinuePath(continuePath),
            ),
        )
        return "redirect:/oauth2/authorization/$provider"
    }

    @PostMapping("/reauth")
    fun submitReauth(
        @RequestParam password: String,
        @RequestParam(name = "continue", required = false) continuePath: String?,
        request: HttpServletRequest,
        authentication: Authentication,
        model: Model,
    ): String {
        val user = userRepository.findByEmail(authentication.name) ?: return "redirect:/login"
        val target = StepUp.sanitizeContinuePath(continuePath)
        model.addAttribute("continuePath", target)
        fillModel(model, user)

        if (accountLockoutService.isAccountLocked(user)) {
            model.addAttribute("error", messages.get("login.error.locked"))
            return "reauth"
        }
        val hash = user.passwordHash
        if (hash == null || !passwordEncoder.matches(password, hash)) {
            accountLockoutService.recordFailedAttempt(user)
            auditEventService.record("REAUTH_FAILED", user.id, mapOf("email" to user.email))
            model.addAttribute("error", messages.get("reauth.error.wrongPassword"))
            return "reauth"
        }

        accountLockoutService.recordSuccessfulLogin(user)
        StepUp.markAuthTime(request, user.id!!)
        request.getSession(false)?.removeAttribute(StepUp.CONTINUE_SESSION_KEY)
        auditEventService.record("REAUTH_SUCCESS", user.id, mapOf("method" to "password"))
        return "redirect:$target"
    }

    /**
     * 페이지 JS 용 사전 점검: 패스키 등록 시작 전 account 페이지가 호출해 만료면 /reauth 로 보낸다.
     * (서버측 강제는 StepUpEnforcementFilter 가 별도로 수행한다.)
     */
    @GetMapping("/api/reauth/check")
    @ResponseBody
    fun check(request: HttpServletRequest): Map<String, Boolean> =
        mapOf("reauthRequired" to !StepUp.isRecent(request, stepUpProperties.maxAge))

    private fun fillModel(
        model: Model,
        user: User,
    ) {
        model.addAttribute("email", user.email)
        model.addAttribute("hasPassword", user.passwordHash != null)
        model.addAttribute("hasPasskeys", user.id?.let { passkeyService.hasPasskeys(it) } ?: false)
        // 연결됐고 등록도 된 소셜 공급자 — 소셜 전용 계정의 step-up 데드엔드 해소용 재인증 수단.
        val linked = user.id?.let { federationService.linkedProviders(it) } ?: emptySet()
        model.addAttribute(
            "socialProviders",
            registeredProviderIds()
                .filter { linked.contains(it) }
                .map { SocialReauthOption(it, SocialProviders.label(it)) },
        )
    }

    private fun registeredProviderIds(): List<String> {
        val repository = clientRegistrationRepositoryProvider.getIfAvailable() ?: return emptyList()
        val registrations = repository as? Iterable<*> ?: return emptyList()
        return registrations.filterIsInstance<ClientRegistration>().map { it.registrationId }.sorted()
    }
}
