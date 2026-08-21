package com.taspa.server.passkey

import com.fasterxml.jackson.databind.ObjectMapper
import com.taspa.server.audit.AuditEventService
import com.taspa.server.credential.AccountLockoutService
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.login.LoginEventService
import com.taspa.server.session.SessionMetadata
import com.taspa.server.stepup.StepUp
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.savedrequest.RequestCache
import org.springframework.stereotype.Component

/**
 * 패스키(POST /login/webauthn) 성공 핸들러. 기본 HttpMessageConverterAuthenticationSuccessHandler 를
 * 대체한다. 기본 핸들러의 문제(6.4.4 실측):
 *  1) saved request 가 없으면 redirectUrl 이 "/" 로 고정되는데, 직접 로그인 경로(로그아웃 후 /login)에는
 *     saved request 가 없어 사용자가 매핑 없는 "/" 에 착지한다 → /account 로 보정한다.
 *  2) 비밀번호 경로(MfaAwareAuthenticationSuccessHandler)와 달리 LOGIN_SUCCESS 감사 로그와
 *     잠금 카운터 리셋이 없다 → 동일하게 수행한다.
 * Stage B: auth_time(step-up 기준)과 login_events(새 로그인 알림 판정)도 기록한다.
 * 이미 인증된 세션에서의 패스키 성공(= step-up 재인증, /reauth 의 패스키 버튼)도 같은 경로로
 * auth_time 이 갱신된다 — 재인증 페이지 JS 가 continue 경로로 복귀시킨다.
 * 응답 JSON 계약은 벤더링 JS(webauthn.js)가 기대하는 {"redirectUrl": ..., "authenticated": true} 를 유지한다.
 * saved request 가 있으면(OIDC continuation) 기본 핸들러와 동일하게 그 URL 로 보내고 캐시를 비운다.
 */
@Component
class PasskeyAuthenticationSuccessHandler(
    private val requestCache: RequestCache,
    private val userRepository: UserRepository,
    private val accountLockoutService: AccountLockoutService,
    private val auditEventService: AuditEventService,
    private val loginEventService: LoginEventService,
    private val objectMapper: ObjectMapper,
) : AuthenticationSuccessHandler {
    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        userRepository.findByEmail(authentication.name)?.let { user ->
            accountLockoutService.recordSuccessfulLogin(user)
            // 이미 이 사용자로 완전 인증된 세션에서의 패스키 성공 = step-up 재인증(/reauth 의 패스키 버튼).
            // 새 로그인이 아니므로 login_events 를 남기지 않는다 — 남기면 이력이 오염되고, IP 가 바뀐
            // 재인증에서 "새 로그인이 감지되었습니다" 알림이 오발송된다. auth_time 만 갱신한다.
            val isReauth = StepUp.authTime(request) != null && StepUp.authUserId(request) == user.id
            StepUp.markAuthTime(request, user.id!!)
            // 패스키 로그인은 establishSecurityContext 를 거치지 않으므로 세션 메타를 여기서 기록한다.
            SessionMetadata.record(request)
            if (isReauth) {
                auditEventService.record("REAUTH_SUCCESS", user.id, mapOf("method" to "passkey"))
            } else {
                auditEventService.record(
                    "LOGIN_SUCCESS",
                    user.id,
                    mapOf("email" to user.email, "method" to "passkey"),
                )
                loginEventService.recordLogin(request, user, "passkey")
            }
        }

        val savedRequest = requestCache.getRequest(request, response)
        val redirectUrl = savedRequest?.redirectUrl ?: (request.contextPath + "/account")
        requestCache.removeRequest(request, response)

        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.writer, AuthenticationSuccessResponse(redirectUrl))
    }

    /** 벤더링 JS 계약: {"redirectUrl": "...", "authenticated": true} */
    data class AuthenticationSuccessResponse(
        val redirectUrl: String,
        val authenticated: Boolean = true,
    )
}
