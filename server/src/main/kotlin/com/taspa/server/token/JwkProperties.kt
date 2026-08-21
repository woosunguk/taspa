package com.taspa.server.token

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "taspa.jwk")
data class JwkProperties(
    /** ACTIVE 키가 활성화된 지 이 기간을 넘기면 회전 대상. Duration 타입("30d" 등). */
    val rotationPeriod: Duration = Duration.ofDays(30),
    /**
     * RETIRED 키 유지 기간. 하한 = 발급 토큰 최대 수명 + 외부 RS 의 JWKS 캐시 TTL.
     * 이 기간이 지나 삭제되면 해당 키로 서명된 토큰은 전부 검증 불가가 된다.
     */
    val retirementGrace: Duration = Duration.ofDays(7),
)
