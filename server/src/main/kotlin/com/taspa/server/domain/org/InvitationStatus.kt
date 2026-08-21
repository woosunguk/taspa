package com.taspa.server.domain.org

/**
 * 조직 초대 상태. PENDING 만 수락 가능하다.
 *  - PENDING: 발송됨, 수락 대기.
 *  - ACCEPTED: 수락 완료(멤버십 생성). 재사용 불가(단일 사용).
 *  - REVOKED: 관리자가 취소함.
 *  - EXPIRED: 만료 시각 경과(lazy 또는 정리 잡에서 전이).
 */
enum class InvitationStatus {
    PENDING,
    ACCEPTED,
    REVOKED,
    EXPIRED,
}
