package com.taspa.server.maintenance

import com.taspa.server.domain.credential.PasswordResetToken
import com.taspa.server.domain.credential.PasswordResetTokenRepository
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.verification.EmailVerificationCode
import com.taspa.server.domain.verification.EmailVerificationCodeRepository
import com.taspa.server.support.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 보존 정리 잡 확장 검증:
 *  - email_verification_codes / password_reset_tokens: 소진·만료분 삭제, 유효분 보존
 *  - oauth2_authorization: 만료 유예 초과 행 삭제, 유효/진행중(만료시각 없음) 행 보존
 */
class RetentionCleanupIntegrationTest : IntegrationTestBase() {
    @Autowired lateinit var retentionCleanupJob: RetentionCleanupJob

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var emailVerificationCodeRepository: EmailVerificationCodeRepository

    @Autowired lateinit var passwordResetTokenRepository: PasswordResetTokenRepository

    private val idExpired = "cleanup-test-expired"
    private val idFresh = "cleanup-test-fresh"
    private val idInflight = "cleanup-test-inflight"

    private lateinit var userId: UUID

    @BeforeEach
    fun setUp() {
        emailVerificationCodeRepository.deleteAll()
        passwordResetTokenRepository.deleteAll()
        userRepository.deleteAll()
        listOf(idExpired, idFresh, idInflight).forEach {
            jdbcTemplate.update("DELETE FROM oauth2_authorization WHERE id = ?", it)
        }
        userId = userRepository.save(User(email = "cleanup@example.com", emailVerified = true)).id!!
    }

    @Test
    fun `cleanup deletes consumed or expired verification codes and reset tokens but keeps valid ones`() {
        val now = Instant.now()
        // email verification codes
        emailVerificationCodeRepository.save(
            EmailVerificationCode(userId = userId, codeHash = "h1", expiresAt = now.plusSeconds(600)),
        ) // valid
        emailVerificationCodeRepository.save(
            EmailVerificationCode(userId = userId, codeHash = "h2", expiresAt = now.minusSeconds(600)),
        ) // expired
        emailVerificationCodeRepository.save(
            EmailVerificationCode(
                userId = userId,
                codeHash = "h3",
                expiresAt = now.plusSeconds(600),
                consumedAt = now,
            ),
        ) // consumed
        // password reset tokens
        passwordResetTokenRepository.save(
            PasswordResetToken(userId = userId, tokenHash = "t1", expiresAt = now.plusSeconds(600)),
        ) // valid
        passwordResetTokenRepository.save(
            PasswordResetToken(userId = userId, tokenHash = "t2", expiresAt = now.minusSeconds(600)),
        ) // expired
        passwordResetTokenRepository.save(
            PasswordResetToken(userId = userId, tokenHash = "t3", used = true, expiresAt = now.plusSeconds(600)),
        ) // used

        retentionCleanupJob.cleanUp()

        val codes = emailVerificationCodeRepository.findAll().filter { it.userId == userId }
        assertThat(codes.map { it.codeHash }).containsExactly("h1")
        val tokens = passwordResetTokenRepository.findAll().filter { it.userId == userId }
        assertThat(tokens.map { it.tokenHash }).containsExactly("t1")
    }

    @Test
    fun `cleanup deletes expired oauth2 authorizations but keeps valid and in-flight rows`() {
        val insertWithRefreshExpiry =
            """
            INSERT INTO oauth2_authorization
                (id, registered_client_id, principal_name, authorization_grant_type, refresh_token_expires_at)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
        // 만료(10일 전) — grace(1일) 초과 → 삭제 대상.
        jdbcTemplate.update(
            insertWithRefreshExpiry,
            idExpired,
            "client-x",
            "cleanup@example.com",
            "authorization_code",
            Timestamp.from(Instant.now().minus(Duration.ofDays(10))),
        )
        // 미래 만료 → 보존.
        jdbcTemplate.update(
            insertWithRefreshExpiry,
            idFresh,
            "client-x",
            "cleanup@example.com",
            "authorization_code",
            Timestamp.from(Instant.now().plus(Duration.ofDays(10))),
        )
        // 진행 중(만료 시각 전무) → 보존(GREATEST > epoch 가드).
        jdbcTemplate.update(
            """
            INSERT INTO oauth2_authorization
                (id, registered_client_id, principal_name, authorization_grant_type)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            idInflight,
            "client-x",
            "cleanup@example.com",
            "authorization_code",
        )

        retentionCleanupJob.cleanUp()

        assertThat(rowExists(idExpired)).isFalse()
        assertThat(rowExists(idFresh)).isTrue()
        assertThat(rowExists(idInflight)).isTrue()
    }

    private fun rowExists(id: String): Boolean =
        (jdbcTemplate.queryForObject("SELECT count(*) FROM oauth2_authorization WHERE id = ?", Int::class.java, id) ?: 0) > 0
}
