package com.taspa.server.domain.jwk

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * JWT 서명용 RSA 키 1개당 1행. keyJsonEncrypted 는 개인키를 포함한
 * RSAKey.toJSONString() 을 AES-GCM 암호화한 값이다.
 */
@Entity
@Table(name = "jwk_keys")
class JwkKey(
    @Id
    @Column(name = "kid", length = 64)
    val kid: String,
    @Column(name = "key_json_encrypted", nullable = false, columnDefinition = "TEXT")
    val keyJsonEncrypted: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: JwkKeyStatus,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "activated_at")
    var activatedAt: Instant? = null,
    @Column(name = "retired_at")
    var retiredAt: Instant? = null,
)
