package com.taspa.server.domain.sso

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 이메일 도메인 → SSO 커넥션 매핑(HRD, Home Realm Discovery).
 *
 * domain 은 소문자·trim 정규화된 값이며 PK 다(도메인은 단일 커넥션에만 매핑).
 * verified=true 인 도메인만 로그인 라우팅과 도메인 일치 강제(정책 5)에 쓰인다.
 */
@Entity
@Table(name = "sso_domains")
class SsoDomain(
    @Id
    @Column(name = "domain", nullable = false, length = 255)
    val domain: String,
    @Column(name = "connection_id", nullable = false)
    val connectionId: UUID,
    @Column(name = "verified", nullable = false)
    var verified: Boolean = false,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
