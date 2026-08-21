package com.taspa.server.account.dto

import com.taspa.server.domain.user.User
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class SignupRequest(
    // 상한 근거는 User.MAX_EMAIL_LENGTH KDoc 참고 — 세션 PRINCIPAL_NAME(VARCHAR 100) 인덱싱 제약.
    @field:Email
    @field:NotBlank
    @field:Size(max = User.MAX_EMAIL_LENGTH)
    val email: String,
    @field:NotBlank
    val password: String,
    val displayName: String? = null,
)
