package com.taspa.server.stepup

import jakarta.servlet.http.HttpServletRequest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * auth_time 패턴(OIDC 의 auth_time 과 동일 개념)의 세션 속성 헬퍼.
 * 모든 완전 인증(비밀번호·MFA·패스키·소셜·매직 링크)과 /reauth 성공이 갱신한다.
 * PendingAuth 와 마찬가지로 SecurityContext 밖(세션 속성)에만 존재한다.
 *
 * auth_time 과 함께 "누구의 인증인지"(userId)도 기록한다 — 이미 인증된 세션에서 같은 사용자의
 * 패스키/소셜 성공은 새 로그인이 아니라 step-up 재인증으로 구분하기 위해서다.
 */
object StepUp {
    const val AUTH_TIME_SESSION_KEY = "TASPA_AUTH_TIME"
    const val AUTH_USER_SESSION_KEY = "TASPA_AUTH_USER"
    const val CONTINUE_SESSION_KEY = "TASPA_REAUTH_CONTINUE"

    fun markAuthTime(
        request: HttpServletRequest,
        userId: UUID,
    ) {
        val session = request.getSession(true)
        session.setAttribute(AUTH_TIME_SESSION_KEY, Instant.now())
        session.setAttribute(AUTH_USER_SESSION_KEY, userId)
    }

    fun authTime(request: HttpServletRequest): Instant? = request.getSession(false)?.getAttribute(AUTH_TIME_SESSION_KEY) as? Instant

    /** 이 세션의 auth_time 이 어느 사용자의 것인지. 재인증(step-up) 여부 판정에 쓴다. */
    fun authUserId(request: HttpServletRequest): UUID? = request.getSession(false)?.getAttribute(AUTH_USER_SESSION_KEY) as? UUID

    /** auth_time 이 없으면(구세션 등) 보수적으로 재인증을 요구한다. */
    fun isRecent(
        request: HttpServletRequest,
        maxAge: Duration,
    ): Boolean {
        val authTime = authTime(request) ?: return false
        return Instant.now().isBefore(authTime.plus(maxAge))
    }

    /**
     * 재인증 후 복귀 경로 정화: 앱 내 절대 경로만 허용한다(open redirect 방지).
     */
    fun sanitizeContinuePath(path: String?): String {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("://")) {
            return "/account"
        }
        return path
    }
}
