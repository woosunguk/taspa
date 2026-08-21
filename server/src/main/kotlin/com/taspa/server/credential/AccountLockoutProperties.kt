package com.taspa.server.credential

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "taspa.account-lockout")
data class AccountLockoutProperties(
    val maxAttempts: Int = 5,
    val lockoutDurationMinutes: Long = 30,
)
