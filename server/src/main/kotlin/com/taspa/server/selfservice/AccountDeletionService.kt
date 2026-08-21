package com.taspa.server.selfservice

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.security.SecureTokenGenerator
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.session.SessionManagementService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * 계정 탈퇴(하드 삭제 — step-up + 확인은 컨트롤러에서 강제).
 *
 * **하드삭제 채택 근거**: 소프트삭제(status 플래그)는 PII(이메일 등)를 계속 보존해 최소 수집·삭제권
 * 요구와 어긋난다. 탈퇴 즉시 users 행을 물리 삭제해 PII 를 제거하고, 재식별 불가능한 감사만 남긴다.
 *
 * 삭제 순서:
 *  1) oauth2_authorization / oauth2_authorization_consent — principal_name(=이메일) 키. users 로의 FK 가
 *     없어(SAS 표준 스키마) CASCADE 되지 않으므로 명시 삭제한다(활성 토큰·동의 무효화).
 *  2) users 행 삭제 → FK ON DELETE CASCADE 로 backup_codes / email_verification_codes /
 *     password_reset_tokens / webauthn_user_entities(→ webauthn_credentials) / federated_identities /
 *     trusted_devices / login_events / magic_link_tokens 가 함께 제거된다.
 *  3) 세션 저장소(spring-session)는 users 로의 FK 가 없어 CASCADE 안 됨 → 커밋 후 명시 폐기.
 *  4) 감사 익명화: audit_events.user_id 는 FK 가 없어 삭제 후에도 남고, 선행 이벤트(EMAIL_CHANGED /
 *     PASSWORD_CHANGED / EMAIL_VERIFICATION_SENT 등)의 detail 에는 평문 이메일이 담긴다. 하드삭제의
 *     "재식별 불가능한 감사만 남긴다" 불변식을 지키려면 이 detail 을 함께 제거해야 하므로, 해당 user_id 의
 *     기존 audit_events detail 을 익명화 마커로 덮어써 PII 를 지운다(type·user_id·created_at 는 감사
 *     추적을 위해 남긴다 — user_id UUID 는 users 행 삭제 후 역추적 불가라 재식별되지 않는다).
 *  5) 감사 ACCOUNT_DELETED: 익명화 이후(커밋 후) 기록한다. detail 은 이메일 SHA-256 해시만 담아
 *     PII 를 보존하지 않는다.
 */
@Service
class AccountDeletionService(
    private val userRepository: UserRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val sessionManagementService: SessionManagementService,
    private val auditEventService: AuditEventService,
    private val transactionTemplate: TransactionTemplate,
) {
    companion object {
        /** 탈퇴 사용자의 선행 감사 detail 을 대체하는 익명화 마커(PII 제거, 유효 JSON). */
        private const val ANONYMIZED_DETAIL = "{\"redacted\":\"account_deleted\"}"
    }

    fun deleteAccount(
        userId: UUID,
        email: String,
    ) {
        transactionTemplate.execute {
            jdbcTemplate.update("DELETE FROM oauth2_authorization WHERE principal_name = ?", email)
            jdbcTemplate.update("DELETE FROM oauth2_authorization_consent WHERE principal_name = ?", email)
            // 선행 감사 이벤트의 detail 에 남은 평문 이메일(PII)을 익명화 마커로 덮어쓴다 — 하드삭제의
            // "재식별 불가능한 감사만 남긴다" 불변식을 실제로 관철한다. type·user_id·created_at 는 남긴다.
            jdbcTemplate.update(
                "UPDATE audit_events SET detail = ? WHERE user_id = ?",
                ANONYMIZED_DETAIL,
                userId,
            )
            // FK ON DELETE CASCADE 가 나머지 사용자 소유 행을 정리한다(User 엔티티엔 매핑 연관이 없어
            // Hibernate 는 users 한 행만 DELETE — 실제 파급은 DB 제약이 수행).
            userRepository.deleteById(userId)
            userRepository.flush()
        }

        // 세션은 별도 저장소(principal_name 인덱스=이메일) — 커밋 후 폐기(현재 세션 포함).
        sessionManagementService.revokeAll(userId, email)
        auditEventService.record(
            "ACCOUNT_DELETED",
            userId,
            mapOf("emailHash" to SecureTokenGenerator.hashToken(email)),
        )
    }
}
