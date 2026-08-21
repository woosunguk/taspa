package com.taspa.server.admin.dto

import com.taspa.server.domain.user.User
import java.time.Instant
import java.util.UUID

data class AdminUserSummary(
    val id: UUID,
    val email: String,
    val displayName: String?,
    val status: String,
    val role: String,
    val emailVerified: Boolean,
    val mfaEnabled: Boolean,
    val createdAt: Instant,
) {
    companion object {
        fun from(user: User): AdminUserSummary =
            AdminUserSummary(
                id = user.id!!,
                email = user.email,
                displayName = user.displayName,
                status = user.status,
                role = user.role,
                emailVerified = user.emailVerified,
                mfaEnabled = user.mfaEnabled,
                createdAt = user.createdAt,
            )
    }
}
