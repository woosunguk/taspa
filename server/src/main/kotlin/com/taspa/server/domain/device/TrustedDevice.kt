package com.taspa.server.domain.device

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 신뢰 기기(MFA 30일 스킵). 쿠키(taspa_td)에는 토큰 원본, DB에는 SHA-256 해시만 저장한다.
 * 사용 시마다 토큰이 회전(tokenHash 갱신)되지만 expiresAt 은 발급 시점 기준 고정이다(sliding 연장 금지).
 */
@Entity
@Table(name = "trusted_devices")
class TrustedDevice(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    var tokenHash: String,
    @Column(name = "ua_label", length = 255)
    val uaLabel: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
) {
    fun isExpired(): Boolean = Instant.now().isAfter(expiresAt)
}
