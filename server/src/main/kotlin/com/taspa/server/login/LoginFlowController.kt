package com.taspa.server.login

import com.taspa.server.config.i18n.MessageResolver
import com.taspa.server.device.TrustedDeviceService
import com.taspa.server.domain.sso.SsoProtocol
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserStatus
import com.taspa.server.enterprise.SsoConnectionService
import com.taspa.server.federation.FederationService
import com.taspa.server.federation.PendingSocialLink
import com.taspa.server.federation.SocialAttributes
import com.taspa.server.federation.SocialProviders
import com.taspa.server.mfa.MfaService
import com.taspa.server.passkey.PasskeyService
import com.taspa.server.verification.EmailVerificationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

@Controller
class LoginFlowController(
    private val userRepository: UserRepository,
    private val emailVerificationService: EmailVerificationService,
    private val mfaService: MfaService,
    private val loginFlowSupport: LoginFlowSupport,
    private val passkeyService: PasskeyService,
    private val federationService: FederationService,
    private val trustedDeviceService: TrustedDeviceService,
    private val clientRegistrationRepositoryProvider: ObjectProvider<ClientRegistrationRepository>,
    private val ssoConnectionService: SsoConnectionService,
    private val messages: MessageResolver,
) {
    /** 로그인 페이지 버튼용: 등록된 소셜 공급자 (registrationId, 표시명) 목록. */
    data class SocialLoginOption(
        val id: String,
        val label: String,
    )

    /** 기업 SSO 보조 진입 버튼(enforced=false 커넥션): 경로(entryPath)와 표시명. */
    data class SsoLoginOption(
        val entryPath: String,
        val label: String,
    )

    // ---- 1단계: 이메일(identifier) ----

    @GetMapping("/login")
    fun identifierPage(
        @RequestParam(required = false) reset: String?,
        @RequestParam(required = false) error: String?,
        @RequestParam(required = false) expired: String?,
        request: HttpServletRequest,
        model: Model,
    ): String {
        if (reset == "true") {
            model.addAttribute("info", messages.get("login.info.passwordChanged"))
        }
        /*
         * ★게이트(MFA·이메일 인증·리스크 챌린지)가 시간 초과로 사라져 여기로 돌아온 경우.
         * 이 안내가 없던 동안, 정답 코드를 넣은 순간 아무 설명 없이 빈 로그인 화면으로 튕겼고
         * 사용자는 코드가 틀렸다고 오해했다(`LoginFlowSupport.gateLostRedirect`).
         * 이메일은 세션에 남은 힌트로 채워 재입력 부담을 없앤다.
         */
        if (expired == "1") {
            model.addAttribute("error", messages.get("login.error.gateExpired"))
            (request.getSession(false)?.getAttribute(PendingAuth.LOGIN_HINT_KEY) as? String)
                ?.let { model.addAttribute("email", it) }
        }
        // 벤더링한 webauthn.js 는 패스키 실패 시 /login?error 로 이동한다. 소셜 실패는 error=social,
        // 기업 SSO(SAML/조직 OIDC) 실패는 error=sso.
        if (error != null) {
            model.addAttribute(
                "error",
                when (error) {
                    "social" -> messages.get("login.error.social")
                    "sso" -> messages.get("login.error.sso")
                    else -> messages.get("login.error.passkey")
                },
            )
        }
        model.addAttribute("socialProviders", registeredProviders())
        model.addAttribute("ssoProviders", ssoLoginButtons())
        return "login/identifier"
    }

    @PostMapping("/login/identifier")
    fun submitIdentifier(
        @RequestParam email: String,
        request: HttpServletRequest,
        model: Model,
    ): String {
        val normalized = email.trim().lowercase()

        // HRD(Home Realm Discovery): 이메일 도메인이 enabled+verified 매핑을 가진 SSO 커넥션과
        // 매칭되고 enforced 면 로컬 password/passkey 보다 먼저 IdP 로 단락 리다이렉트한다(정책 1).
        val domain = normalized.substringAfterLast('@', "")
        if (domain.isNotEmpty()) {
            val connection = ssoConnectionService.findEnabledConnectionByDomain(domain)
            if (connection != null && connection.enforced) {
                return when (connection.protocolEnum()) {
                    SsoProtocol.OIDC -> "redirect:/oauth2/authorization/${connection.registrationId}"
                    SsoProtocol.SAML -> "redirect:/saml2/authenticate/${connection.registrationId}"
                }
            }
        }

        val user = if (normalized.isEmpty()) null else userRepository.findByEmail(normalized)
        if (user == null) {
            model.addAttribute("email", normalized)
            model.addAttribute("error", messages.get("login.error.userNotFound"))
            model.addAttribute("socialProviders", registeredProviders())
            model.addAttribute("ssoProviders", ssoLoginButtons())
            return "login/identifier"
        }
        request.getSession(true).setAttribute(PendingAuth.LOGIN_HINT_KEY, normalized)
        // 구글식: 패스키 보유 사용자는 패스키 화면을 우선 제시한다.
        if (passkeyService.hasPasskeys(user.id!!)) {
            return "redirect:/login/passkey"
        }
        return "redirect:/login/password"
    }

    // ---- 패스키 (어서션은 POST /login/webauthn 필터가 처리) ----

    @GetMapping("/login/passkey")
    fun passkeyPage(
        request: HttpServletRequest,
        model: Model,
    ): String {
        if (loginFlowSupport.isFullyAuthenticated()) {
            return "redirect:/account"
        }
        val hint =
            request.getSession(false)?.getAttribute(PendingAuth.LOGIN_HINT_KEY) as? String
                ?: return loginFlowSupport.gateLostRedirect(request)
        model.addAttribute("email", hint)
        return "login/passkey"
    }

    // ---- 2단계: 비밀번호 (POST 는 formLogin 필터가 처리) ----

    @GetMapping("/login/password")
    fun passwordPage(
        @RequestParam(required = false) error: String?,
        request: HttpServletRequest,
        model: Model,
    ): String {
        if (loginFlowSupport.isFullyAuthenticated()) {
            return "redirect:/account"
        }
        val hint =
            request.getSession(false)?.getAttribute(PendingAuth.LOGIN_HINT_KEY) as? String
                ?: return loginFlowSupport.gateLostRedirect(request)
        model.addAttribute("email", hint)
        val user = userRepository.findByEmail(hint)
        model.addAttribute("hasPasskeys", user?.id?.let { passkeyService.hasPasskeys(it) } ?: false)
        // 이 계정에 연결된 소셜 공급자 버튼(등록된 것만) — 소셜 전용 계정 UX.
        val linked = user?.id?.let { federationService.linkedProviders(it) } ?: emptySet()
        model.addAttribute("linkedProviders", registeredProviders().filter { linked.contains(it.id) })
        if (error != null) {
            model.addAttribute(
                "error",
                if (error == "locked") {
                    messages.get("login.error.locked")
                } else {
                    messages.get("login.error.invalidCredentials")
                },
            )
        }
        return "login/password"
    }

    // ---- MFA 게이트 ----

    @GetMapping("/login/mfa")
    fun mfaPage(
        @RequestParam(required = false) method: String?,
        request: HttpServletRequest,
        model: Model,
    ): String {
        if (loginFlowSupport.isFullyAuthenticated()) {
            return "redirect:/account"
        }
        val pending = loginFlowSupport.currentPending(request)
        if (pending == null || pending.stage != PendingAuthStage.MFA) {
            return loginFlowSupport.gateLostRedirect(request)
        }
        model.addAttribute("useBackupCode", method == "backup")
        return "login/mfa"
    }

    @PostMapping("/login/mfa")
    fun submitMfa(
        @RequestParam code: String,
        @RequestParam(required = false) method: String?,
        @RequestParam(required = false) trustDevice: String?,
        request: HttpServletRequest,
        response: HttpServletResponse,
        model: Model,
    ): String {
        val pending = loginFlowSupport.currentPending(request)
        if (pending == null || pending.stage != PendingAuthStage.MFA) {
            return loginFlowSupport.gateLostRedirect(request)
        }
        val userId = pending.userId ?: return loginFlowSupport.gateLostRedirect(request)

        val trimmed = code.trim()
        val verified =
            mfaService.verifyCode(userId, trimmed) ||
                mfaService.verifyBackupCode(userId, trimmed)
        if (!verified) {
            model.addAttribute("useBackupCode", method == "backup")
            model.addAttribute("error", messages.get("login.mfa.error.invalidCode"))
            return "login/mfa"
        }

        // "이 기기에서 30일 동안 묻지 않음" — MFA 성공 시에만 신뢰 기기 쿠키를 발급한다.
        if (trustDevice != null) {
            trustedDeviceService.issue(userId, request, response)
        }
        return loginFlowSupport.completeAuthentication(request, response, userId, "mfa")
    }

    // ---- 이메일 인증 게이트 ----

    @GetMapping("/login/verify-email")
    fun verifyEmailPage(
        @RequestParam(required = false) resent: String?,
        request: HttpServletRequest,
        model: Model,
    ): String {
        if (loginFlowSupport.isFullyAuthenticated()) {
            return "redirect:/account"
        }
        val pending = loginFlowSupport.currentPending(request)
        if (pending == null || pending.stage != PendingAuthStage.EMAIL_VERIFICATION || pending.userId == null) {
            return loginFlowSupport.gateLostRedirect(request)
        }
        model.addAttribute("email", emailOf(pending.userId))
        if (resent == "true") {
            model.addAttribute("info", messages.get("login.info.codeResent"))
        } else if (resent == "throttled") {
            model.addAttribute("error", messages.get("login.error.resendThrottled"))
        }
        return "login/verify-email"
    }

    @PostMapping("/login/verify-email")
    fun submitVerifyEmail(
        @RequestParam code: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
        model: Model,
    ): String {
        val pending = loginFlowSupport.currentPending(request)
        if (pending == null || pending.stage != PendingAuthStage.EMAIL_VERIFICATION || pending.userId == null) {
            return loginFlowSupport.gateLostRedirect(request)
        }

        if (!emailVerificationService.verify(pending.userId, code.trim())) {
            model.addAttribute("email", emailOf(pending.userId))
            model.addAttribute("error", messages.get("login.error.invalidVerificationCode"))
            return "login/verify-email"
        }

        // 소셜(미검증 공급자) 신규 가입 경유: 이메일 소유가 방금 증명됐으므로 이제야 소셜 신원을
        // 연결한다 — 소유 증명 전 연결은 계정 선점(스쿼팅) 취약점이라 성공 핸들러가 연결을 미뤄뒀다.
        // pending.method 가 소셜일 때만 — 같은 세션의 중단된 소셜 시도가 폼 가입 인증에 섞이지 않도록.
        val link = pendingSocialLink(request)
        if (link != null && pending.method?.startsWith("social:") == true) {
            return linkAndFinish(request, response, pending.userId, link)
        }

        // 이메일 인증 통과 후 남은 게이트(MFA — 신뢰 기기 판정 포함)를 공용 함수로 판정한다.
        val user = userRepository.findById(pending.userId).orElse(null) ?: return loginFlowSupport.gateLostRedirect(request)
        if (loginFlowSupport.requiredGate(request, response, user) == PendingAuthStage.MFA) {
            loginFlowSupport.transitionPending(request, pending.userId, PendingAuthStage.MFA, pending.method)
            return "redirect:/login/mfa"
        }
        return loginFlowSupport.completeAuthentication(request, response, pending.userId, pending.method ?: "password")
    }

    @PostMapping("/login/verify-email/resend")
    fun resendVerifyEmail(request: HttpServletRequest): String {
        val pending = loginFlowSupport.currentPending(request)
        if (pending == null || pending.stage != PendingAuthStage.EMAIL_VERIFICATION || pending.userId == null) {
            return loginFlowSupport.gateLostRedirect(request)
        }
        val sent = emailVerificationService.resend(pending.userId)
        return if (sent) "redirect:/login/verify-email?resent=true" else "redirect:/login/verify-email?resent=throttled"
    }

    // ---- 리스크 챌린지 게이트 ----
    // 비밀번호 로그인에서 MEDIUM 이상 리스크가 감지된 MFA 미등록 사용자 — 이메일 코드로 본인 확인.
    // 코드 발급·검증은 EmailVerificationService 재사용(챌린지 통과 = 이메일 소유 재증명).

    @GetMapping("/login/risk-challenge")
    fun riskChallengePage(
        @RequestParam(required = false) resent: String?,
        request: HttpServletRequest,
        model: Model,
    ): String {
        if (loginFlowSupport.isFullyAuthenticated()) {
            return "redirect:/account"
        }
        val pending = loginFlowSupport.currentPending(request)
        if (pending == null || pending.stage != PendingAuthStage.RISK_CHALLENGE || pending.userId == null) {
            return loginFlowSupport.gateLostRedirect(request)
        }
        model.addAttribute("email", emailOf(pending.userId))
        if (resent == "true") {
            model.addAttribute("info", messages.get("login.info.codeResent"))
        } else if (resent == "throttled") {
            model.addAttribute("error", messages.get("login.error.resendThrottled"))
        }
        return "login/risk-challenge"
    }

    @PostMapping("/login/risk-challenge")
    fun submitRiskChallenge(
        @RequestParam code: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
        model: Model,
    ): String {
        val pending = loginFlowSupport.currentPending(request)
        if (pending == null || pending.stage != PendingAuthStage.RISK_CHALLENGE || pending.userId == null) {
            return loginFlowSupport.gateLostRedirect(request)
        }

        // verify() 가 아닌 챌린지 전용 검증 — 이미 인증된 사용자라 emailVerified 재기록이 불필요하고,
        // 감사 로그에서 가입 인증(EMAIL_VERIFIED)과 챌린지 통과(RISK_CHALLENGE_PASSED)를 구분한다.
        if (!emailVerificationService.verifyRiskChallenge(pending.userId, code.trim())) {
            model.addAttribute("email", emailOf(pending.userId))
            model.addAttribute("error", messages.get("login.error.invalidVerificationCode"))
            return "login/risk-challenge"
        }

        // RISK_CHALLENGE 는 MFA 미등록 사용자에게만 배정되지만, 방어적으로 남은 게이트를 재판정한다
        // (verify-email 통과 경로와 동일 패턴 — 재판정은 리스크 무전달 = 기본 LOW).
        val user = userRepository.findById(pending.userId).orElse(null) ?: return loginFlowSupport.gateLostRedirect(request)
        if (loginFlowSupport.requiredGate(request, response, user) == PendingAuthStage.MFA) {
            loginFlowSupport.transitionPending(request, pending.userId, PendingAuthStage.MFA, pending.method)
            return "redirect:/login/mfa"
        }
        return loginFlowSupport.completeAuthentication(request, response, pending.userId, pending.method ?: "password")
    }

    @PostMapping("/login/risk-challenge/resend")
    fun resendRiskChallenge(request: HttpServletRequest): String {
        val pending = loginFlowSupport.currentPending(request)
        if (pending == null || pending.stage != PendingAuthStage.RISK_CHALLENGE || pending.userId == null) {
            return loginFlowSupport.gateLostRedirect(request)
        }
        val sent = emailVerificationService.resend(pending.userId)
        return if (sent) "redirect:/login/risk-challenge?resent=true" else "redirect:/login/risk-challenge?resent=throttled"
    }

    // ---- 소셜: 기존 계정 확인(SOCIAL_LINK) 게이트 ----
    // 같은 이메일의 로컬 계정이 있으나 자동 연결 조건(공급자·로컬 모두 검증됨)이 안 될 때,
    // 이메일 코드로 소유자 본인임을 확인한 뒤 연결한다.

    @GetMapping("/login/link-confirm")
    fun linkConfirmPage(
        @RequestParam(required = false) resent: String?,
        request: HttpServletRequest,
        model: Model,
    ): String {
        if (loginFlowSupport.isFullyAuthenticated()) {
            return "redirect:/account"
        }
        val pending = loginFlowSupport.currentPending(request)
        val link = pendingSocialLink(request)
        if (pending == null || pending.stage != PendingAuthStage.SOCIAL_LINK || pending.userId == null || link == null) {
            return loginFlowSupport.gateLostRedirect(request)
        }
        model.addAttribute("email", emailOf(pending.userId))
        model.addAttribute("providerLabel", SocialProviders.label(link.provider))
        if (resent == "true") {
            model.addAttribute("info", messages.get("login.info.codeResent"))
        } else if (resent == "throttled") {
            model.addAttribute("error", messages.get("login.error.resendThrottled"))
        }
        return "login/link-confirm"
    }

    @PostMapping("/login/link-confirm")
    fun submitLinkConfirm(
        @RequestParam code: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
        model: Model,
    ): String {
        val pending = loginFlowSupport.currentPending(request)
        val link = pendingSocialLink(request)
        if (pending == null || pending.stage != PendingAuthStage.SOCIAL_LINK || pending.userId == null || link == null) {
            return loginFlowSupport.gateLostRedirect(request)
        }

        // 코드 확인 = 이메일 소유 증명 → 로컬 이메일도 검증 처리된다(EmailVerificationService.verify).
        if (!emailVerificationService.verify(pending.userId, code.trim())) {
            model.addAttribute("email", emailOf(pending.userId))
            model.addAttribute("providerLabel", SocialProviders.label(link.provider))
            model.addAttribute("error", messages.get("login.error.invalidVerificationCode"))
            return "login/link-confirm"
        }

        return linkAndFinish(request, response, pending.userId, link)
    }

    @PostMapping("/login/link-confirm/resend")
    fun resendLinkConfirm(request: HttpServletRequest): String {
        val pending = loginFlowSupport.currentPending(request)
        if (pending == null || pending.stage != PendingAuthStage.SOCIAL_LINK || pending.userId == null) {
            return loginFlowSupport.gateLostRedirect(request)
        }
        val sent = emailVerificationService.resend(pending.userId)
        return if (sent) "redirect:/login/link-confirm?resent=true" else "redirect:/login/link-confirm?resent=throttled"
    }

    // ---- 소셜: 이메일 미제공(SOCIAL_EMAIL) 게이트 ----
    // 카카오 이메일 동의 거부 등. 이메일 입력 → 코드 확인 → 계정 생성(검증됨) + 연결 + 완전 인증.
    // 입력한 이메일의 로컬 계정이 이미 있으면 기존 계정 확인 플로우(SOCIAL_LINK)로 전환한다.

    @GetMapping("/login/social-email")
    fun socialEmailPage(
        @RequestParam(required = false) resent: String?,
        request: HttpServletRequest,
        model: Model,
    ): String {
        if (loginFlowSupport.isFullyAuthenticated()) {
            return "redirect:/account"
        }
        val pending = loginFlowSupport.currentPending(request)
        val link = pendingSocialLink(request)
        if (pending == null || pending.stage != PendingAuthStage.SOCIAL_EMAIL || link == null) {
            return loginFlowSupport.gateLostRedirect(request)
        }
        model.addAttribute("providerLabel", SocialProviders.label(link.provider))
        // userId 가 생기면(이메일 제출 후) 코드 입력 화면.
        model.addAttribute("codeStep", pending.userId != null)
        pending.userId?.let { model.addAttribute("email", emailOf(it)) }
        if (resent == "true") {
            model.addAttribute("info", messages.get("login.info.codeResent"))
        } else if (resent == "throttled") {
            model.addAttribute("error", messages.get("login.error.resendThrottled"))
        }
        return "login/social-email"
    }

    @PostMapping("/login/social-email")
    fun submitSocialEmail(
        @RequestParam email: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
        model: Model,
    ): String {
        val pending = loginFlowSupport.currentPending(request)
        val link = pendingSocialLink(request)
        if (pending == null || pending.stage != PendingAuthStage.SOCIAL_EMAIL || link == null) {
            return loginFlowSupport.gateLostRedirect(request)
        }

        val normalized = email.trim().lowercase()
        // 길이 상한: 세션 PRINCIPAL_NAME(VARCHAR 100) 제약 — User.MAX_EMAIL_LENGTH KDoc 참고.
        if (normalized.isEmpty() || !normalized.contains('@') || normalized.length > User.MAX_EMAIL_LENGTH) {
            model.addAttribute("providerLabel", SocialProviders.label(link.provider))
            model.addAttribute("codeStep", false)
            model.addAttribute("error", messages.get("login.socialEmail.error.invalidEmail"))
            return "login/social-email"
        }

        val existing = userRepository.findByEmail(normalized)
        if (existing != null) {
            // 비활성 계정은 코드 발송 전에 차단한다(SOCIAL_LINK 완결 경로의 정지 우회 방지).
            if (existing.status != UserStatus.ACTIVE.name) {
                return "redirect:/login?error=social"
            }
            // 이미 이 이메일의 계정이 있음 → 소유 확인 후 연결(SOCIAL_LINK 와 동일 정책).
            emailVerificationService.issue(existing.id!!)
            loginFlowSupport.transitionPending(request, existing.id, PendingAuthStage.SOCIAL_LINK)
            return "redirect:/login/link-confirm"
        }

        // 신규 계정(비밀번호 없음, 미검증) 생성 후 코드 확인으로 검증한다.
        val created =
            federationService.createSocialUser(
                normalized,
                SocialAttributes(
                    provider = link.provider,
                    providerUserId = link.providerUserId,
                    email = normalized,
                    emailVerifiedByProvider = false,
                    displayName = link.displayName,
                ),
            )
        emailVerificationService.issue(created.id!!)
        loginFlowSupport.transitionPending(request, created.id, PendingAuthStage.SOCIAL_EMAIL)
        return "redirect:/login/social-email"
    }

    @PostMapping("/login/social-email/verify")
    fun submitSocialEmailCode(
        @RequestParam code: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
        model: Model,
    ): String {
        val pending = loginFlowSupport.currentPending(request)
        val link = pendingSocialLink(request)
        if (pending == null || pending.stage != PendingAuthStage.SOCIAL_EMAIL || pending.userId == null || link == null) {
            return loginFlowSupport.gateLostRedirect(request)
        }

        if (!emailVerificationService.verify(pending.userId, code.trim())) {
            model.addAttribute("providerLabel", SocialProviders.label(link.provider))
            model.addAttribute("codeStep", true)
            model.addAttribute("email", emailOf(pending.userId))
            model.addAttribute("error", messages.get("login.error.invalidVerificationCode"))
            return "login/social-email"
        }

        return linkAndFinish(request, response, pending.userId, link)
    }

    @PostMapping("/login/social-email/resend")
    fun resendSocialEmailCode(request: HttpServletRequest): String {
        val pending = loginFlowSupport.currentPending(request)
        if (pending == null || pending.stage != PendingAuthStage.SOCIAL_EMAIL || pending.userId == null) {
            return loginFlowSupport.gateLostRedirect(request)
        }
        val sent = emailVerificationService.resend(pending.userId)
        return if (sent) "redirect:/login/social-email?resent=true" else "redirect:/login/social-email?resent=throttled"
    }

    /** 소셜 게이트 공통 마무리: 계정 상태 확인 → 신원 연결 → 남은 게이트(MFA) 판정 → 완전 인증. */
    private fun linkAndFinish(
        request: HttpServletRequest,
        response: HttpServletResponse,
        userId: UUID,
        link: PendingSocialLink,
    ): String {
        val user = userRepository.findById(userId).orElse(null) ?: return loginFlowSupport.gateLostRedirect(request)
        // 비활성 계정 차단 — 이 경로는 AuthenticationProvider(UserDetails.disabled 검사)를 거치지
        // 않으므로 여기서 명시적으로 확인하지 않으면 정지된 사용자가 자기 이메일 코드만으로
        // 소셜 링크 경로를 통해 로그인 정책을 우회한다(completeAuthentication 의 백스톱과 이중 방어).
        if (user.status != UserStatus.ACTIVE.name) {
            request.getSession(false)?.let {
                it.removeAttribute(PendingAuth.SESSION_KEY)
                it.removeAttribute(PendingSocialLink.SESSION_KEY)
            }
            return "redirect:/login?error=social"
        }

        // 코드 확인 중 같은 소셜 신원이 다른 경로로 연결됐을 수 있다(레이스) —
        // linkOrConverge 가 멱등 성공/UNIQUE 경합을 모두 수렴한다.
        val linked =
            federationService.linkOrConverge(
                userId,
                SocialAttributes(
                    provider = link.provider,
                    providerUserId = link.providerUserId,
                    email = link.email,
                    emailVerifiedByProvider = false,
                    displayName = link.displayName,
                ),
            )
        if (!linked) {
            return "redirect:/login?error=social"
        }
        request.getSession(false)?.removeAttribute(PendingSocialLink.SESSION_KEY)

        val method = "social:${link.provider}"
        if (loginFlowSupport.requiredGate(request, response, user) == PendingAuthStage.MFA) {
            loginFlowSupport.transitionPending(request, userId, PendingAuthStage.MFA, method)
            return "redirect:/login/mfa"
        }
        return loginFlowSupport.completeAuthentication(request, response, userId, method)
    }

    private fun pendingSocialLink(request: HttpServletRequest): PendingSocialLink? =
        request.getSession(false)?.getAttribute(PendingSocialLink.SESSION_KEY) as? PendingSocialLink

    private fun registeredProviders(): List<SocialLoginOption> {
        val repository = clientRegistrationRepositoryProvider.getIfAvailable() ?: return emptyList()
        val registrations = repository as? Iterable<*> ?: return emptyList()
        return registrations
            .filterIsInstance<ClientRegistration>()
            .map { SocialLoginOption(it.registrationId, SocialProviders.label(it.registrationId)) }
            .sortedBy { it.id }
    }

    /** enforced=false 인 enabled 기업 SSO 커넥션 — "회사 계정으로 로그인" 보조 진입 버튼(HRD 가 주 경로). */
    private fun ssoLoginButtons(): List<SsoLoginOption> =
        ssoConnectionService.optionalLoginButtons().map {
            val entry =
                when (it.protocol) {
                    SsoProtocol.SAML.name -> "/saml2/authenticate/${it.registrationId}"
                    else -> "/oauth2/authorization/${it.registrationId}"
                }
            SsoLoginOption(entry, it.displayName)
        }

    private fun emailOf(userId: UUID): String = userRepository.findById(userId).map { it.email }.orElse("")
}
