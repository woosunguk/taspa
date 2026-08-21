package com.taspa.server.login

import com.taspa.server.audit.AuditEventService
import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.device.TrustedDeviceService
import com.taspa.server.domain.user.User
import com.taspa.server.domain.user.UserRepository
import com.taspa.server.domain.user.UserStatus
import com.taspa.server.federation.PendingSocialLink
import com.taspa.server.risk.RiskLevel
import com.taspa.server.session.SessionMetadata
import com.taspa.server.stepup.StepUp
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.savedrequest.RequestCache
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * 부분 인증(pending) 상태와 완전 인증 상태를 세션 기준으로 전환하는 공용 로직.
 *
 * 핵심 불변식: pending 상태에서는 SPRING_SECURITY_CONTEXT 가 세션에 존재하지 않는다.
 * 따라서 게이트를 통과하기 전에는 어떤 보호 자원(/oauth2/authorize 포함)에도 접근할 수 없다.
 */
@Component
class LoginFlowSupport(
    private val userRepository: UserRepository,
    private val loginUserDetailsService: LoginUserDetailsService,
    private val securityContextRepository: HttpSessionSecurityContextRepository,
    private val requestCache: RequestCache,
    private val trustedDeviceService: TrustedDeviceService,
    private val loginEventService: LoginEventService,
    private val auditEventService: AuditEventService,
) {
    /**
     * 게이트 진입: 이미 필터가 세션에 저장했을 수 있는 완전 인증을 제거하고 pending 을 심는다.
     * userId 는 SOCIAL_EMAIL(계정 미생성) 단계에서만 null 을 허용한다.
     * method 는 1차 인증 수단 라벨(password / social:{provider} / magic) — 게이트 통과 후 기록에 쓰인다.
     */
    fun startPending(
        request: HttpServletRequest,
        response: HttpServletResponse,
        userId: UUID?,
        stage: PendingAuthStage,
        method: String? = null,
    ) {
        clearSecurityContext(request, response)
        val session = request.getSession(true)
        session.setAttribute(
            PendingAuth.SESSION_KEY,
            PendingAuth(userId, stage, Instant.now().plus(PendingAuth.TTL), method),
        )
    }

    /**
     * 위생 규칙용: 이미 완전 인증된 세션인지. 로그인 단계 페이지(GET /login/passkey·password·mfa·verify-email)는
     * 완전 인증 상태에서 /account 로 되돌린다.
     */
    fun isFullyAuthenticated(): Boolean {
        val authentication = SecurityContextHolder.getContext().authentication ?: return false
        return authentication.isAuthenticated && authentication !is AnonymousAuthenticationToken
    }

    fun currentPending(request: HttpServletRequest): PendingAuth? {
        val session = request.getSession(false) ?: return null
        val pending = session.getAttribute(PendingAuth.SESSION_KEY) as? PendingAuth ?: return null
        if (pending.isExpired()) {
            session.removeAttribute(PendingAuth.SESSION_KEY)
            // ★만료 **사실**을 남긴다 — 아래 [gateLostRedirect] 가 이걸 읽어 사용자에게 이유를 말한다.
            session.setAttribute(PendingAuth.EXPIRED_KEY, true)
            return null
        }
        return pending
    }

    /**
     * 게이트(MFA·이메일 인증·리스크 챌린지·소셜 연결 확인)를 잃었을 때 갈 곳.
     *
     * ★그전에는 이 자리마다 `"redirect:/login"` 한 줄이었다(같은 컨트롤러에만 21곳). 그래서 **정답 코드를
     * 넣은 순간에도** 5분 TTL 이 지났으면 아무 설명 없이 빈 로그인 화면으로 튕겼다 — 사용자가 보는 것은
     * "코드를 넣었는데 처음으로 돌아왔다"이고, 화면 어디에도 만료됐다는 말도 다시 하라는 안내도 없다.
     * 코드가 틀렸다고 오해해 메일을 다시 확인하고, 같은 코드를 다시 넣고, 또 튕긴다.
     *
     * 만료로 잃은 경우에만 표식을 싣는다. 게이트를 **애초에 시작하지 않은** 직접 URL 접근까지 "시간이
     * 지났습니다"라고 말하면 그건 다른 종류의 거짓말이라, 둘을 구분한다.
     *
     * 이메일 힌트(`LOGIN_HINT_KEY`)는 지우지 않는다 — 식별자 화면이 그 값으로 입력란을 채워
     * 재입력 부담을 없앤다(다시 시작하는 것 자체는 피할 수 없으므로 비용이라도 줄인다).
     */
    fun gateLostRedirect(request: HttpServletRequest): String {
        val session = request.getSession(false) ?: return "redirect:/login"
        val expired = session.getAttribute(PendingAuth.EXPIRED_KEY) == true
        session.removeAttribute(PendingAuth.EXPIRED_KEY)
        return if (expired) "redirect:/login?expired=1" else "redirect:/login"
    }

    fun transitionPending(
        request: HttpServletRequest,
        userId: UUID,
        stage: PendingAuthStage,
        method: String? = null,
    ) {
        val session = request.getSession(true)
        session.setAttribute(
            PendingAuth.SESSION_KEY,
            PendingAuth(userId, stage, Instant.now().plus(PendingAuth.TTL), method),
        )
    }

    /**
     * 완전 인증 진입 전 게이트 판정 — 비밀번호 성공 핸들러·소셜 성공 핸들러·이메일 인증 통과 후·매직 링크가 공유한다.
     * 순서: 이메일 인증이 먼저, 통과 후 MFA. MFA 는 유효한 신뢰 기기 쿠키(taspa_td)가 있으면 건너뛴다 —
     * 검증 성공 시 토큰이 회전되므로 response 가 필요하다.
     *
     * risk 는 **비밀번호 경로만** 평가값을 전달한다(RiskEvaluationService) — 패스키(피싱 내성)·
     * 소셜(공급자 보증)·매직 링크(이메일 소유 증명)는 면제라 기본값 LOW 를 쓴다. MEDIUM 이상이면
     * MFA 사용자는 신뢰 기기 스킵을 무시하고 MFA 강제, MFA 미등록 사용자는 이메일 코드 챌린지
     * (RISK_CHALLENGE). 이메일 미인증 게이트가 최우선이므로 그 자체가 이메일 소유를 증명하는
     * EMAIL_VERIFICATION 과 RISK_CHALLENGE 는 중복 발동하지 않는다.
     */
    fun requiredGate(
        request: HttpServletRequest,
        response: HttpServletResponse,
        user: User,
        risk: RiskLevel = RiskLevel.LOW,
    ): PendingAuthStage? =
        when {
            !user.emailVerified -> PendingAuthStage.EMAIL_VERIFICATION
            user.mfaEnabled && risk >= RiskLevel.MEDIUM -> PendingAuthStage.MFA
            user.mfaEnabled && !trustedDeviceService.validateAndRotate(request, response, user) -> PendingAuthStage.MFA
            risk >= RiskLevel.MEDIUM -> PendingAuthStage.RISK_CHALLENGE
            else -> null
        }

    /**
     * 게이트 통과: 세션 고정 공격 방지를 위해 세션 ID 를 교체하고, 완전 인증을 SecurityContext 에 세워
     * 세션에 저장한 뒤 pending 을 제거한다. auth_time(step-up 기준)과 login_events(새 로그인 알림 판정)도
     * 여기서 기록한다. 원래 요청(saved request)이 있으면 그곳으로, 없으면 /account 로.
     *
     * method: login_events 기록용 수단 라벨(password / mfa / passkey / social:{provider} / magic).
     */
    fun completeAuthentication(
        request: HttpServletRequest,
        response: HttpServletResponse,
        userId: UUID,
        method: String,
    ): String {
        // 모든 완전 인증 수렴점의 상태 백스톱: 이 경로는 AuthenticationProvider(UserDetails.disabled
        // 검사)를 거치지 않고 authenticated 토큰을 직접 만들기 때문에, 여기서 재검사하지 않으면
        // 소셜 링크/이메일 인증 완료 같은 수렴 경로가 정지(SUSPENDED) 계정을 로그인시킬 수 있다.
        val user = userRepository.findById(userId).orElse(null)
        if (user == null || user.status != UserStatus.ACTIVE.name) {
            auditEventService.record(
                "LOGIN_REJECTED",
                user?.id,
                mapOf("method" to method, "reason" to (user?.status ?: "NOT_FOUND")),
            )
            clearSecurityContext(request, response)
            request.getSession(false)?.let {
                it.removeAttribute(PendingAuth.SESSION_KEY)
                it.removeAttribute(PendingSocialLink.SESSION_KEY)
            }
            return "redirect:/login?error=social"
        }

        request.changeSessionId()
        establishSecurityContext(request, response, userId)
        StepUp.markAuthTime(request, userId)
        loginEventService.recordLogin(request, user, method)

        request.getSession(false)?.let {
            it.removeAttribute(PendingAuth.SESSION_KEY)
            // 소셜 게이트를 경유한 경우 남아 있을 수 있는 공급자 신원도 함께 정리한다.
            it.removeAttribute(PendingSocialLink.SESSION_KEY)
        }

        val savedRequest = requestCache.getRequest(request, response)
        val target =
            if (savedRequest != null) {
                requestCache.removeRequest(request, response)
                savedRequest.redirectUrl
            } else {
                "/account"
            }
        return "redirect:$target"
    }

    /**
     * 로컬 UserDetails 기반 완전 인증을 SecurityContext 에 세워 세션에 저장한다.
     * 소셜 로그인도 이 경로로 승격되어 principal 모델이 UsernamePasswordAuthenticationToken 으로 통일된다
     * (OAuth2AuthenticationToken 을 세션에 남기지 않는다). 이미 인증된 세션의 principal 교체
     * (계정 연결 복귀 등)에도 쓰인다 — 이 경우 세션 ID 교체·리다이렉트 판단은 호출자가 결정한다.
     */
    fun establishSecurityContext(
        request: HttpServletRequest,
        response: HttpServletResponse,
        userId: UUID,
    ) {
        val user = userRepository.findById(userId).orElseThrow { AuthException(ErrorCode.USER_NOT_FOUND) }
        val userDetails = loginUserDetailsService.loadUserByUsername(user.email)
        val authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                userDetails,
                null,
                userDetails.authorities,
            )
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, request, response)
        // 세션 목록 표시용 클라이언트 메타(IP/브라우저) — 완전 인증 수립(로그인/재인증) 시점에만 기록한다.
        SessionMetadata.record(request)
    }

    /**
     * 세션에서 완전 인증을 제거한다. oauth2Login 성공 핸들러처럼 필터가 이미 저장한
     * OAuth2AuthenticationToken 을 걷어내야 하는 지점에서 직접 호출할 수 있다.
     */
    fun clearSecurityContext(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        SecurityContextHolder.clearContext()
        // 빈 컨텍스트를 저장하면 세션의 SPRING_SECURITY_CONTEXT 속성이 제거된다.
        securityContextRepository.saveContext(SecurityContextHolder.createEmptyContext(), request, response)
        // 방어적 보강: 저장 구현과 무관하게 세션 속성이 확실히 사라지도록 직접 제거한다.
        request.getSession(false)?.removeAttribute(
            HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        )
    }
}
