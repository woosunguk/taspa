package com.taspa.server.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.taspa.server.common.exception.ErrorCode
import com.taspa.server.common.exception.ErrorResponse
import com.taspa.server.config.i18n.MessageResolver
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component

/**
 * API 경로(`/api` 이하)의 미인증 응답 — **로그인 페이지가 아니라 401 JSON** 을 돌려준다.
 *
 * API 가 302 로 로그인 페이지를 가리키면 브라우저 `fetch` 는 그 리다이렉트를 **투명하게 따라가** 결국
 * 200 + HTML 을 받는다. 호출자 입장에서는 성공한 요청이므로, 본문을 JSON 으로 파싱하다 터지거나
 * (SPA) 빈 객체를 정상 결과로 오인한다(서버 렌더링 admin 템플릿의 api() 헬퍼가 실패한 변경 작업을
 * 성공으로 보고하던 원인). 401 은 그 모호함을 없앤다 — 호출자는 "다시 로그인하라"를 정확히 안다.
 *
 * 본문 스키마는 [ErrorResponse] 로 [com.taspa.server.common.exception.GlobalExceptionHandler] 와 같다.
 * 인증 필터 단계는 컨트롤러 advice 가 닿지 않는 구간이라 여기서 직접 같은 모양을 만든다 —
 * 클라이언트가 오류 처리를 한 갈래로 유지할 수 있게(빈 401 이면 화면에 띄울 문구가 없다).
 */
@Component
class ApiAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
    private val messages: MessageResolver,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        // 인증 관문의 응답은 세션 상태에 따라 달라지므로 어떤 계층에도 캐시되면 안 된다.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        objectMapper.writeValue(
            response.outputStream,
            ErrorResponse(
                errorCode = ErrorCode.UNAUTHENTICATED.name,
                message = resolveMessage(),
            ),
        )
    }

    /**
     * 문구는 요청 로케일로 해석한다. 해석에 실패하더라도(메시지 키 누락·로케일 확인 불가) 401 자체는
     * 반드시 나가야 하므로 enum 의 영문 기본값으로 낙하한다 — 진입점이 예외를 던지면 미인증 요청이
     * 500 이 되어 원래 고치려던 모호함이 그대로 돌아온다.
     */
    private fun resolveMessage(): String =
        runCatching { messages.get("error.${ErrorCode.UNAUTHENTICATED.name}") }
            .getOrDefault(ErrorCode.UNAUTHENTICATED.message)
}
