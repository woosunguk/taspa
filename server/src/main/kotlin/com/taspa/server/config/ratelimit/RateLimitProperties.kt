package com.taspa.server.config.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 인증 엔드포인트 rate limit 설정. 기본 off — prod 프로파일(application-prod.yml)에서만 켠다.
 * (ip, 엔드포인트그룹)당 토큰 버킷: capacity 순간 허용치, refillPeriod 마다 refillTokens 보충.
 */
@ConfigurationProperties(prefix = "taspa.rate-limit")
data class RateLimitProperties(
    val enabled: Boolean = false,
    val capacity: Long = 20,
    val refillTokens: Long = 20,
    val refillPeriodSeconds: Long = 60,
)
