package com.taspa.server.stepup

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor

/**
 * RequireRecentAuth 가 붙은 핸들러(메서드 또는 클래스)에 대해 auth_time 신선도를 강제한다.
 *
 * - API 요청(/api/ 이하): AuthException(REAUTH_REQUIRED) → GlobalExceptionHandler 가 401 JSON 으로 응답.
 *   페이지 JS 가 이를 받아 /reauth?continue=... 로 이동시킨다.
 * - HTML 요청: 원래 URL 을 세션에 저장하고 /reauth 로 리다이렉트.
 *
 * 필터 기반이라 애노테이션이 불가능한 /webauthn/register 계열은 StepUpEnforcementFilter 가 맡는다.
 */
@Component
class RecentAuthInterceptor(
    private val stepUpProperties: StepUpProperties,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (handler !is HandlerMethod || !requiresRecentAuth(handler)) {
            return true
        }
        if (StepUp.isRecent(request, stepUpProperties.maxAge)) {
            return true
        }

        if (request.requestURI.startsWith("/api/")) {
            throw AuthException(ErrorCode.REAUTH_REQUIRED)
        }

        val original = request.requestURI + (request.queryString?.let { "?$it" } ?: "")
        request.getSession(true).setAttribute(StepUp.CONTINUE_SESSION_KEY, original)
        response.sendRedirect(request.contextPath + "/reauth")
        return false
    }

    private fun requiresRecentAuth(handler: HandlerMethod): Boolean =
        handler.hasMethodAnnotation(RequireRecentAuth::class.java) ||
            AnnotatedElementUtils.hasAnnotation(handler.beanType, RequireRecentAuth::class.java)
}
