package com.taspa.server.common.http

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * 요청 상관관계 ID 를 MDC(`correlationId`)에 심어 한 요청이 남긴 모든 로그 라인을 잇는다.
 * prod 의 구조화(ECS JSON) 출력은 MDC 를 최상위 필드로 자동 포함하고, 평문 출력은
 * `logging.pattern.correlation` 이 같은 값을 찍는다(application.yml).
 *
 * 값은 **의미 없는 랜덤 UUID** 다 — 사용자 식별자(이메일·userId)나 토큰을 상관관계 키로 쓰면
 * 로그 전체가 PII 저장소가 되므로 절대 대입하지 않는다.
 *
 * 인바운드 헤더(X-Request-Id → X-Correlation-Id 순)는 **정규화 후에만** 재사용한다:
 * 64자 이하 + [A-Za-z0-9_-] 만 허용한다. 개행·제어문자를 허용하면 클라이언트가 MDC 를 통해
 * 가짜 로그 라인을 위조할 수 있다(로그 인젝션). 위반 값은 조용히 버리고 새로 발급한다.
 *
 * MDC 는 스레드 로컬이라 **반드시 finally 에서 제거**한다 — 톰캣 워커가 재사용될 때
 * 이전 요청의 ID 가 다음 요청 로그에 새는 것을 막는다.
 *
 * 필터 순서는 HIGHEST_PRECEDENCE — 시큐리티 체인(-100)·rate limit(-101)보다 앞이라
 * 인증 이전 단계에서 거절된 요청의 로그까지 상관관계가 잡힌다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {
    // ASYNC/ERROR 디스패치도 다시 태워 MDC 를 복원한다(요청 속성으로 같은 ID 재사용) —
    // 특히 /error 포워딩 로그가 원 요청과 같은 ID 를 갖게 하려면 필요하다.
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    override fun shouldNotFilterErrorDispatch(): Boolean = false

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val correlationId = request.getAttribute(REQUEST_ATTRIBUTE) as? String ?: resolve(request)
        request.setAttribute(REQUEST_ATTRIBUTE, correlationId)
        MDC.put(MDC_KEY, correlationId)
        response.setHeader(REQUEST_ID_HEADER, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    private fun resolve(request: HttpServletRequest): String =
        sequenceOf(REQUEST_ID_HEADER, CORRELATION_ID_HEADER)
            .mapNotNull(request::getHeader)
            .firstOrNull(::isSafe)
            ?: UUID.randomUUID().toString()

    private fun isSafe(value: String): Boolean =
        value.length in 1..MAX_LENGTH &&
            value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

    companion object {
        const val MDC_KEY = "correlationId"
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val CORRELATION_ID_HEADER = "X-Correlation-Id"

        private const val MAX_LENGTH = 64
        private const val REQUEST_ATTRIBUTE = "com.taspa.server.correlationId"
    }
}
