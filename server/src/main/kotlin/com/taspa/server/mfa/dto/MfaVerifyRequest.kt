package com.taspa.server.mfa.dto

import jakarta.validation.constraints.NotBlank

data class MfaVerifyRequest(
    // 메시지는 ValidationMessages[_locale].properties 로 외부화(요청 로케일 기준 해석).
    @field:NotBlank(message = "{mfa.code.required}")
    val code: String,
)
