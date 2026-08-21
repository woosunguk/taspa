package com.taspa.server.selfservice

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.credential.PasswordPolicyService
import com.taspa.server.device.TrustedDeviceService
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.session.SessionManagementService
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * 인세션 비밀번호 변경/설정(step-up 은 컨트롤러의 @RequireRecentAuth 로 강제).
 *
 * - 비밀번호 보유 계정: 현재 비밀번호 확인 + 새 비밀번호(정책 검증) → 변경.
 * - 소셜 전용 계정(password_hash NULL): 현재 비밀번호가 없으므로 step-up 만으로 최초 설정 허용.
 * - 어느 경우든 성공 시 **모든 세션 + 모든 신뢰 기기 폐기**(자격 증명 변경 = 탈취 대응, PasswordResetService
 *   와 동일 훅 재사용). 현재 세션도 폐기되므로 클라이언트는 /login 으로 재로그인해야 한다.
 *
 * 트랜잭션/커넥션 규율은 PasswordResetService 와 동일: 비밀번호·신뢰 기기 폐기는 한 트랜잭션에서 커밋하고,
 * 세션 저장소 폐기(REQUIRES_NEW)는 커밋 이후 실행해 요청당 커넥션 2개 점유를 피한다.
 */
@Service
class PasswordChangeService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val passwordPolicyService: PasswordPolicyService,
    private val trustedDeviceService: TrustedDeviceService,
    private val sessionManagementService: SessionManagementService,
    private val auditEventService: AuditEventService,
    private val transactionTemplate: TransactionTemplate,
) {
    fun changePassword(
        userId: UUID,
        currentPassword: String?,
        newPassword: String,
    ) {
        val email =
            requireNotNull(
                transactionTemplate.execute {
                    val user =
                        userRepository
                            .findById(userId)
                            .orElseThrow { AuthException(ErrorCode.USER_NOT_FOUND) }

                    val existingHash = user.passwordHash
                    if (existingHash != null) {
                        // 비밀번호 보유 계정: 현재 비밀번호 확인(step-up 과 별개의 소유 증명).
                        if (currentPassword.isNullOrEmpty() ||
                            !passwordEncoder.matches(currentPassword, existingHash)
                        ) {
                            throw AuthException(ErrorCode.CURRENT_PASSWORD_INCORRECT)
                        }
                    }

                    val violations = passwordPolicyService.validate(newPassword)
                    if (violations.isNotEmpty()) {
                        throw AuthException(ErrorCode.PASSWORD_POLICY_VIOLATION, violations.joinToString("; "))
                    }

                    user.passwordHash = passwordEncoder.encode(newPassword)
                    user.failedLoginAttempts = 0
                    user.lockedUntil = null
                    userRepository.save(user)

                    // 신뢰 기기 폐기는 같은 JPA 트랜잭션에 참여한다(추가 커넥션 없음).
                    trustedDeviceService.revokeAll(userId)
                    user.email
                },
            )

        // 자격 증명 변경 → 모든 활성 세션 폐기(현재 세션 포함) — 커밋 확정 후.
        sessionManagementService.revokeAll(userId, email)
        auditEventService.record("PASSWORD_CHANGED", userId, mapOf("email" to email))
    }
}
