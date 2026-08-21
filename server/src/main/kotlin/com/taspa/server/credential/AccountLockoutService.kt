package com.taspa.server.credential

import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class AccountLockoutService(
    private val properties: AccountLockoutProperties,
    private val userRepository: UserRepository,
) {
    /**
     * 만료된 잠금은 여기서 자동 해제한다. 이때 failedLoginAttempts 는 **리셋하지 않는다** —
     * 잠금까지 갔던 실패 이력은 리스크 신호(RiskEvaluationService.recentFailures)의 입력이라,
     * 인증 전에 0 으로 되돌리면 정확히 최악의 브루트포스(잠금 유발 후 성공)에서 신호가 소거된다.
     * 카운터는 성공 시 recordSuccessfulLogin 이, 만료 후 첫 실패 시 recordFailedAttempt 가
     * 새 사이클로 재시작하며 정리한다(1회 실패 즉시 재잠금 방지도 그쪽에서 담당).
     */
    fun isAccountLocked(user: User): Boolean {
        val lockedUntil = user.lockedUntil ?: return false
        if (Instant.now().isAfter(lockedUntil)) {
            user.lockedUntil = null
            userRepository.save(user)
            return false
        }
        return true
    }

    @Transactional
    fun recordFailedAttempt(user: User) {
        if (user.lockedUntil == null && user.failedLoginAttempts >= properties.maxAttempts) {
            // 만료 잠금이 남긴 잔여 카운터 — 새 잠금 사이클로 재시작한다.
            // (만료 직후 첫 실패 1번으로 즉시 재잠금되는 문제 방지 — 기존 '해제 시 리셋'의 대체.)
            user.failedLoginAttempts = 1
        } else {
            user.failedLoginAttempts += 1
        }
        if (user.failedLoginAttempts >= properties.maxAttempts) {
            user.lockedUntil = Instant.now().plusSeconds(properties.lockoutDurationMinutes * 60)
        }
        userRepository.save(user)
    }

    @Transactional
    fun recordSuccessfulLogin(user: User) {
        if (user.failedLoginAttempts > 0 || user.lockedUntil != null) {
            user.failedLoginAttempts = 0
            user.lockedUntil = null
            userRepository.save(user)
        }
    }
}
