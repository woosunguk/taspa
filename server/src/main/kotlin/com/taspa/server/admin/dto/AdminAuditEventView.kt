package com.taspa.server.admin.dto

import java.time.Instant
import java.util.UUID

data class AdminAuditEventView(
    val id: UUID,
    val type: String,
    val userId: UUID?,
    /** userId 가 현존 사용자로 해석될 때만 채워진다(탈퇴 계정은 null). */
    val email: String?,
    val detail: String?,
    val createdAt: Instant,
)
