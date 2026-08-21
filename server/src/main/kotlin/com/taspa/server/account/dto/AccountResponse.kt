package com.taspa.server.account.dto

import com.taspa.server.domain.user.User

data class AccountResponse(
    val id: String,
    val email: String,
    val status: String,
    val emailVerified: Boolean,
) {
    companion object {
        fun from(user: User): AccountResponse =
            AccountResponse(
                id = user.id.toString(),
                email = user.email,
                status = user.status,
                emailVerified = user.emailVerified,
            )
    }
}
