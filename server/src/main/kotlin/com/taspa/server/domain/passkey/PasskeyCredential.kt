package com.taspa.server.domain.passkey

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Spring Security `CredentialRecord` 14필드의 영속 표현.
 * Bytes 값은 base64url 문자열로, 공개키(COSE)/attestation 은 BYTEA 로 저장한다.
 */
@Entity
@Table(name = "webauthn_credentials")
class PasskeyCredential(
    @Id
    @Column(name = "credential_id", length = 1024)
    val credentialId: String,
    @Column(name = "user_entity_external_id", nullable = false, length = 256)
    val userEntityExternalId: String,
    @Column(name = "credential_type", nullable = false, length = 32)
    var credentialType: String,
    @Column(name = "public_key_cose", nullable = false)
    var publicKeyCose: ByteArray,
    @Column(name = "signature_count", nullable = false)
    var signatureCount: Long = 0,
    @Column(name = "uv_initialized", nullable = false)
    var uvInitialized: Boolean = false,
    /** AuthenticatorTransport 값(csv, 예: "internal,hybrid"). 없으면 null. */
    @Column(name = "transports")
    var transports: String? = null,
    @Column(name = "backup_eligible", nullable = false)
    var backupEligible: Boolean = false,
    @Column(name = "backup_state", nullable = false)
    var backupState: Boolean = false,
    @Column(name = "attestation_object")
    var attestationObject: ByteArray? = null,
    @Column(name = "attestation_client_data_json")
    var attestationClientDataJson: ByteArray? = null,
    @Column(name = "label", nullable = false, length = 100)
    var label: String,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null,
)
