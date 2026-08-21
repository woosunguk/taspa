package com.taspa.server.credential

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.common.exception.userMessageFor
import com.taspa.server.config.i18n.MessageResolver
import com.taspa.server.mail.MailService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class PasswordResetController(
    private val passwordResetService: PasswordResetService,
    private val mailService: MailService,
    private val messages: MessageResolver,
    @Value("\${taspa.password-reset.base-url:http://localhost:9100}")
    private val baseUrl: String,
) {
    @GetMapping("/password-reset")
    fun requestPage(): String = "password-reset/request"

    @PostMapping("/password-reset")
    fun submitRequest(
        @RequestParam email: String,
        model: Model,
    ): String {
        val rawToken = passwordResetService.createResetToken(email.trim())
        if (rawToken != null) {
            mailService.sendPasswordResetLink(email.trim(), "$baseUrl/password-reset/confirm?token=$rawToken")
        }
        // 이메일 열거 공격 방지를 위해 계정 존재 여부와 무관하게 동일한 안내를 보여준다.
        model.addAttribute("info", messages.get("passwordReset.info.linkSent"))
        return "password-reset/request"
    }

    /**
     * ★**폼을 열기 전에 링크가 살아 있는지 먼저 본다.** 그전에는 검증 없이 폼을 그려서, 죽은 링크로도
     * 정상처럼 열리고 사용자는 새 비밀번호를 입력한 뒤에야 만료를 알았다. 죽었으면 입력을 시키지 않고
     * 곧바로 재요청 화면으로 보낸다(그 화면이 이 상황의 유일한 다음 행동이다).
     */
    @GetMapping("/password-reset/confirm")
    fun confirmPage(
        @RequestParam token: String,
        model: Model,
    ): String {
        if (!passwordResetService.tokenUsable(token)) {
            model.addAttribute("error", messages.get("error.${ErrorCode.RESET_TOKEN_EXPIRED.name}"))
            return "password-reset/request"
        }
        model.addAttribute("token", token)
        return "password-reset/confirm"
    }

    @PostMapping("/password-reset/confirm")
    fun submitConfirm(
        @RequestParam token: String,
        @RequestParam newPassword: String,
        model: Model,
    ): String =
        try {
            passwordResetService.resetPassword(token, newPassword)
            "redirect:/login?reset=true"
        } catch (ex: AuthException) {
            model.addAttribute("token", token)
            // ★`ex.message` 는 ErrorCode 의 **영문 기본 문구**다. 여기만 i18n 을 건너뛰고 있어서
            //   한국어 화면에 영문 오류가 떴다(JSON API 는 GlobalExceptionHandler 가 제대로 번역한다).
            model.addAttribute("error", messages.userMessageFor(ex))
            "password-reset/confirm"
        }
}
