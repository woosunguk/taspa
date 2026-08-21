package com.taspa.server.scim

import com.taspa.server.common.exception.AuthException
import com.taspa.server.common.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * SCIM 전용 오류 변환기 — **scim 패키지 컨트롤러에만** 적용된다(basePackages 한정 + HIGHEST_PRECEDENCE 로
 * 전역 GlobalExceptionHandler 보다 먼저 매칭). 기존 앱 오류 포맷(ErrorResponse)은 무영향.
 * 응답은 RFC 7644 §3.12 의 urn:ietf:params:scim:api:messages:2.0:Error 형식(status 는 문자열).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = ["com.taspa.server.scim"])
class ScimErrorHandler {
    private val log = LoggerFactory.getLogger(ScimErrorHandler::class.java)

    @ExceptionHandler(ScimException::class)
    fun handleScim(ex: ScimException): ResponseEntity<Map<String, Any?>> = scimError(ex.status, ex.scimType, ex.detail)

    /**
     * 재사용하는 내부 서비스(OrganizationService 등)가 던지는 AuthException 을 SCIM 오류로 번역한다.
     * 상태 매핑은 GlobalExceptionHandler 와 동일 사상(NOT_FOUND→404, FORBIDDEN→403, VALIDATION→400 …)을
     * 최소로 복제한다 — SCIM 표면에서는 형식만 SCIM Error 로 바뀐다.
     */
    @ExceptionHandler(AuthException::class)
    fun handleAuth(ex: AuthException): ResponseEntity<Map<String, Any?>> {
        val status =
            when (ex.errorCode) {
                ErrorCode.NOT_FOUND, ErrorCode.USER_NOT_FOUND -> HttpStatus.NOT_FOUND
                ErrorCode.FORBIDDEN -> HttpStatus.FORBIDDEN
                ErrorCode.VALIDATION_ERROR -> HttpStatus.BAD_REQUEST
                ErrorCode.EMAIL_ALREADY_EXISTS -> HttpStatus.CONFLICT
                else -> HttpStatus.BAD_REQUEST
            }
        return scimError(status, if (status == HttpStatus.BAD_REQUEST) "invalidValue" else null, ex.message)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException): ResponseEntity<Map<String, Any?>> =
        scimError(HttpStatus.BAD_REQUEST, "invalidSyntax", "Request body is not valid SCIM JSON")

    /**
     * check-then-write 경합(동시 중복 POST·externalId 배정)이 DB UNIQUE 위반으로 커밋에서 거부된 경우 —
     * 격리는 지켜졌으므로(fail-closed) 500 이 아니라 409 uniqueness 다. 409 는 프로비저너(Azure AD)가
     * 조회-후-갱신 표준 흐름으로 자연 수습하지만 500 은 재시도/quarantine 대상으로 취급한다.
     * GlobalExceptionHandler 의 409 매핑은 이 advice 가 우선 매칭돼 SCIM 컨트롤러에는 도달하지 않으므로 복제한다.
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleIntegrity(ex: DataIntegrityViolationException): ResponseEntity<Map<String, Any?>> =
        scimError(HttpStatus.CONFLICT, "uniqueness", "Resource conflicts with an existing resource")

    /** 쿼리 파라미터 바인딩 실패(startIndex=abc 등) — 클라이언트 입력 오류라 500 이 아닌 400 invalidValue. */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ResponseEntity<Map<String, Any?>> =
        scimError(HttpStatus.BAD_REQUEST, "invalidValue", "Invalid value for parameter '${ex.name}'")

    /**
     * 지원하지 않는 HTTP 메서드 → 405 (+ RFC 9110 이 요구하는 `Allow`). 캐치올이 삼키면 500 이 되는데,
     * Azure AD 프로비저너는 500 을 quarantine 사유로 취급한다 — 잘못된 메서드 한 번에 그 조직의 연동
     * 전체가 격리될 수 있다. 본문은 앱 공용 ErrorResponse 가 아니라 SCIM Error 스키마여야 프로비저너가
     * 파싱한다.
     *
     * ★이 메서드는 `ScimDispatchErrorResolver` 가 직접 호출하기도 한다 — 그 이유는 그쪽 주석 참고.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(ex: HttpRequestMethodNotSupportedException): ResponseEntity<Map<String, Any?>> {
        log.debug("SCIM method not supported: {}", ex.message)
        val builder =
            ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.CONTENT_TYPE, ScimMediaType.SCIM_JSON)
        ex.supportedHttpMethods?.takeIf { it.isNotEmpty() }?.let { builder.allow(*it.toTypedArray()) }
        return builder.body(
            scimErrorBody(HttpStatus.METHOD_NOT_ALLOWED, null, "Request method is not supported for this resource"),
        )
    }

    /** 지원하지 않는 Content-Type → 415. 405 와 같은 이유(500 = quarantine)로 캐치올에 넘기지 않는다. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleMediaTypeNotSupported(ex: HttpMediaTypeNotSupportedException): ResponseEntity<Map<String, Any?>> {
        log.debug("SCIM media type not supported: {}", ex.message)
        return scimError(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            null,
            "Request content type is not supported; use ${ScimMediaType.SCIM_JSON} or application/json",
        )
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<Map<String, Any?>> {
        log.error("SCIM internal error", ex)
        return scimError(HttpStatus.INTERNAL_SERVER_ERROR, null, "Internal server error")
    }

    private fun scimError(
        status: HttpStatus,
        scimType: String?,
        detail: String,
    ): ResponseEntity<Map<String, Any?>> =
        ResponseEntity
            .status(status)
            .header(HttpHeaders.CONTENT_TYPE, ScimMediaType.SCIM_JSON)
            .body(scimErrorBody(status, scimType, detail))

    /** RFC 7644 §3.12 Error 표현(status 는 문자열) — 이 표면의 오류 형식 단일 출처. */
    private fun scimErrorBody(
        status: HttpStatus,
        scimType: String?,
        detail: String,
    ): Map<String, Any?> =
        buildMap {
            put("schemas", listOf("urn:ietf:params:scim:api:messages:2.0:Error"))
            scimType?.let { put("scimType", it) }
            put("detail", detail)
            put("status", status.value().toString())
        }
}
