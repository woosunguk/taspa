package com.taspa.server.domain.meal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 식권 QR 불투명 핸들. 원문 토큰(256bit 랜덤)은 응답으로만 나가고 SHA-256 해시만 저장한다
 * (SecureTokenGenerator — trusted_devices·magic_link 와 동일 패턴). 60초 TTL, 단일사용(used_at).
 * 서명 JWS(오프라인 검증)는 후속 단계 — 이 핸들은 서버 왕복 검증 전제라 상태 없는 위조가 불가능하다.
 */
@Entity
@Table(name = "meal_qr_tokens")
class MealQrToken(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    val tokenHash: String,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "org_id", nullable = false)
    val orgId: UUID,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Column(name = "used_at")
    var usedAt: Instant? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
