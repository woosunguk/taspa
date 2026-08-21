package com.taspa.server.stepup

import com.fasterxml.jackson.databind.ObjectMapper
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.common.exception.ErrorResponse
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 패스키 등록 엔드포인트(POST /webauthn/register/options, POST /webauthn/register)는
 * Spring Security 필터가 직접 처리하므로 @RequireRecentAuth 인터셉터가 개입할 수 없다.
 * 이 필터를 WebAuthnRegistrationFilter 앞에 배치해 step-up 을 강제한다.
 *
 * 미인증 요청은 그대로 통과시킨다 — 인가/거절은 기존 필터 체인의 몫이다(계약 유지).
 */
class StepUpEnforcementFilter(
    private val stepUpProperties: StepUpProperties,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    private val protectedPaths = setOf("/webauthn/register/options", "/webauthn/register")

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !(request.method == "POST" && request.requestURI in protectedPaths)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authentication = SecurityContextHolder.getContext().authentication
        val authenticated =
            authentication != null &&
                authentication !is AnonymousAuthenticationToken &&
                authentication.isAuthenticated
        if (!authenticated || StepUp.isRecent(request, stepUpProperties.maxAge)) {
            filterChain.doFilter(request, response)
            return
        }

        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(
            response.writer,
            ErrorResponse(errorCode = ErrorCode.REAUTH_REQUIRED.name, message = ErrorCode.REAUTH_REQUIRED.message),
        )
    }
}
