package com.taspa.server.passkey

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.domain.passkey.PasskeyCredential
import com.taspa.server.domain.passkey.PasskeyCredentialRepository
import com.taspa.server.domain.passkey.PasskeyUserEntityRepository
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.passkey.dto.PasskeyResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class PasskeyService(
    private val passkeyUserEntityRepository: PasskeyUserEntityRepository,
    private val passkeyCredentialRepository: PasskeyCredentialRepository,
    private val userRepository: UserRepository,
    private val auditEventService: AuditEventService,
) {
    @Transactional(readOnly = true)
    fun hasPasskeys(userId: UUID): Boolean {
        val entity = passkeyUserEntityRepository.findById(userId).orElse(null) ?: return false
        return passkeyCredentialRepository.existsByUserEntityExternalId(entity.externalId)
    }

    @Transactional(readOnly = true)
    fun listPasskeys(userId: UUID): List<PasskeyResponse> {
        val entity = passkeyUserEntityRepository.findById(userId).orElse(null) ?: return emptyList()
        return passkeyCredentialRepository
            .findByUserEntityExternalId(entity.externalId)
            .sortedBy { it.createdAt }
            .map { it.toResponse() }
    }

    fun rename(
        userId: UUID,
        credentialId: String,
        label: String,
    ) {
        val credential = ownedCredentialOrThrow(userId, credentialId)
        credential.label = label
        passkeyCredentialRepository.save(credential)
        auditEventService.record("PASSKEY_RENAMED", userId, mapOf("credentialId" to credentialId))
    }

    fun delete(
        userId: UUID,
        credentialId: String,
    ) {
        // 사용자 행 잠금: 소셜 해제(FederationService.unlink)의 "마지막 로그인 수단" 검증과
        // 교차 경쟁하지 않도록 같은 사용자의 수단 변경을 직렬화한다(TOCTOU 방지).
        userRepository.findByIdForUpdate(userId) ?: throw AuthException(ErrorCode.NOT_FOUND)
        val credential = ownedCredentialOrThrow(userId, credentialId)
        passkeyCredentialRepository.delete(credential)
        auditEventService.record("PASSKEY_DELETED", userId, mapOf("credentialId" to credentialId))
    }

    /** 소유권 검사: 다른 사용자의 credential 은 존재 여부를 숨기기 위해 NOT_FOUND 로 응답한다. */
    private fun ownedCredentialOrThrow(
        userId: UUID,
        credentialId: String,
    ): PasskeyCredential {
        val entity =
            passkeyUserEntityRepository.findById(userId).orElse(null)
                ?: throw AuthException(ErrorCode.NOT_FOUND)
        val credential =
            passkeyCredentialRepository.findById(credentialId).orElse(null)
                ?: throw AuthException(ErrorCode.NOT_FOUND)
        if (credential.userEntityExternalId != entity.externalId) {
            throw AuthException(ErrorCode.NOT_FOUND)
        }
        return credential
    }

    private fun PasskeyCredential.toResponse(): PasskeyResponse =
        PasskeyResponse(
            credentialId = credentialId,
            label = label,
            createdAt = createdAt,
            lastUsedAt = lastUsedAt,
        )
}
