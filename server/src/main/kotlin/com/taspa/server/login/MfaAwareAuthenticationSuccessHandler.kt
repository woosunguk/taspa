package com.taspa.server.login

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.http.RequestClientInfo
import com.taspa.server.credential.AccountLockoutService
import com.taspa.server.domain.audit.AuditEventRepository
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.mail.MailService
import com.taspa.server.risk.RiskEvaluationService
import com.taspa.server.risk.RiskLevel
import com.taspa.server.session.SessionMetadata
import com.taspa.server.stepup.StepUp
import com.taspa.server.verification.EmailVerificationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * 비밀번호 인증 성공 직후 실행된다. 이 시점에는 필터가 이미 완전 인증을 세션에 저장한 상태다.
 * 게이트(미인증 이메일 / MFA / 리스크 챌린지)가 필요하면 그 완전 인증을 걷어내고 pending 으로
 * 전환한다. 게이트가 없으면 완전 인증 확정 — auth_time·login_events(새 로그인 알림)를 기록하고
 * 원래 요청으로 위임하여 OIDC authorize 요청을 계속 진행시킨다.
 * 게이트 판정 자체는 소셜 성공 핸들러와 공유하는 LoginFlowSupport.requiredGate 를 쓴다.
 * 리스크 평가(RiskEvaluationService)는 비밀번호 경로인 여기서만 수행한다.
 */
@Component
class MfaAwareAuthenticationSuccessHandler(
    private val userRepository: UserRepository,
    private val accountLockoutService: AccountLockoutService,
    private val auditEventService: AuditEventService,
    private val loginFlowSupport: LoginFlowSupport,
    private val loginEventService: LoginEventService,
    private val riskEvaluationService: RiskEvaluationService,
    private val emailVerificationService: EmailVerificationService,
    private val mailService: MailService,
    private val auditEventRepository: AuditEventRepository,
) : AuthenticationSuccessHandler {
    companion object {
        private const val METHOD_PASSWORD = "password"

        /** HIGH 경고 메일 발송 이력 감사 타입 — 쿨다운 판정의 저장소를 겸한다. */
        private const val RISK_ALERT_AUDIT_TYPE = "RISK_ALERT_MAILED"

        /** 같은 사용자에 대한 HIGH 경고 메일 최소 간격. */
        private val HIGH_RISK_ALERT_COOLDOWN: Duration = Duration.ofMinutes(15)
    }

    private val log = LoggerFactory.getLogger(MfaAwareAuthenticationSuccessHandler::class.java)

    private val delegate =
        SavedRequestAwareAuthenticationSuccessHandler().apply {
            setDefaultTargetUrl("/account")
        }

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        val user = userRepository.findByEmail(authentication.name)
        if (user == null) {
            delegate.onAuthenticationSuccess(request, response, authentication)
            return
        }

        // 순서 불변식: 리스크 평가가 recordSuccessfulLogin 보다 먼저다 — recentFailures 신호가
        // user.failedLoginAttempts 의 "리셋 전" 값을 읽어야 하기 때문(성공 처리가 0 으로 되돌린다).
        val risk = riskEvaluationService.evaluate(request, response, user)
        accountLockoutService.recordSuccessfulLogin(user)

        if (risk == RiskLevel.HIGH) {
            // 새 로그인 알림과 별개의 보안 경고(차단 안내). 발송 실패는 로그인 흐름에 비전파.
            // 사용자별 쿨다운: 게이트 통과 전에는 login_events 기반 자연 억제가 없어, 올바른
            // 비밀번호를 가진 공격자가 로그인 폼 반복 제출로 경고 메일을 폭주시킬 수 있다 —
            // 최근 발송 이력(RISK_ALERT_MAILED 감사)이 있으면 생략한다.
            val alreadyAlerted =
                auditEventRepository.existsByTypeAndUserIdAndCreatedAtAfter(
                    RISK_ALERT_AUDIT_TYPE,
                    user.id!!,
                    Instant.now().minus(HIGH_RISK_ALERT_COOLDOWN),
                )
            if (!alreadyAlerted) {
                try {
                    mailService.sendHighRiskLoginAlert(
                        user.email,
                        RequestClientInfo.uaLabel(request),
                        RequestClientInfo.ip(request),
                        Instant.now(),
                    )
                    auditEventService.record(RISK_ALERT_AUDIT_TYPE, user.id, mapOf("email" to user.email))
                } catch (ex: Exception) {
                    log.warn("high-risk login alert mail failed for userId={}", user.id, ex)
                }
            }
        }

        val gate = loginFlowSupport.requiredGate(request, response, user, risk)
        if (gate == null) {
            auditEventService.record("LOGIN_SUCCESS", user.id, mapOf("email" to user.email))
            StepUp.markAuthTime(request, user.id!!)
            // 무게이트 경로는 establishSecurityContext 를 거치지 않으므로 세션 메타를 여기서 기록한다.
            SessionMetadata.record(request)
            loginEventService.recordLogin(request, user, METHOD_PASSWORD)
            delegate.onAuthenticationSuccess(request, response, authentication)
            return
        }

        loginFlowSupport.startPending(request, response, user.id!!, gate, METHOD_PASSWORD)
        auditEventService.record("LOGIN_GATE", user.id, mapOf("stage" to gate.name))
        if (gate == PendingAuthStage.RISK_CHALLENGE) {
            // 챌린지 코드는 게이트 진입 시점에 발급한다(EMAIL_VERIFICATION 은 가입 시 이미 발급됨).
            // issue() 직접 호출이 아니라 resend() 를 태운다 — 60초 최소 간격이 없으면 비밀번호를
            // 아는 공격자가 로그인 반복만으로 코드 메일을 폭주시키고, 매번 새 코드가 '최신 미소진'
            // 코드가 되어 병행 세션(다른 기기)의 유효 코드를 즉시 무효화한다. 간격 내면 발송을
            // 생략하고 직전 코드를 그대로 쓴다. 발송 실패/스로틀 시에도 챌린지 페이지의
            // "다시 보내기"로 복구할 수 있으므로 흐름은 계속한다.
            try {
                emailVerificationService.resend(user.id)
            } catch (ex: Exception) {
                log.warn("risk challenge code issue failed for userId={}", user.id, ex)
            }
        }
        val target =
            when (gate) {
                PendingAuthStage.EMAIL_VERIFICATION -> "/login/verify-email"
                PendingAuthStage.RISK_CHALLENGE -> "/login/risk-challenge"
                else -> "/login/mfa"
            }
        response.sendRedirect(request.contextPath + target)
    }
}
