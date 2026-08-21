package com.taspa.server.login

import com.taspa.server.audit.AuditEventService
import com.taspa.server.credential.AccountLockoutService
import com.taspa.server.domain.user.UserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.LockedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.stereotype.Component

/**
 * 비밀번호 인증 실패 처리. 자격 증명 오류는 lockout 실패 카운트를 올리고, 잠긴 계정은 안내 메시지로 리다이렉트.
 * 국문 에러 메시지는 /login/password 페이지에서 error 파라미터로 렌더링된다.
 */
@Component
class LoginFailureHandler(
    private val userRepository: UserRepository,
    private val accountLockoutService: AccountLockoutService,
    private val auditEventService: AuditEventService,
) : AuthenticationFailureHandler {
    override fun onAuthenticationFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        val username = request.getParameter("username")

        val redirect =
            if (exception is LockedException) {
                "/login/password?error=locked"
            } else {
                if (username != null) {
                    val user = userRepository.findByEmail(username)
                    if (user != null) {
                        accountLockoutService.recordFailedAttempt(user)
                        auditEventService.record("LOGIN_FAILURE", user.id, mapOf("email" to username))
                        if (user.lockedUntil != null) {
                            response.sendRedirect(request.contextPath + "/login/password?error=locked")
                            return
                        }
                    } else {
                        auditEventService.record("LOGIN_FAILURE", null, mapOf("email" to username))
                    }
                }
                "/login/password?error"
            }
        response.sendRedirect(request.contextPath + redirect)
    }
}
