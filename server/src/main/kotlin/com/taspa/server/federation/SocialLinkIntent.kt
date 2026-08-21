package com.taspa.server.federation

import java.io.Serializable
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 로그인된 사용자가 계정 페이지에서 "연결"을 시작했음을 표시하는 세션 마커.
 *
 * oauth2Login 필터는 성공 핸들러 호출 전에 SecurityContext 를 OAuth2AuthenticationToken 으로
 * 교체·저장하므로, 핸들러 시점에는 "직전까지 로그인돼 있던 로컬 사용자"를 컨텍스트로 알 수 없다.
 * 그래서 연결 시작 엔드포인트(GET /account/federations/link/{provider} — 인증 필수)가 이 마커를
 * 세션에 심고 /oauth2/authorization/{provider} 로 보낸다. 세션 속성은 세션 ID 교체와 무관하게 유지된다.
 */
data class SocialLinkIntent(
    val userId: UUID,
    val provider: String,
    val createdAt: Instant = Instant.now(),
) : Serializable {
    fun isExpired(): Boolean = Instant.now().isAfter(createdAt.plus(TTL))

    companion object {
        const val SESSION_KEY = "TASPA_SOCIAL_LINK_INTENT"
        val TTL: Duration = Duration.ofMinutes(10)
    }
}
