package com.taspa.server.domain.passkey

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * WebAuthn 사용자 핸들. external_id 는 인증기에 노출되는 base64url 랜덤 값으로,
 * users.id 를 인증기/클라이언트에 노출하지 않기 위한 간접 계층이다.
 */
@Entity
@Table(name = "webauthn_user_entities")
class PasskeyUserEntity(
    @Id
    @Column(name = "user_id")
    val userId: UUID,
    @Column(name = "external_id", nullable = false, unique = true, length = 256)
    val externalId: String,
    @Column(name = "name", nullable = false)
    var name: String,
    @Column(name = "display_name", nullable = false)
    var displayName: String,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)
