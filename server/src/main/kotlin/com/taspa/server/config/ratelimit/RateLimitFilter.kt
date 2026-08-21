package com.taspa.server.config.ratelimit

import com.taspa.server.common.http.RequestClientInfo
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.MessageSource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.LocaleResolver
import org.springframework.web.util.UrlPathHelper

/**
 * 인증 엔드포인트 IP×엔드포인트 rate limit 필터.
 *
 * 스프링 시큐리티 필터체인보다 앞(더 높은 우선순위)에 배치돼(RateLimitConfig), 인증/세션/CSRF
 * 처리 전에 차단한다 — 비밀번호·클라이언트 시크릿 그라인딩, 이메일 폭탄(password-reset/magic-link),
 * device user_code 무차별 대입(device_verification), /oauth2/token bcrypt CPU-DoS 를 앞단에서 흡수한다.
 *
 * IP 는 RequestClientInfo.ip(remoteAddr) 를 쓴다 — prod 에서는 forward-headers-strategy=native 로
 * Tomcat RemoteIpValve 가 이미 remoteAddr 를 실 클라이언트 IP 로 재작성한 뒤다.
 *
 * 경로 매칭은 raw requestURI 가 아니라 UrlPathHelper 로 **URL 디코드 + 세미콜론(matrix) 파라미터 제거**한
 * 경로를 쓴다 — Spring Security/Authorization Server 의 엔드포인트 매칭과 동일 정규화라서, `/login/%70assword`
 * 나 `/oauth2/token;x=1` 로 rate limit 을 우회하면서 실제 엔드포인트로는 라우팅되는 불일치를 막는다.
 */
class RateLimitFilter(
    private val rateLimiter: RateLimiter,
    private val messageSource: MessageSource,
    private val localeResolver: LocaleResolver,
) : OncePerRequestFilter() {
    // Spring MVC/Security 기본과 동일: URL 디코드 + 세미콜론 콘텐츠 제거(명시적으로 고정).
    private val pathHelper =
        UrlPathHelper().apply {
            setUrlDecode(true)
            setRemoveSemicolonContent(true)
        }

    private data class Rule(
        val group: String,
        val matches: (method: String, path: String) -> Boolean,
    )

    // 그룹별로 별도 버킷을 둔다("ip|group") — 한 엔드포인트 폭주가 다른 엔드포인트를 굶기지 않게.
    private val rules =
        listOf(
            Rule("token") { _, p -> p == "/oauth2/token" },
            Rule("login-password") { m, p -> m == "POST" && p == "/login/password" },
            Rule("login-identifier") { m, p -> m == "POST" && p == "/login/identifier" },
            Rule("webauthn") { _, p -> p.startsWith("/webauthn/") },
            Rule("password-reset") { m, p -> m == "POST" && p == "/password-reset" },
            Rule("magic-link") { m, p -> m == "POST" && p == "/login/magic/request" },
            // ★**수신자를 요청자가 정하는 발송 경로**는 전부 여기서 묶는다. 제한이 없으면 주소 목록만
            // 있으면 우리가 스팸 발송기가 되고(수신자 피해 + 발송 도메인 평판 손실) users 테이블도 부푼다.
            // 사용자 단위 쿨다운(EmailVerificationService.resend 의 60초)은 **이미 그 사용자에게 보낸 적이
            // 있을 때만** 작동하므로, 매번 새 주소를 대는 공격에는 아무 제한이 되지 못한다 — IP 버킷이 필요하다.
            //
            // 가입: HTML 폼과 JSON API 는 **같은 표적**이라 한 버킷을 쓴다(나누면 두 경로를 번갈아 쳐서
            //   실효 한도가 두 배가 된다).
            Rule("signup") { m, p -> m == "POST" && (p == "/signup" || p == "/api/accounts/signup") },
            // 소셜 이메일 보완(카카오 미동의 등): 스로틀 없는 issue() 를 타고, **호출마다 새 users 행까지
            //   만든다**. 가입과 같은 표적이라 같은 버킷에 둔다.
            Rule("signup") { m, p -> m == "POST" && p == "/login/social-email" },
            // 이메일 변경: 로그인 계정 하나가 임의 제3자 주소로 코드를 무제한 보낼 수 있었다.
            //   계정이 있어야 하므로 표적이 다르다 — 별도 버킷(가입 폭주가 정상 사용자의 주소 변경을
            //   굶기지 않게).
            Rule("email-change") { m, p -> m == "POST" && p == "/api/account/email/change" },
            // Device Authorization Grant(RFC 8628 §5.2): user_code 무차별 대입을 IP 단위로 제한한다.
            // SAS 는 오답 user_code 에 오류만 반환할 뿐 lockout 이 없어(방어가 엔트로피+짧은 TTL 에만 의존),
            // 앱단에서 시도 횟수를 제한한다. 프리필 GET·제출 POST 모두 매칭한다.
            Rule("device-verification") { _, p -> p == "/oauth2/device_verification" },
        )

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = pathHelper.getPathWithinApplication(request)
        val group = rules.firstOrNull { it.matches(request.method, path) }?.group
        if (group == null) {
            filterChain.doFilter(request, response)
            return
        }
        val key = "${RequestClientInfo.ip(request)}|$group"
        if (rateLimiter.tryAcquire(key)) {
            filterChain.doFilter(request, response)
        } else {
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            response.setHeader("Retry-After", "60")
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            // 이 필터는 DispatcherServlet(LocaleChangeInterceptor) 앞이라 ?lang= 쿼리는 아직
            // 반영되지 않는다 — 리졸버로 쿠키(TASPA_LOCALE)/Accept-Language 기반 로케일만 해석한다.
            val locale = localeResolver.resolveLocale(request)
            val message = messageSource.getMessage("error.rateLimited", null, locale)
            response.writer.write(
                """{"error":"rate_limited","message":"${jsonEscape(message)}"}""",
            )
        }
    }

    /** 로케일화된 메시지를 JSON 문자열에 안전하게 넣기 위한 최소 이스케이프(따옴표·역슬래시·제어문자). */
    private fun jsonEscape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }
}
