package com.taspa.server.scim

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView

/**
 * SCIM 표면의 **디스패치 단계** 오류를 SCIM Error 스키마로 내보내는 전송 어댑터.
 *
 * ★왜 advice(ScimErrorHandler)로 끝나지 않는가 — 이게 이 파일의 존재 이유다.
 * `HttpRequestMethodNotSupportedException` 은 **핸들러 매핑 도중**에 던져지므로 DispatcherServlet 이
 * 예외를 해석할 때 handler 가 null 이다. `ExceptionHandlerExceptionResolver` 는 그 상태에서
 * `ControllerAdviceBean.isApplicableToBeanType(null)` 로 advice 적용성을 따지는데, `basePackages`
 * 셀렉터가 걸린 advice 는 대상 타입을 알 수 없으므로 **적용되지 않는다**. 그래서 SCIM 경로의 405 는
 * scim 패키지 한정 advice 를 그냥 지나쳐 전역 GlobalExceptionHandler 로 갔고, 상태 코드는 405 로
 * 맞지만 본문이 앱 공용 포맷(errorCode/message)이라 SCIM 클라이언트가 파싱하지 못했다.
 * 전역 핸들러를 SCIM 용으로 고치면 다른 표면이 함께 바뀌므로, 경로로 좁힌 이 resolver 가
 * 그 한 칸을 메운다(비 SCIM 요청은 null 반환 → 기존 체인 그대로).
 *
 * 오류 형식·상태·헤더는 전부 [ScimErrorHandler] 에 위임한다 — 표현이 두 곳으로 갈라지지 않게.
 */
@Component
class ScimDispatchErrorResolver(
    private val scimErrorHandler: ScimErrorHandler,
    private val objectMapper: ObjectMapper,
) : HandlerExceptionResolver,
    Ordered {
    /** 표준 resolver 체인(ExceptionHandlerExceptionResolver 는 order 0)보다 먼저 본다. */
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun resolveException(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any?,
        ex: Exception,
    ): ModelAndView? {
        if (ex !is HttpRequestMethodNotSupportedException || !isScimRequest(request)) return null
        write(response, scimErrorHandler.handleMethodNotSupported(ex))
        // 빈 ModelAndView = "처리했고 렌더링할 뷰는 없다"(DispatcherServlet 규약).
        return ModelAndView()
    }

    /** SecurityConfig 의 scim 체인 매처(/scim/v2 이하)와 같은 범위. */
    private fun isScimRequest(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath ?: "")
        return path == SCIM_ROOT || path.startsWith("$SCIM_ROOT/")
    }

    private fun write(
        response: HttpServletResponse,
        entity: ResponseEntity<Map<String, Any?>>,
    ) {
        response.status = entity.statusCode.value()
        entity.headers.forEach { name, values ->
            // Content-Type 은 인코딩까지 함께 정해야 해서 아래에서 따로 설정한다.
            if (!name.equals(HttpHeaders.CONTENT_TYPE, ignoreCase = true)) {
                values.forEach { response.addHeader(name, it) }
            }
        }
        response.characterEncoding = Charsets.UTF_8.name()
        response.contentType = ScimMediaType.SCIM_JSON
        response.writer.write(objectMapper.writeValueAsString(entity.body))
    }

    private companion object {
        const val SCIM_ROOT = "/scim/v2"
    }
}
