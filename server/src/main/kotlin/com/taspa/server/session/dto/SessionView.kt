package com.taspa.server.session.dto

import java.time.Instant

/**
 * 활성 세션 표시용 뷰. publicId 는 세션 ID 의 SHA-256 hex 앞 16자 —
 * 세션 ID 원문은 화면/API 에 절대 노출하지 않는다(탈취 시 세션 하이재킹 직결).
 */
data class SessionView(
    val publicId: String,
    val ip: String?,
    val browser: String?,
    val createdAt: Instant,
    val lastActiveAt: Instant,
    val current: Boolean,
)
