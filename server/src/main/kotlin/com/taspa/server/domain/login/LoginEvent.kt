package com.taspa.server.domain.login

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 완전 인증(로그인) 1건당 1행. method: password / mfa / passkey / social:{provider} / magic.
 * 최근 30일 내 같은 (ip, uaLabel) 이력이 없으면 "새 로그인" 알림 대상이 된다 —
 * 이벤트 자체가 기록되므로 같은 기기의 재로그인은 자연히 재발송이 억제된다.
 */
@Entity
@Table(name = "login_events")
class LoginEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "ip", length = 64)
    val ip: String? = null,
    @Column(name = "ua_label", length = 255)
    val uaLabel: String? = null,
    @Column(name = "method", nullable = false, length = 32)
    val method: String,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
