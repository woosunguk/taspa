package com.taspa.server.domain.credential

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 매직 링크(이메일 로그인) 토큰. 단일 사용(usedAt 마킹), 기본 15분 만료. 해시만 저장한다. */
@Entity
@Table(name = "magic_link_tokens")
class MagicLinkToken(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    val tokenHash: String,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Column(name = "used_at")
    var usedAt: Instant? = null,
) {
    fun isExpired(): Boolean = Instant.now().isAfter(expiresAt)
}
