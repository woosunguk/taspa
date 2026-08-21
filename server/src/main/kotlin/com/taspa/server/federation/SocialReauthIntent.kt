package com.taspa.server.federation

import java.io.Serializable
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 로그인된 사용자가 /reauth 에서 "소셜로 본인 확인"을 시작했음을 표시하는 세션 마커.
 * SocialLinkIntent 와 같은 이유(oauth2Login 필터가 성공 핸들러 호출 전에 SecurityContext 를
 * 교체한다)로, 시작 엔드포인트(GET /reauth/social/{provider} — 인증 필수)가 세션에 심는다.
 *
 * 소셜 전용 계정(비밀번호·패스키 없음)의 step-up 데드엔드 해소: 연결된 소셜 계정으로
 * 재인증하면 auth_time 만 갱신하고 continuePath 로 복귀한다 — 새 로그인으로 취급하지 않는다.
 */
data class SocialReauthIntent(
    val userId: UUID,
    val provider: String,
    val continuePath: String,
    val createdAt: Instant = Instant.now(),
) : Serializable {
    fun isExpired(): Boolean = Instant.now().isAfter(createdAt.plus(TTL))

    companion object {
        const val SESSION_KEY = "TASPA_SOCIAL_REAUTH_INTENT"
        val TTL: Duration = Duration.ofMinutes(10)
    }
}
