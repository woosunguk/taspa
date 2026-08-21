package com.taspa.server.domain.federation

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 소셜 공급자(google/kakao/naver) 계정과 로컬 계정의 연결.
 * (provider, providerUserId) 는 전역 유일 — 하나의 소셜 계정은 하나의 로컬 계정에만 연결된다.
 */
@Entity
@Table(name = "federated_identities")
class FederatedIdentity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "provider", nullable = false, length = 32)
    val provider: String,
    @Column(name = "provider_user_id", nullable = false)
    val providerUserId: String,
    /** 연결 시점의 공급자 이메일(감사/표시용 — 로컬 이메일과 다를 수 있음). */
    @Column(name = "email_at_link")
    val emailAtLink: String? = null,
    /** 기업 SSO(Stage E) 커넥션 참조. 소셜(구글/카카오/네이버) 연결은 null. */
    @Column(name = "connection_id")
    val connectionId: UUID? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
