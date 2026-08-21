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
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.csrf.MissingCsrfTokenException
import org.springframework.stereotype.Component

/**
 * API 경로의 **403 응답** — [ApiAuthenticationEntryPoint](401)와 짝을 이루는 나머지 절반.
 *
 * ★이게 없던 동안 SPA 의 모든 상태변경 화면이 같은 방식으로 실패했다. 세션이 만료된 뒤 '저장'을 누르면
 * 응답은 401 이 아니라 **CSRF 실패 403** 이고, 본문은 Spring Boot 기본 오류
 * (`{timestamp,status,error,path}`)라 우리 `errorCode` 가 없다. 그래서 `web/lib/api.ts` 의 로그인 이동은
 * 401 에서만 도니 걸리지 않고, `readError` 는 남은 `error` 필드를 그대로 써서 한국어 화면에
 * **"Forbidden (403)"** 이라는 영문 프레임워크 문구를 띄웠다. 옆의 '다시 시도' 버튼은 세션이 없으니
 * 영원히 같은 결과다 — 사용자는 무엇이 잘못됐는지도, 로그인하면 된다는 것도 알 수 없었다.
 *
 * 그래서 두 가지를 구분해 **우리 스키마로** 답한다:
 * - **CSRF 토큰 자체가 없음** → 세션이 사라진 것이다(토큰은 세션에 매여 있다). `UNAUTHENTICATED` 로
 *   내려보내 프런트가 401 과 **같은 경로**(로그인 이동)로 처리하게 한다. 사실 이건 인증 문제이지
 *   권한 문제가 아니다.
 * - **그 외** → 진짜 권한 부족. `FORBIDDEN` 으로 내려보내되 문구는 한국어로 준다.
 *
 * ★상태코드는 그대로 403 이다. 401 로 바꾸면 "인증 헤더를 다시 보내라"는 뜻이 되어 브라우저·프록시의
 * 동작이 달라지고, 무엇보다 CSRF 실패와 권한 부족이 응답 코드에서 구별되지 않게 된다.
 * 구분은 본문의 errorCode 가 진다.
 */
@Component
class ApiAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
    private val messages: MessageResolver,
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        // 토큰이 아예 없는 경우만 "세션 소실"로 본다. 토큰이 있는데 값이 다른 경우(InvalidCsrfTokenException)는
        // 실제 위조 시도일 수 있으므로 권한 부족 쪽에 남긴다.
        val sessionLost = accessDeniedException is MissingCsrfTokenException
        val code = if (sessionLost) ErrorCode.UNAUTHENTICATED else ErrorCode.FORBIDDEN

        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        objectMapper.writeValue(
            response.outputStream,
            ErrorResponse(errorCode = code.name, message = resolveMessage(code)),
        )
    }

    /** 진입점과 같은 이유로 문구 해석 실패가 403 자체를 500 으로 바꾸지 않게 한다. */
    private fun resolveMessage(code: ErrorCode): String = runCatching { messages.get("error.${code.name}") }.getOrDefault(code.message)
}
