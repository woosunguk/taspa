package com.taspa.server.passkey.dto

import java.time.Instant

data class PasskeyResponse(
    val credentialId: String,
    val label: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
)
