package com.taspa.server.passkey.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class PasskeyRenameRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val label: String,
)
