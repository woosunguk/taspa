package com.taspa.server.selfservice.dto

import java.util.UUID

/**
 * 현재 로그인 사용자의 신원·상태(GET /api/account/me).
 * SPA 가 부팅 시 화면 진입점을 정하는 데 쓰는 최소 집합 — 개인정보는 본인 것만 담고, 조직 목록처럼
 * 화면별로 필요 시점이 다른 데이터는 전용 엔드포인트에 맡긴다.
 */
data class CurrentUserView(
    val userId: UUID,
    val email: String,
    val displayName: String?,
    val emailVerified: Boolean,
    val mfaEnabled: Boolean,
    /** 소셜 전용 계정은 비밀번호가 없다 — 계정 화면이 "설정"과 "변경"을 구분하는 근거. */
    val hasPassword: Boolean,
    /** 플랫폼 관리자(users.role=ADMIN) — 관리 콘솔 진입점 노출 여부. */
    val platformAdmin: Boolean,
    /** ≥1 개 조직의 활성 ORG_ADMIN — 조직 콘솔 진입점 노출 여부. */
    val manageableOrgs: Boolean,
)
