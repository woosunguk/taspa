package com.taspa.server.device

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "taspa.trusted-device")
data class TrustedDeviceProperties(
    /** 만료는 발급 시점 기준 고정(OWASP 권장) — 사용해도 연장하지 않는다. */
    val expiryDays: Long = 30,
)
