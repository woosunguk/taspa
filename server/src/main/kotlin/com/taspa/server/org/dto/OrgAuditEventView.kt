package com.taspa.server.org.dto

import java.time.Instant
import java.util.UUID

/**
 * 조직 스코프 활동로그 1행 — 관리자 audit(AdminAuditEventView)와 동형. 행위자(userId)와 그 이메일,
 * 이벤트 유형·상세(JSON 문자열)·시각을 담는다. org_id 는 조회 필터에만 쓰이므로 응답에는 노출하지 않는다.
 */
data class OrgAuditEventView(
    val id: UUID,
    val userId: UUID?,
    /** userId 가 현존 사용자로 해석될 때만 채워진다(탈퇴 계정은 null). */
    val email: String?,
    val type: String,
    val detail: String?,
    val createdAt: Instant,
    /**
     * 행위자가 플랫폼 운영자(users.role=ADMIN)면 true. 이때 userId·email 은 마스킹(null)되어
     * 테넌트 ORG_ADMIN 에게 내부 운영자 신원(이메일)이 노출되지 않는다 — 콘솔은 대신 역할 라벨만 표시한다.
     */
    val platformActor: Boolean = false,
)
