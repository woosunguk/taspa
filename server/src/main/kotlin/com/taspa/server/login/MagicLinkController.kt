package com.taspa.server.login

import com.taspa.server.audit.AuditEventService
import com.taspa.server.credential.AccountLockoutService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * 매직 링크 엔드포인트(B-4). GET 은 토큰을 소비하지 않고 확인 페이지만 렌더하며,
 * POST 가 소비·승격한다 — 이메일 스캐너의 선클릭으로 토큰이 타지 않도록.
 */
@Controller
class MagicLinkController(
    private val magicLinkService: MagicLinkService,
    private val loginFlowSupport: LoginFlowSupport,
    private val accountLockoutService: AccountLockoutService,
    private val auditEventService: AuditEventService,
) {
    companion object {
        private const val METHOD_MAGIC = "magic"
    }

    /** password 페이지의 "이메일로 로그인 링크 받기". LOGIN_HINT 의 이메일 대상 — 결과와 무관하게 동일 화면. */
    @PostMapping("/login/magic/request")
    fun requestMagicLink(
        request: HttpServletRequest,
        model: Model,
    ): String {
        val hint =
            request.getSession(false)?.getAttribute(PendingAuth.LOGIN_HINT_KEY) as? String
                ?: return "redirect:/login"
        magicLinkService.request(hint)
        model.addAttribute("email", hint)
        return "login/magic-sent"
    }

    @GetMapping("/login/magic")
    fun magicLandingPage(
        @RequestParam token: String,
        model: Model,
    ): String {
        val user = magicLinkService.peek(token) ?: return "login/magic-invalid"
        model.addAttribute("email", user.email)
        model.addAttribute("token", token)
        return "login/magic-confirm"
    }

    @PostMapping("/login/magic")
    fun submitMagicLogin(
        @RequestParam token: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): String {
        val user = magicLinkService.consume(token) ?: return "login/magic-invalid"

        accountLockoutService.recordSuccessfulLogin(user)

        // 이메일 소유는 방금 증명됐고(consume 에서 검증 마킹) MFA 게이트는 유지된다.
        val gate = loginFlowSupport.requiredGate(request, response, user)
        if (gate != null) {
            loginFlowSupport.startPending(request, response, user.id!!, gate, METHOD_MAGIC)
            auditEventService.record("LOGIN_GATE", user.id, mapOf("stage" to gate.name, "method" to METHOD_MAGIC))
            return when (gate) {
                PendingAuthStage.EMAIL_VERIFICATION -> "redirect:/login/verify-email"
                else -> "redirect:/login/mfa"
            }
        }

        auditEventService.record("LOGIN_SUCCESS", user.id, mapOf("email" to user.email, "method" to METHOD_MAGIC))
        return loginFlowSupport.completeAuthentication(request, response, user.id!!, METHOD_MAGIC)
    }
}
