package com.taspa.server.mfa

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.crypto.AesEncryptionService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.device.TrustedDeviceService
import com.taspa.server.domain.mfa.BackupCode
import com.taspa.server.domain.mfa.BackupCodeRepository
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.mfa.dto.MfaSetupResponse
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

@Service
class MfaService(
    private val userRepository: UserRepository,
    private val backupCodeRepository: BackupCodeRepository,
    private val totpService: TotpService,
    @Qualifier("mfaEncryptionService")
    private val aesEncryptionService: AesEncryptionService,
    private val passwordEncoder: PasswordEncoder,
    private val auditEventService: AuditEventService,
    private val trustedDeviceService: TrustedDeviceService,
    @Value("\${taspa.mfa.backup-code-count:10}")
    private val backupCodeCount: Int,
) {
    companion object {
        private const val BACKUP_CODE_LENGTH = 8
        private val ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray()
        private val secureRandom = SecureRandom()
    }

    /** MFA 등록 1단계: 시크릿을 발급/암호화 저장(아직 활성화 아님)하고 QR 코드를 반환한다. */
    @Transactional
    fun setup(userId: UUID): MfaSetupResponse {
        val user = userRepository.findById(userId).orElseThrow { AuthException(ErrorCode.USER_NOT_FOUND) }
        if (user.mfaEnabled) {
            throw AuthException(ErrorCode.MFA_ALREADY_ENABLED)
        }

        val secret = totpService.generateSecret()
        user.mfaSecretEncrypted = aesEncryptionService.encrypt(secret)
        userRepository.save(user)

        return MfaSetupResponse(
            qrCodeDataUri = totpService.generateQrCodeDataUri(user.email, secret),
            secret = secret,
        )
    }

    /** MFA 등록 2단계: 코드 확인에 성공하면 활성화하고 백업 코드 목록(1회 표시)을 반환한다. */
    @Transactional
    fun activate(
        userId: UUID,
        code: String,
    ): List<String> {
        val user = userRepository.findById(userId).orElseThrow { AuthException(ErrorCode.USER_NOT_FOUND) }
        val encrypted = user.mfaSecretEncrypted ?: throw AuthException(ErrorCode.MFA_NOT_SETUP)

        val secret = aesEncryptionService.decrypt(encrypted)
        if (!totpService.verifyCode(secret, code)) {
            throw AuthException(ErrorCode.MFA_INVALID_CODE)
        }

        user.mfaEnabled = true
        userRepository.save(user)
        // MFA (재)등록 시 기존 신뢰 기기를 모두 폐기한다 — 새 팩터 기준으로 다시 신뢰를 쌓는다(무효화 트리거).
        trustedDeviceService.revokeAll(userId)
        auditEventService.record("MFA_ENABLED", user.id, mapOf("email" to user.email))
        return generateBackupCodes(userId)
    }

    @Transactional
    fun disable(
        userId: UUID,
        code: String,
    ) {
        val user = userRepository.findById(userId).orElseThrow { AuthException(ErrorCode.USER_NOT_FOUND) }
        if (!user.mfaEnabled) {
            throw AuthException(ErrorCode.MFA_NOT_ENABLED)
        }

        val secret = aesEncryptionService.decrypt(user.mfaSecretEncrypted!!)
        if (!totpService.verifyCode(secret, code)) {
            throw AuthException(ErrorCode.MFA_INVALID_CODE)
        }

        user.mfaEnabled = false
        user.mfaSecretEncrypted = null
        userRepository.save(user)
        backupCodeRepository.deleteAllByUserId(userId)
        // MFA 해제 시 신뢰 기기도 의미를 잃으므로 전체 폐기한다(무효화 트리거).
        trustedDeviceService.revokeAll(userId)
        auditEventService.record("MFA_DISABLED", user.id, mapOf("email" to user.email))
    }

    /** 로그인 게이트에서 사용: 저장된 시크릿으로 TOTP 코드를 검증한다. */
    fun verifyCode(
        userId: UUID,
        code: String,
    ): Boolean {
        val user = userRepository.findById(userId).orElseThrow { AuthException(ErrorCode.USER_NOT_FOUND) }
        val encrypted = user.mfaSecretEncrypted ?: return false
        return totpService.verifyCode(aesEncryptionService.decrypt(encrypted), code)
    }

    /** 로그인 게이트의 "다른 방법 시도": 백업 코드를 검증하고 1회성으로 소진 처리한다. */
    @Transactional
    fun verifyBackupCode(
        userId: UUID,
        code: String,
    ): Boolean {
        val unusedCodes = backupCodeRepository.findByUserIdAndUsedAtIsNull(userId)
        for (backupCode in unusedCodes) {
            if (passwordEncoder.matches(code, backupCode.codeHash)) {
                backupCode.usedAt = Instant.now()
                backupCodeRepository.save(backupCode)
                return true
            }
        }
        return false
    }

    @Transactional
    fun regenerateBackupCodes(userId: UUID): List<String> {
        val user = userRepository.findById(userId).orElseThrow { AuthException(ErrorCode.USER_NOT_FOUND) }
        if (!user.mfaEnabled) {
            throw AuthException(ErrorCode.MFA_NOT_ENABLED)
        }
        return generateBackupCodes(userId)
    }

    private fun generateBackupCodes(userId: UUID): List<String> {
        backupCodeRepository.deleteAllByUserId(userId)

        val rawCodes =
            (1..backupCodeCount).map {
                buildString {
                    repeat(BACKUP_CODE_LENGTH) {
                        append(ALPHANUMERIC[secureRandom.nextInt(ALPHANUMERIC.size)])
                    }
                }
            }

        backupCodeRepository.saveAll(
            rawCodes.map { code -> BackupCode(userId = userId, codeHash = passwordEncoder.encode(code)) },
        )
        return rawCodes
    }
}
