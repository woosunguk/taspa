package com.taspa.server.admin.dto

data class RoleChangeRequest(
    /** UserRole 상수명(USER | ADMIN). */
    val role: String,
)
