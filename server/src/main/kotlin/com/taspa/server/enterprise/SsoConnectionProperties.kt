package com.taspa.server.enterprise

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 기업 SSO 커넥션 설정. cacheTtl 은 DB→ClientRegistration/RelyingPartyRegistration 변환 결과의
 * 짧은 캐시 수명 — 관리자가 커넥션을 바꾼 뒤 반영까지의 최대 지연이다(기본 30초).
 */
@ConfigurationProperties(prefix = "taspa.sso")
data class SsoConnectionProperties(
    val cacheTtl: Duration = Duration.ofSeconds(30),
)
