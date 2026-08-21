package com.taspa.server.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * CORS 는 신뢰 오리진 화이트리스트로만 연다(`taspa.cors.allowed-origins`, 기본 빈 목록 = 전면 거부).
 *
 * 이전의 `allowedOriginPatterns=["*"] + allowCredentials=true` 조합은 모든 오리진에 자격 증명
 * 응답을 반사해, 임의 사이트가 세션 쿠키로 API 응답(세션 목록의 IP/기기 정보 등)을 읽을 수
 * 있었다 — IdP 에서는 브라우저 클라이언트가 same-origin(서버 렌더 페이지)뿐이므로 기본은 닫는다.
 * OAuth2/OIDC 플로우는 리다이렉트·서버 간 통신이라 CORS 와 무관하다. SPA 등 cross-origin
 * 클라이언트가 생기면 해당 오리진만 명시적으로 등록한다.
 */
@Configuration
class CorsConfig {
    @Bean
    fun corsConfigurationSource(
        @Value("\${taspa.cors.allowed-origins:}") allowedOrigins: List<String>,
    ): CorsConfigurationSource {
        val origins = allowedOrigins.map { it.trim() }.filter { it.isNotEmpty() }
        // 허용 오리진은 운영자가 기동 로그로 확인할 수 있어야 한다 — 비어 있으면 cross-origin 이 전면
        // 거부되는데, 그 사실을 모르면 "왜 요청이 403 인가"를 한참 헤매게 된다.
        org.slf4j.LoggerFactory
            .getLogger(CorsConfig::class.java)
            .info("CORS allowed origins: {}", if (origins.isEmpty()) "(none — cross-origin denied)" else origins)
        val config =
            CorsConfiguration().apply {
                this.allowedOrigins = origins
                // PATCH 가 빠져 있으면 그 메서드를 쓰는 화면이 dev 에서 **상시** 깨진다(표시 이름 저장,
                // 패스키 이름 변경, 캘린더 피드 활성 토글 — 실측 3곳). 게다가 증상이 두 겹으로 오도된다:
                // CORS 거절은 403 + 비-JSON 본문이라 `web/lib/api.ts` 의 readError 가 "로그인이 필요합니다"로
                // 정규화하고, 그런데 401 이 아니라 로그인 이동도 일어나지 않는다 — 사용자는 이유를 알 수 없다.
                // 목록을 손으로 관리하는 대신 `MUTATING` 전체를 덮는다.
                allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                allowedHeaders = listOf("*")
                // 화이트리스트 오리진에만 의미가 있다 — 목록이 비면 어떤 cross-origin 도 허용되지 않는다.
                allowCredentials = true
            }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", config)
        }
    }
}
