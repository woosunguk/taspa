package com.taspa.server.federation.dto

import java.time.Instant

data class FederationResponse(
    val provider: String,
    val providerLabel: String,
    val emailAtLink: String?,
    val createdAt: Instant,
)
