package com.taspa.server.login

import java.io.Serializable
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class PendingAuthStage {
    EMAIL_VERIFICATION,
    MFA,

    /** 소셜 로그인: 같은 이메일의 기존 로컬 계정이 있으나 자동 연결 조건 미충족 — 이메일 코드로 본인 확인 후 연결. */
    SOCIAL_LINK,

    /** 소셜 로그인: 공급자가 이메일을 주지 않음(카카오 미동의) — 이메일 입력·확인 후 계정 생성·연결. */
    SOCIAL_EMAIL,

    /**
     * 리스크 기반 인증: 비밀번호 로그인에서 MEDIUM 이상 리스크가 감지된 MFA 미등록 사용자 —
     * 이메일 코드로 본인 확인. 미인증 계정은 EMAIL_VERIFICATION 게이트가 이메일 소유를
     * 증명하므로 이 게이트와 중복 발동하지 않는다(requiredGate 판정 순서로 보장).
     */
    RISK_CHALLENGE,
}

/**
 * 비밀번호(또는 소셜 1차 인증)는 통과했지만 아직 완전 인증되지 않은 상태.
 *
 * 이 데이터는 절대 SecurityContext 에 들어가지 않는다. 세션 속성으로만 보관하여
 * Spring Authorization Server 의 /oauth2/authorize 가 이 상태를 "인증됨"으로 오인해
 * authorization code 를 발급하는 취약점을 원천 차단한다.
 *
 * userId 는 SOCIAL_EMAIL 진입 직후(로컬 계정이 아직 없음)에만 null 이다.
 * 소셜 단계의 공급자 정보는 별도 세션 속성(PendingSocialLink)에 보관한다.
 *
 * method 는 1차 인증 수단 라벨(password / social:{provider} / magic) — 게이트 통과 후
 * login_events 의 method 기록에 쓰인다(MFA 게이트를 거치면 "mfa" 로 기록).
 */
data class PendingAuth(
    val userId: UUID?,
    val stage: PendingAuthStage,
    val expiresAt: Instant,
    val method: String? = null,
) : Serializable {
    fun isExpired(): Boolean = Instant.now().isAfter(expiresAt)

    companion object {
        const val SESSION_KEY = "TASPA_PENDING_AUTH"
        const val LOGIN_HINT_KEY = "TASPA_LOGIN_HINT"

        /**
         * 게이트가 **만료로** 사라졌다는 표식(1회성). `LoginFlowSupport.gateLostRedirect` 가 읽고 지운다 —
         * 만료와 "애초에 시작한 적 없음"을 구분해 사용자에게 사실만 말하기 위한 것이다.
         */
        const val EXPIRED_KEY = "TASPA_PENDING_EXPIRED"

        /**
         * 부분 인증 유효 시간.
         *
         * ★5분이었는데, 이메일 코드 게이트에는 **짧았다**: 메일 앱을 열고 코드를 찾아 돌아오는 사이에
         * 만료되면 정답 코드를 넣어도 처음으로 튕긴다. 10분은 부분 인증 상태(SecurityContext 에 들어가지
         * 않는다 — `docs/architecture.md` §7)의 노출 창으로 여전히 짧고, 업계 관행(로그인 트랜잭션
         * 10~15분)의 하단이다.
         */
        val TTL: Duration = Duration.ofMinutes(10)
    }
}
