package com.taspa.server.risk

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "taspa.risk")
data class RiskProperties(
    /** false 면 리스크 평가를 건너뛰어(항상 LOW) 기존 동작 그대로가 된다. */
    val enabled: Boolean = true,
    /** unseenDevice 판정 창 — 이 기간 내 같은 (ip, ua 라벨) 로그인 이력이 없으면 새 환경으로 본다. */
    val unseenWindowDays: Long = 90,
)
