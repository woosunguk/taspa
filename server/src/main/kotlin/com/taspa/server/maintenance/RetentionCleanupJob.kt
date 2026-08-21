package com.taspa.server.maintenance

import com.taspa.server.domain.audit.AuditEventRepository
import com.taspa.server.domain.credential.MagicLinkTokenRepository
import com.taspa.server.domain.credential.PasswordResetTokenRepository
import com.taspa.server.domain.device.TrustedDeviceRepository
import com.taspa.server.domain.login.LoginEventRepository
import com.taspa.server.domain.verification.EmailVerificationCodeRepository
import com.taspa.server.org.OrgInvitationService
import com.taspa.server.passkey.JdbcCreationOptionsRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

/**
 * 보존 기간 정리 잡 — 무한 누적을 막는다.
 *
 * - login_events: 알림 판정은 최근 30일만 사용하므로 보존 기간(기본 90일) 초과분 삭제
 * - trusted_devices: 만료된 행 삭제(만료 후에는 MFA 스킵에 쓰이지 않고 목록에도 노출하지 않는다)
 * - magic_link_tokens: 소진(used_at 마킹)됐거나 만료된 행 삭제
 * - email_verification_codes: 소진(consumed)됐거나 만료된 코드 삭제
 * - password_reset_tokens: 사용(used)됐거나 만료된 토큰 삭제
 * - oauth2_authorization: 모든 토큰 만료가 유예 시각 이전인 인가 행 삭제(IdP 최고 핫패스 테이블 — 무한 성장 방지)
 * - audit_events: 보존 기간(기본 365일) 초과분 삭제
 * - 패스키 등록 옵션 인메모리 캐시: 만료 항목 정리 훅(기본은 접근 시 lazy 정리 — B-2)
 */
@Component
class RetentionCleanupJob(
    private val loginEventRepository: LoginEventRepository,
    private val trustedDeviceRepository: TrustedDeviceRepository,
    private val magicLinkTokenRepository: MagicLinkTokenRepository,
    private val emailVerificationCodeRepository: EmailVerificationCodeRepository,
    private val passwordResetTokenRepository: PasswordResetTokenRepository,
    private val auditEventRepository: AuditEventRepository,
    private val jdbcCreationOptionsRepository: JdbcCreationOptionsRepository,
    private val orgInvitationService: OrgInvitationService,
    private val jdbcTemplate: JdbcTemplate,
    @Value("\${taspa.retention.login-events-days:90}")
    private val loginEventRetentionDays: Long,
    @Value("\${taspa.retention.audit-days:365}")
    private val auditRetentionDays: Long,
    @Value("\${taspa.retention.oauth2-authorization-grace-days:1}")
    private val oauth2AuthorizationGraceDays: Long,
) {
    private val log = LoggerFactory.getLogger(RetentionCleanupJob::class.java)

    @Scheduled(cron = "\${taspa.retention.cleanup-cron:0 0 4 * * *}")
    @Transactional
    fun cleanUp() {
        // ★이 잡만 잠금 대기 상한을 완화한다(전역 기본은 application.yml 의 lock_timeout=3s).
        // 3s 는 "단일 행·밀리초 보유" 인 요청 경로 기준으로 정한 값이다. 여기 대량 DELETE 는 핫패스
        // 테이블(oauth2_authorization 등)을 훑으므로 3s 안에 잠긴 행 하나만 만나도 중단되는데,
        // 이 메서드는 **단일 트랜잭션**이라 한 문장이 죽으면 8개 DELETE 가 통째로 롤백된다 —
        // 매일 새벽 조용히 실패하고 테이블이 무한 증가한다(@Scheduled 예외는 ERROR 로그 한 줄뿐).
        // SET LOCAL 은 트랜잭션 종료 시 자동 복원되므로 커넥션 풀에 오염을 남기지 않는다.
        jdbcTemplate.execute("SET LOCAL lock_timeout = '30s'")
        val now = Instant.now()
        val events = loginEventRepository.deleteByCreatedAtBefore(now.minus(Duration.ofDays(loginEventRetentionDays)))
        val devices = trustedDeviceRepository.deleteByExpiresAtBefore(now)
        val tokens = magicLinkTokenRepository.deleteConsumedOrExpired(now)
        val emailCodes = emailVerificationCodeRepository.deleteConsumedOrExpired(now)
        val resetTokens = passwordResetTokenRepository.deleteUsedOrExpired(now)
        val authorizations = purgeExpiredOAuth2Authorizations(now.minus(Duration.ofDays(oauth2AuthorizationGraceDays)))
        val audits = auditEventRepository.deleteByCreatedAtBefore(now.minus(Duration.ofDays(auditRetentionDays)))
        // 조직 초대: 만료 시각이 지난 PENDING 을 EXPIRED 로 전이(만료 초대가 목록/수락에 남지 않게 한다).
        val expiredInvitations = orgInvitationService.expireOverdue()
        jdbcCreationOptionsRepository.purgeExpired()
        log.info(
            "retention cleanup: loginEvents={}, trustedDevices={}, magicLinkTokens={}, " +
                "emailCodes={}, resetTokens={}, oauth2Authorizations={}, auditEvents={}, expiredInvitations={}",
            events,
            devices,
            tokens,
            emailCodes,
            resetTokens,
            authorizations,
            audits,
            expiredInvitations,
        )
    }

    /**
     * oauth2_authorization 정리 — 이 테이블은 인가코드 교환·토큰 발급·리프레시마다 행이 쌓이지만
     * 삭제 경로가 없어 단조 증가한다. 모든 토큰 만료 시각 중 최댓값(= 가장 오래 사는 refresh 토큰
     * 기준)이 유예 시각(cutoff) 이전인 행만 지운다. 만료 시각이 하나도 없는 진행 중 인가 행은
     * (GREATEST > epoch 가드로) 절대 지우지 않는다.
     */
    private fun purgeExpiredOAuth2Authorizations(cutoff: Instant): Int {
        val greatestExpiry =
            """
            GREATEST(
                COALESCE(access_token_expires_at, TIMESTAMP 'epoch'),
                COALESCE(refresh_token_expires_at, TIMESTAMP 'epoch'),
                COALESCE(oidc_id_token_expires_at, TIMESTAMP 'epoch'),
                COALESCE(authorization_code_expires_at, TIMESTAMP 'epoch'),
                COALESCE(user_code_expires_at, TIMESTAMP 'epoch'),
                COALESCE(device_code_expires_at, TIMESTAMP 'epoch')
            )
            """.trimIndent()
        val sql =
            "DELETE FROM oauth2_authorization " +
                "WHERE $greatestExpiry > TIMESTAMP 'epoch' AND $greatestExpiry < ?"
        return jdbcTemplate.update(sql, Timestamp.from(cutoff))
    }
}
