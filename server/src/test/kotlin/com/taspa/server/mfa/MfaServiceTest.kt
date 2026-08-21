package com.taspa.server.mfa

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.crypto.AesEncryptionService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.device.TrustedDeviceService
import com.taspa.server.domain.mfa.BackupCode
import com.taspa.server.domain.mfa.BackupCodeRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.Optional
import java.util.UUID

class MfaServiceTest {
    private val userRepository = mockk<UserRepository>()
    private val backupCodeRepository = mockk<BackupCodeRepository>(relaxed = true)
    private val totpService = mockk<TotpService>()
    private val aesEncryptionService = AesEncryptionService("test-encryption-key-do-not-use")
    private val passwordEncoder = BCryptPasswordEncoder(4)
    private val auditEventService = mockk<AuditEventService>(relaxed = true)
    private val trustedDeviceService = mockk<TrustedDeviceService>(relaxed = true)

    private lateinit var mfaService: MfaService

    private val userId = UUID.randomUUID()
    private val testSecret = "JBSWY3DPEHPK3PXP"

    @BeforeEach
    fun setUp() {
        every { userRepository.save(any()) } answers { firstArg() }
        every { backupCodeRepository.save(any()) } answers { firstArg() }
        mfaService =
            MfaService(
                userRepository = userRepository,
                backupCodeRepository = backupCodeRepository,
                totpService = totpService,
                aesEncryptionService = aesEncryptionService,
                passwordEncoder = passwordEncoder,
                auditEventService = auditEventService,
                trustedDeviceService = trustedDeviceService,
                backupCodeCount = 10,
            )
    }

    @Test
    fun `setup stores encrypted secret and returns qr code`() {
        val user = createUser(mfaEnabled = false)
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { totpService.generateSecret() } returns testSecret
        every { totpService.generateQrCodeDataUri(any(), any()) } returns "data:image/png;base64,AAAA"

        val response = mfaService.setup(userId)

        assertThat(response.secret).isEqualTo(testSecret)
        assertThat(response.qrCodeDataUri).startsWith("data:image/png;base64,")
        assertThat(user.mfaSecretEncrypted).isNotNull()
        // 저장된 시크릿은 복호화하면 원본과 일치한다.
        assertThat(aesEncryptionService.decrypt(user.mfaSecretEncrypted!!)).isEqualTo(testSecret)
    }

    @Test
    fun `setup throws when MFA already enabled`() {
        val user = createUser(mfaEnabled = true)
        every { userRepository.findById(userId) } returns Optional.of(user)

        assertThatThrownBy { mfaService.setup(userId) }
            .isInstanceOf(AuthException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.MFA_ALREADY_ENABLED)
    }

    @Test
    fun `activate enables MFA and returns ten backup codes on valid code`() {
        val encrypted = aesEncryptionService.encrypt(testSecret)
        val user = createUser(mfaEnabled = false, mfaSecretEncrypted = encrypted)
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { totpService.verifyCode(testSecret, "123456") } returns true
        val saved = slot<List<BackupCode>>()
        every { backupCodeRepository.saveAll(capture(saved)) } answers { firstArg() }

        val codes = mfaService.activate(userId, "123456")

        assertThat(user.mfaEnabled).isTrue()
        assertThat(codes).hasSize(10)
        assertThat(codes).allMatch { it.length == 8 }
        assertThat(saved.captured).hasSize(10)
    }

    @Test
    fun `activate throws when secret not set up`() {
        val user = createUser(mfaEnabled = false, mfaSecretEncrypted = null)
        every { userRepository.findById(userId) } returns Optional.of(user)

        assertThatThrownBy { mfaService.activate(userId, "123456") }
            .isInstanceOf(AuthException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.MFA_NOT_SETUP)
    }

    @Test
    fun `activate throws on invalid code`() {
        val encrypted = aesEncryptionService.encrypt(testSecret)
        val user = createUser(mfaEnabled = false, mfaSecretEncrypted = encrypted)
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { totpService.verifyCode(testSecret, "000000") } returns false

        assertThatThrownBy { mfaService.activate(userId, "000000") }
            .isInstanceOf(AuthException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.MFA_INVALID_CODE)
        assertThat(user.mfaEnabled).isFalse()
    }

    @Test
    fun `verifyBackupCode consumes a matching code once`() {
        val rawCode = "abcd1234"
        val backupCode = BackupCode(userId = userId, codeHash = passwordEncoder.encode(rawCode))
        every { backupCodeRepository.findByUserIdAndUsedAtIsNull(userId) } returns listOf(backupCode)

        val result = mfaService.verifyBackupCode(userId, rawCode)

        assertThat(result).isTrue()
        assertThat(backupCode.usedAt).isNotNull()
        verify { backupCodeRepository.save(backupCode) }
    }

    @Test
    fun `verifyBackupCode returns false when no unused code matches`() {
        every { backupCodeRepository.findByUserIdAndUsedAtIsNull(userId) } returns emptyList()

        assertThat(mfaService.verifyBackupCode(userId, "nomatch1")).isFalse()
    }

    @Test
    fun `disable clears MFA state and deletes backup codes`() {
        val encrypted = aesEncryptionService.encrypt(testSecret)
        val user = createUser(mfaEnabled = true, mfaSecretEncrypted = encrypted)
        every { userRepository.findById(userId) } returns Optional.of(user)
        every { totpService.verifyCode(testSecret, "123456") } returns true

        mfaService.disable(userId, "123456")

        assertThat(user.mfaEnabled).isFalse()
        assertThat(user.mfaSecretEncrypted).isNull()
        verify { backupCodeRepository.deleteAllByUserId(userId) }
        // MFA 해제는 신뢰 기기 전체 폐기 트리거다.
        verify { trustedDeviceService.revokeAll(userId) }
    }

    private fun createUser(
        mfaEnabled: Boolean = false,
        mfaSecretEncrypted: String? = null,
    ): User =
        User(
            id = userId,
            email = "test@example.com",
            passwordHash = "hashed",
            mfaEnabled = mfaEnabled,
            mfaSecretEncrypted = mfaSecretEncrypted,
        )
}
