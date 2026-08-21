package com.taspa.server.passkey

import com.taspa.server.domain.passkey.PasskeyCredential
import com.taspa.server.domain.passkey.PasskeyCredentialRepository
import com.taspa.server.domain.passkey.PasskeyUserEntityRepository
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.webauthn.api.AuthenticatorTransport
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.security.web.webauthn.api.CredentialRecord
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType
import org.springframework.security.web.webauthn.management.UserCredentialRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Spring Security WebAuthn 의 credential 저장소 어댑터.
 * CredentialRecord 14필드를 PasskeyCredential 로 왕복 매핑한다.
 * save() 는 upsert — 인증 성공 시 SS 가 signatureCount/lastUsed 를 갱신해 다시 save() 를 호출한다.
 */
@Component
@Transactional
class JpaUserCredentialRepository(
    private val passkeyCredentialRepository: PasskeyCredentialRepository,
    private val passkeyUserEntityRepository: PasskeyUserEntityRepository,
) : UserCredentialRepository {
    private val log = LoggerFactory.getLogger(JpaUserCredentialRepository::class.java)

    @Transactional(readOnly = true)
    override fun findByCredentialId(credentialId: Bytes): CredentialRecord? =
        passkeyCredentialRepository
            .findById(credentialId.toBase64UrlString())
            .map { it.toRecord() }
            .orElse(null)

    @Transactional(readOnly = true)
    override fun findByUserId(userId: Bytes): List<CredentialRecord> =
        passkeyCredentialRepository
            .findByUserEntityExternalId(userId.toBase64UrlString())
            .map { it.toRecord() }

    override fun save(credentialRecord: CredentialRecord) {
        val id = credentialRecord.credentialId.toBase64UrlString()
        // label 컬럼은 VARCHAR(100). SS 등록 필터(WebAuthnRegistrationFilter)는 저장 중 예외를 400 으로
        // 매핑하지 않으므로(6.4.4 바이트코드 실측: registerCredential 에 try/catch 없음) 예외를 던지면
        // 직접 호출(curl 등)로 101자 이상 label 전송 시 500 이 된다. 절단으로 방어한다.
        // (PATCH /api/passkeys 는 @Size(max=100) 로 요청 단계에서 400 처리 — 화면은 maxlength=100.)
        val label = credentialRecord.label.take(LABEL_MAX_LENGTH)
        val existing = passkeyCredentialRepository.findById(id).orElse(null)
        if (existing != null) {
            existing.signatureCount = credentialRecord.signatureCount
            existing.uvInitialized = credentialRecord.isUvInitialized
            existing.backupEligible = credentialRecord.isBackupEligible
            existing.backupState = credentialRecord.isBackupState
            existing.lastUsedAt = credentialRecord.lastUsed
            existing.label = label
            passkeyCredentialRepository.save(existing)
            return
        }
        passkeyCredentialRepository.save(
            PasskeyCredential(
                credentialId = id,
                userEntityExternalId = credentialRecord.userEntityUserId.toBase64UrlString(),
                credentialType = credentialRecord.credentialType.value,
                publicKeyCose = credentialRecord.publicKey.bytes,
                signatureCount = credentialRecord.signatureCount,
                uvInitialized = credentialRecord.isUvInitialized,
                transports =
                    credentialRecord.transports
                        ?.takeIf { it.isNotEmpty() }
                        ?.joinToString(",") { it.value },
                backupEligible = credentialRecord.isBackupEligible,
                backupState = credentialRecord.isBackupState,
                attestationObject = credentialRecord.attestationObject?.bytes,
                attestationClientDataJson = credentialRecord.attestationClientDataJSON?.bytes,
                label = label,
                createdAt = credentialRecord.created,
                lastUsedAt = credentialRecord.lastUsed,
            ),
        )
    }

    /**
     * 소유권 가드: Spring Security 기본 DELETE /webauthn/register/{id} 는 소유권 검사를 하지 않을
     * 가능성이 있어, 현재 인증 사용자가 소유하지 않은 credential 삭제 요청은 무시하고 WARN 만 남긴다.
     */
    override fun delete(credentialId: Bytes) {
        val id = credentialId.toBase64UrlString()
        val credential = passkeyCredentialRepository.findById(id).orElse(null) ?: return
        val ownerEmail = passkeyUserEntityRepository.findByExternalId(credential.userEntityExternalId)?.name
        val authentication = SecurityContextHolder.getContext().authentication
        val requester =
            authentication
                ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
                ?.name
        if (requester == null || requester != ownerEmail) {
            log.warn(
                "passkey delete denied: requester={} does not own credentialId={}",
                requester,
                id,
            )
            return
        }
        passkeyCredentialRepository.deleteById(id)
    }

    private fun PasskeyCredential.toRecord(): CredentialRecord {
        val builder =
            ImmutableCredentialRecord
                .builder()
                .credentialType(PublicKeyCredentialType.valueOf(credentialType))
                .credentialId(Bytes.fromBase64(credentialId))
                .userEntityUserId(Bytes.fromBase64(userEntityExternalId))
                .publicKey(ImmutablePublicKeyCose(publicKeyCose))
                .signatureCount(signatureCount)
                .uvInitialized(uvInitialized)
                .transports(
                    transports
                        ?.split(",")
                        ?.filter { it.isNotBlank() }
                        ?.map { AuthenticatorTransport.valueOf(it) }
                        ?.toSet()
                        ?: emptySet(),
                ).backupEligible(backupEligible)
                .backupState(backupState)
                .label(label)
                .created(createdAt)
                .lastUsed(lastUsedAt)
        attestationObject?.let { builder.attestationObject(Bytes(it)) }
        attestationClientDataJson?.let { builder.attestationClientDataJSON(Bytes(it)) }
        return builder.build()
    }

    companion object {
        /** webauthn_credentials.label VARCHAR(100) 과 PasskeyRenameRequest 의 @Size(max=100) 에 맞춘다. */
        private const val LABEL_MAX_LENGTH = 100
    }
}
