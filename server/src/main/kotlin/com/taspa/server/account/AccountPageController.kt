package com.taspa.server.account

import com.taspa.server.account.dto.SignupRequest
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.userMessageFor
import com.taspa.server.config.i18n.MessageResolver
import com.taspa.server.device.TrustedDeviceService
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.federation.FederationService
import com.taspa.server.federation.SocialLinkIntent
import com.taspa.server.federation.SocialProviders
import com.taspa.server.login.LoginFlowSupport
import com.taspa.server.login.PendingAuthStage
import com.taspa.server.org.OrganizationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class AccountPageController(
    private val accountService: AccountService,
    private val loginFlowSupport: LoginFlowSupport,
    private val userRepository: UserRepository,
    private val federationService: FederationService,
    private val organizationService: OrganizationService,
    private val trustedDeviceService: TrustedDeviceService,
    private val clientRegistrationRepositoryProvider: ObjectProvider<ClientRegistrationRepository>,
    private val messages: MessageResolver,
) {
    data class LinkableProvider(
        val id: String,
        val label: String,
    )

    /**
     * 루트("/") 전용 뷰는 없다. 로그인 성공 리다이렉트 등이 "/" 로 떨어져도 404 가 되지 않도록
     * /account 로 위임한다. 미인증 요청은 anyRequest().authenticated() 에 걸려 entry point 가
     * /login 으로 유도하므로 이 핸들러에는 인증된 요청만 도달한다.
     */
    @GetMapping("/")
    fun rootPage(): String = "redirect:/account"

    @GetMapping("/account")
    fun accountPage(
        @RequestParam(required = false) linked: String?,
        @RequestParam(required = false) linkError: String?,
        authentication: Authentication,
        model: Model,
    ): String {
        val user = userRepository.findByEmail(authentication.name) ?: return "redirect:/login"
        model.addAttribute("email", user.email)
        model.addAttribute("displayName", user.displayName ?: "")
        model.addAttribute("emailVerified", user.emailVerified)
        model.addAttribute("mfaEnabled", user.mfaEnabled)
        model.addAttribute("hasPassword", user.passwordHash != null)

        // 연결된 계정 섹션: 연결 목록 + (등록됐지만 아직 연결 안 된) 공급자 연결 버튼.
        val federations = federationService.list(user.id!!)
        val linkedProviders = federations.map { it.provider }.toSet()
        model.addAttribute("federations", federations)
        model.addAttribute(
            "linkableProviders",
            registeredProviderIds()
                .filterNot { linkedProviders.contains(it) }
                .map { LinkableProvider(it, SocialProviders.label(it)) },
        )
        if (linked == "1") {
            model.addAttribute("federationInfo", messages.get("account.federation.linked"))
        }
        if (linkError != null) {
            model.addAttribute(
                "federationError",
                if (linkError == "inuse") {
                    messages.get("account.federation.error.inUse")
                } else {
                    messages.get("account.federation.error.generic")
                },
            )
        }

        // 신뢰하는 기기 섹션(MFA 30일 스킵) — 해제는 step-up 대상 API 로 처리한다.
        model.addAttribute("trustedDevices", trustedDeviceService.list(user.id))

        // ORG_ADMIN 으로 관리하는 조직이 하나라도 있으면 자율 콘솔 링크를 노출한다(없으면 미노출).
        model.addAttribute("manageableOrgs", organizationService.listAdministeredOrgs(user.id!!).isNotEmpty())
        return "account"
    }

    /**
     * 계정 연결 시작: 로그인된 사용자의 연결 의도를 세션에 표시한 뒤 공급자 인가로 보낸다.
     * (oauth2Login 필터가 성공 시 SecurityContext 를 교체하므로, 성공 핸들러는 이 마커로
     * "로그인 세션의 연결 플로우"임을 식별한다 — SocialLinkIntent KDoc 참고.)
     */
    @GetMapping("/account/federations/link/{provider}")
    fun startFederationLink(
        @PathVariable provider: String,
        authentication: Authentication,
        request: HttpServletRequest,
    ): String {
        if (!registeredProviderIds().contains(provider)) {
            return "redirect:/account?linkError=1"
        }
        val user = userRepository.findByEmail(authentication.name) ?: return "redirect:/login"
        request.getSession(true).setAttribute(
            SocialLinkIntent.SESSION_KEY,
            SocialLinkIntent(userId = user.id!!, provider = provider),
        )
        return "redirect:/oauth2/authorization/$provider"
    }

    @GetMapping("/logout")
    fun logoutConfirmPage(): String = "logout"

    @GetMapping("/signup")
    fun signupPage(): String = "signup"

    @PostMapping("/signup")
    fun submitSignup(
        @RequestParam email: String,
        @RequestParam password: String,
        @RequestParam(required = false) displayName: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
        model: Model,
    ): String =
        try {
            // 인증 코드 발송은 accountService.signup 안에 있다(모든 가입 경로 공통 — KDoc 참고).
            // 방금 본인이 만든 계정이므로 비밀번호 재입력 없이 이메일 인증 게이트로 진입시킨다.
            val user = accountService.signup(SignupRequest(email.trim(), password, displayName))
            loginFlowSupport.startPending(request, response, user.id!!, PendingAuthStage.EMAIL_VERIFICATION, "password")
            "redirect:/login/verify-email"
        } catch (ex: AuthException) {
            model.addAttribute("email", email.trim())
            model.addAttribute("displayName", displayName ?: "")
            // 영문 기본 상수가 한국어 화면에 새지 않게 — 판정은 userMessageFor 한 곳에 있다.
            model.addAttribute("error", messages.userMessageFor(ex))
            "signup"
        }

    private fun registeredProviderIds(): List<String> {
        val repository = clientRegistrationRepositoryProvider.getIfAvailable() ?: return emptyList()
        val registrations = repository as? Iterable<*> ?: return emptyList()
        return registrations.filterIsInstance<ClientRegistration>().map { it.registrationId }.sorted()
    }
}
