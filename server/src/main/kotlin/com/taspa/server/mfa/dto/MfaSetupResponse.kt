package com.taspa.server.mfa.dto

data class MfaSetupResponse(
    val qrCodeDataUri: String,
    val secret: String,
)
