package com.taspa.server.federation

import java.io.Serializable

/**
 * 소셜 게이트(SOCIAL_LINK/SOCIAL_EMAIL) 진행 중 세션에 보관하는 공급자 신원.
 * PendingAuth 와 같은 원칙 — SecurityContext 밖(세션 속성)에만 존재한다.
 */
data class PendingSocialLink(
    val provider: String,
    val providerUserId: String,
    val email: String?,
    val displayName: String?,
) : Serializable {
    companion object {
        const val SESSION_KEY = "TASPA_PENDING_SOCIAL_LINK"
    }
}
