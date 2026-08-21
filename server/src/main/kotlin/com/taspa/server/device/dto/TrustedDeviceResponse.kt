package com.taspa.server.device.dto

import java.time.Instant
import java.util.UUID

data class TrustedDeviceResponse(
    val id: UUID,
    val uaLabel: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val expiresAt: Instant,
)
