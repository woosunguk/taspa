package com.taspa.server.domain.verification

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "email_verification_codes")
class EmailVerificationCode(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "code_hash", nullable = false, length = 64)
    val codeHash: String,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Column(name = "consumed_at")
    var consumedAt: Instant? = null,
    /** 이 코드에 대한 불일치 제출 횟수 — 상한 도달 시 코드가 소진(무효화)된다(브루트포스 방지). */
    @Column(name = "failed_attempts", nullable = false)
    var failedAttempts: Int = 0,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) {
    fun isExpired(): Boolean = Instant.now().isAfter(expiresAt)
}
