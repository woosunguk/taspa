package com.taspa.server.admin.dto

data class AdminUserDetail(
    val user: AdminUserSummary,
    val passkeyCount: Int,
    val federatedProviders: List<String>,
    val activeSessionCount: Int,
    val recentAuditEvents: List<AdminAuditEventView>,
)
