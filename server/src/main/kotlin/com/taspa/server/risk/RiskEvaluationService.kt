package com.taspa.server.risk

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.http.RequestClientInfo
import com.taspa.server.device.TrustedDeviceService
import com.taspa.server.domain.login.LoginEventRepository
import com.taspa.server.domain.user.User
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * 리스크 기반 인증(Adaptive Auth) — **비밀번호 로그인 경로에만** 적용한다.
 *
 * 적용 범위 근거(다른 수단이 면제되는 이유):
 *  - 패스키: 피싱 내성(origin 바인딩 공개키 서명) — 원격 공격자가 훔친 자격 증명으로 재현할 수 없다.
 *  - 소셜: 공급자가 자체 리스크 엔진으로 1차 인증을 보증한다(이중 챌린지는 UX 손해만).
 *  - 매직 링크: 이메일 소유 증명 자체가 이 챌린지(이메일 코드)와 동일한 요소다.
 *
 * 신호(login_events + users 필드 재사용, 별도 테이블 없음):
 *  - unseenDevice: 최근 unseen-window-days(기본 90일) 내 같은 (ip, ua 라벨) 로그인 이력이 없고,
 *    유효한 신뢰 기기 쿠키도 없다.
 *  - recentFailures: 이 로그인 직전 user.failedLoginAttempts ≥ 3 (잠금 상한 5 전에 발동).
 *    잠금 만료 자동 해제는 카운터를 보존하므로(AccountLockoutService.isAccountLocked KDoc)
 *    잠금까지 갔던 실패 이력도 이 신호에 그대로 잡힌다.
 *  - rapidIpChange: 직전 성공 로그인이 30분 이내인데 IP 가 다르다.
 *
 * IP 는 RequestClientInfo.ip = remoteAddr 만 쓴다 — X-Forwarded-For 는 클라이언트가 조작
 * 가능해 리스크 신호 스푸핑 우회로가 된다(프록시 뒤 배포 전제는 RequestClientInfo KDoc 참고).
 *
 * 판정: HIGH = unseenDevice && (recentFailures || rapidIpChange),
 *       MEDIUM = HIGH 미해당인 unseenDevice 또는 recentFailures (rapidIpChange 동반 여부 무관),
 *       LOW = 그 외. rapidIpChange 는 단독으로 게이트를 열지 않는 보조 신호다
 *       (같은 NAT 안 이동·모바일 망 전환 오탐이 잦아 HIGH 판정의 가중으로만 쓴다).
 */
@Service
class RiskEvaluationService(
    private val properties: RiskProperties,
    private val loginEventRepository: LoginEventRepository,
    private val trustedDeviceService: TrustedDeviceService,
    private val auditEventService: AuditEventService,
) {
    companion object {
        /** recentFailures 임계값 — 계정 잠금(기본 5회) 전에 신호가 먼저 발동하도록 3. */
        private const val FAILURE_THRESHOLD = 3
        private val RAPID_IP_WINDOW: Duration = Duration.ofMinutes(30)
    }

    /**
     * 비밀번호 인증 성공 직후 호출한다. **호출 순서 불변식**: recentFailures 는
     * user.failedLoginAttempts 의 "성공 처리로 리셋되기 전" 값을 읽어야 하므로, 반드시
     * AccountLockoutService.recordSuccessfulLogin 보다 먼저 호출해야 한다
     * (MfaAwareAuthenticationSuccessHandler 참고).
     *
     * response 는 신뢰 기기 쿠키 검증 성공 시의 토큰 회전(Set-Cookie) 때문에 필요하다 —
     * 회전은 요청 속성으로 캐시되어 이후 requiredGate 의 재검증과 중복되지 않는다.
     */
    fun evaluate(
        request: HttpServletRequest,
        response: HttpServletResponse,
        user: User,
    ): RiskLevel {
        if (!properties.enabled) {
            return RiskLevel.LOW
        }
        val userId = user.id!!
        val ip = RequestClientInfo.ip(request)
        val uaLabel = RequestClientInfo.uaLabel(request)
        val now = Instant.now()

        val knownDevice =
            loginEventRepository.existsByUserIdAndIpAndUaLabelAndCreatedAtAfter(
                userId,
                ip,
                uaLabel,
                now.minus(Duration.ofDays(properties.unseenWindowDays)),
            )
        val unseenDevice = !knownDevice && !trustedDeviceService.validateAndRotate(request, response, user)

        val recentFailures = user.failedLoginAttempts >= FAILURE_THRESHOLD

        // login_events 는 성공한 완전 인증만 기록하므로 최신 행이 곧 "직전 성공 로그인"이다.
        // 이 시점에는 이번 로그인이 아직 기록되지 않았다(기록은 게이트 통과 후).
        val lastLogin = loginEventRepository.findFirstByUserIdOrderByCreatedAtDesc(userId)
        val rapidIpChange =
            lastLogin != null &&
                lastLogin.createdAt.isAfter(now.minus(RAPID_IP_WINDOW)) &&
                lastLogin.ip != null &&
                lastLogin.ip != ip

        val level =
            when {
                unseenDevice && (recentFailures || rapidIpChange) -> RiskLevel.HIGH
                unseenDevice || recentFailures -> RiskLevel.MEDIUM
                else -> RiskLevel.LOW
            }
        if (level != RiskLevel.LOW) {
            auditEventService.record(
                "RISK_DETECTED",
                userId,
                mapOf(
                    "level" to level.name,
                    "unseenDevice" to unseenDevice,
                    "recentFailures" to recentFailures,
                    "rapidIpChange" to rapidIpChange,
                    "ip" to ip,
                    "uaLabel" to uaLabel,
                ),
            )
        }
        return level
    }
}
