package com.taspa.server.selfservice

import java.io.Serializable
import java.time.Duration
import java.time.Instant

/**
 * 진행 중인 이메일 변경의 대상(새) 주소를 세션에 묶어 두는 마커.
 *
 * 확인 코드는 email_verification_codes(userId 로 키잉)에 저장되지만 "어느 주소로 바꾸려는지"는
 * 코드 테이블에 없다. 요청(1단계)과 확인(2단계) 사이의 새 주소를 세션에 보관해, 확인 시 클라이언트가
 * 다시 보낸 값이 아니라 코드가 발송된 바로 그 주소로만 전환되도록 강제한다(요청↔확인 대상 불일치 차단).
 *
 * 세션 속성은 JDK 직렬화(BYTEA)되므로 Serializable 이어야 한다(직렬화 불변식).
 */
data class PendingEmailChange(
    val newEmail: String,
    val expiresAt: Instant,
) : Serializable {
    fun isExpired(): Boolean = Instant.now().isAfter(expiresAt)

    companion object {
        const val SESSION_KEY = "TASPA_PENDING_EMAIL_CHANGE"
        val TTL: Duration = Duration.ofMinutes(15)
    }
}
