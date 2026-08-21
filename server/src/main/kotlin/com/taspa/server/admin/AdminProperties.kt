package com.taspa.server.admin

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "taspa.admin")
data class AdminProperties(
    /** 기동 시 ADMIN 으로 승격할 이메일 목록(존재하는 계정만) — 첫 관리자 부트스트랩용. */
    val emails: List<String> = emptyList(),
)
