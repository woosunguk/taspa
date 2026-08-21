package com.taspa.server.common.exception

import com.taspa.server.config.i18n.MessageResolver
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.DateTimeException
import java.time.Instant

data class ErrorResponse(
    val errorCode: String,
    val message: String,
    val timestamp: Instant = Instant.now(),
)

@RestControllerAdvice
class GlobalExceptionHandler(
    private val messages: MessageResolver,
) {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /** 키가 없거나 해석에 실패해도 오류 응답 자체가 500 이 되지 않도록 ErrorCode 기본 문구로 폴백한다. */
    private fun localized(
        code: String,
        fallback: String,
    ): String = runCatching { messages.get(code) }.getOrDefault(fallback)

    @ExceptionHandler(AuthException::class)
    fun handleAuthException(ex: AuthException): ResponseEntity<ErrorResponse> {
        val status =
            when (ex.errorCode) {
                ErrorCode.EMAIL_ALREADY_EXISTS -> HttpStatus.CONFLICT
                ErrorCode.INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED
                ErrorCode.USER_NOT_FOUND -> HttpStatus.NOT_FOUND
                ErrorCode.VALIDATION_ERROR -> HttpStatus.BAD_REQUEST
                ErrorCode.PASSWORD_POLICY_VIOLATION -> HttpStatus.BAD_REQUEST
                ErrorCode.ACCOUNT_LOCKED -> HttpStatus.UNAUTHORIZED
                ErrorCode.ACCOUNT_SUSPENDED -> HttpStatus.FORBIDDEN
                ErrorCode.MFA_ALREADY_ENABLED -> HttpStatus.CONFLICT
                ErrorCode.MFA_NOT_ENABLED -> HttpStatus.CONFLICT
                ErrorCode.MFA_NOT_SETUP -> HttpStatus.CONFLICT
                ErrorCode.MFA_INVALID_CODE -> HttpStatus.BAD_REQUEST
                ErrorCode.RESET_TOKEN_EXPIRED -> HttpStatus.BAD_REQUEST
                ErrorCode.RESET_TOKEN_INVALID -> HttpStatus.BAD_REQUEST
                // 주로 인증 필터의 ApiAuthenticationEntryPoint 가 직접 쓰는 코드지만, 컨트롤러가 던질 수도
                // 있으므로 여기서도 401 로 매핑해 둔다(같은 코드가 두 경로에서 다른 상태를 내지 않게).
                ErrorCode.UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED
                ErrorCode.REAUTH_REQUIRED -> HttpStatus.UNAUTHORIZED
                ErrorCode.LAST_LOGIN_METHOD -> HttpStatus.CONFLICT
                ErrorCode.CLIENT_ID_ALREADY_EXISTS -> HttpStatus.CONFLICT
                ErrorCode.CLIENT_NOT_CONFIDENTIAL -> HttpStatus.CONFLICT
                ErrorCode.ADMIN_SELF_ACTION -> HttpStatus.CONFLICT
                ErrorCode.NOT_FOUND -> HttpStatus.NOT_FOUND
                ErrorCode.FORBIDDEN -> HttpStatus.FORBIDDEN
                ErrorCode.EMAIL_UNCHANGED -> HttpStatus.BAD_REQUEST
                ErrorCode.EMAIL_CHANGE_NOT_PENDING -> HttpStatus.CONFLICT
                ErrorCode.VERIFICATION_CODE_INVALID -> HttpStatus.BAD_REQUEST
                ErrorCode.CURRENT_PASSWORD_INCORRECT -> HttpStatus.BAD_REQUEST
                ErrorCode.CONFIRMATION_MISMATCH -> HttpStatus.BAD_REQUEST
                ErrorCode.INVITATION_INVALID -> HttpStatus.BAD_REQUEST
                ErrorCode.INVITATION_EXPIRED -> HttpStatus.BAD_REQUEST
                ErrorCode.INVITATION_EMAIL_MISMATCH -> HttpStatus.FORBIDDEN
                ErrorCode.DOMAIN_ALREADY_CLAIMED -> HttpStatus.CONFLICT
                // 식권 QR 폐쇄루프 — POS 는 errorCode 문자열로 분기한다(HTTP 상태는 보조 신호).
                ErrorCode.QR_TOKEN_INVALID -> HttpStatus.BAD_REQUEST
                ErrorCode.QR_TOKEN_EXPIRED -> HttpStatus.BAD_REQUEST
                ErrorCode.QR_TOKEN_ALREADY_USED -> HttpStatus.CONFLICT
                ErrorCode.QR_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS
                ErrorCode.MEAL_WINDOW_CLOSED -> HttpStatus.UNPROCESSABLE_ENTITY
                ErrorCode.DAILY_MEAL_LIMIT -> HttpStatus.UNPROCESSABLE_ENTITY
                ErrorCode.NOT_EMPLOYED -> HttpStatus.FORBIDDEN
                ErrorCode.MERCHANT_SUSPENDED -> HttpStatus.FORBIDDEN
                ErrorCode.INVOICE_ALREADY_FINALIZED -> HttpStatus.CONFLICT
                ErrorCode.INVOICE_STALE -> HttpStatus.CONFLICT
                ErrorCode.IAM_POLICY_NOT_FOUND -> HttpStatus.NOT_FOUND
                ErrorCode.IAM_POLICY_IMMUTABLE -> HttpStatus.CONFLICT
                ErrorCode.IAM_VALIDATION -> HttpStatus.BAD_REQUEST
                ErrorCode.IAM_CONFLICT -> HttpStatus.CONFLICT
                ErrorCode.IAM_LOCKOUT -> HttpStatus.CONFLICT
                ErrorCode.MEAL_POLICY_OVERRIDE_EXISTS -> HttpStatus.CONFLICT
                ErrorCode.METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED
                ErrorCode.UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE
                ErrorCode.INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR
            }
        // 서비스가 구체적인 문구를 직접 넘긴 경우(예: "해당 조직의 멤버가 아닙니다")는 그 문구가 더 정확하므로
        // 우선한다. 기본 메시지 그대로면 요청 로케일 문구로 해석한다.
        val message =
            if (ex.message != ex.errorCode.message) {
                ex.message
            } else {
                localized("error.${ex.errorCode.name}", ex.errorCode.message)
            }
        val body =
            ErrorResponse(
                errorCode = ex.errorCode.name,
                message = message,
            )
        return ResponseEntity.status(status).body(body)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val message =
            ex.bindingResult.fieldErrors
                .joinToString(", ") { "${it.field}: ${it.defaultMessage}" }
        val body =
            ErrorResponse(
                errorCode = ErrorCode.VALIDATION_ERROR.name,
                message = message,
            )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    /**
     * DB 무결성 위반(UNIQUE 등) → 409 CONFLICT. 소비 이벤트 멱등 적재의 동시 재전송처럼 같은 멱등키를 두
     * 트랜잭션이 동시에 insert 하면 한쪽이 UNIQUE 위반이 되는데, 이를 500(INTERNAL_ERROR)이 아니라 재시도
     * 안전한 409 로 매핑한다(멱등 계약 유지 — 생산자의 재시도는 이후 no-op 갱신으로 수렴). 원문 메시지는
     * 스키마 세부를 노출할 수 있어 일반 메시지로 대체한다.
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(ex: DataIntegrityViolationException): ResponseEntity<ErrorResponse> {
        log.warn("Data integrity violation (mapped to 409): {}", ex.mostSpecificCause.message)
        val body =
            ErrorResponse(
                errorCode = "CONFLICT",
                message = localized("error.dataConflict", "요청이 기존 데이터와 충돌합니다. 잠시 후 다시 시도하세요."),
            )
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body)
    }

    /**
     * 본문 파싱 실패(누락·JSON 파손·non-null 프로퍼티 null) → 400. 핸들러가 없으면 Exception 캐치올이
     * 먼저 잡아 500(INTERNAL_ERROR) + error 로그가 된다 — 클라이언트 입력 오류는 서버 장애가 아니다.
     * SCIM 경로는 ScimErrorHandler(@Order HIGHEST_PRECEDENCE, scim 패키지 한정)가 우선한다.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(ex: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        val body =
            ErrorResponse(
                errorCode = ErrorCode.VALIDATION_ERROR.name,
                message = localized("error.malformedBody", "요청 본문이 없거나 형식이 올바르지 않습니다"),
            )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    /**
     * 요청 바인딩 실패(타입 불일치·필수 파라미터/헤더 누락·메서드 파라미터 검증 위반) → 400.
     * 전부 **클라이언트 입력 오류**라 서버 장애가 아니다. 전용 핸들러가 없으면 Exception 캐치올이
     * 삼켜 500 + `log.error` 스택트레이스가 되고, 그 노이즈가 운영에서 진짜 500 을 가린다.
     * 그래서 여기서는 `log.debug` 만 남긴다(조사에 필요한 최소 흔적).
     *
     * ★검증 예외가 **두 종류**인 이유: Spring 6.1(Boot 3.4)부터 컨트롤러 메서드 파라미터 검증은
     * `ConstraintViolationException` 이 아니라 `HandlerMethodValidationException` 을 던진다. 지금
     * 이 코드베이스에는 `@Validated` 컨트롤러가 없어 둘 다 도달 불가지만, 나중에 누가 붙였을 때
     * 후자만 빠져 조용히 500 이 되는 함정을 막으려 함께 등록해 둔다.
     */
    @ExceptionHandler(
        MethodArgumentTypeMismatchException::class,
        MissingServletRequestParameterException::class,
        MissingRequestHeaderException::class,
        ConstraintViolationException::class,
        HandlerMethodValidationException::class,
    )
    fun handleBindingFailure(ex: Exception): ResponseEntity<ErrorResponse> {
        log.debug("Request binding failure: {}", ex.message)
        val body =
            ErrorResponse(
                errorCode = ErrorCode.VALIDATION_ERROR.name,
                message = localized("error.${ErrorCode.VALIDATION_ERROR.name}", ErrorCode.VALIDATION_ERROR.message),
            )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    /**
     * 지원하지 않는 HTTP 메서드 → 405. RFC 9110 이 요구하는 `Allow` 헤더를 반드시 실어 준다
     * (Spring 기본 처리에서는 붙던 것이 캐치올을 타면서 사라졌다).
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(ex: HttpRequestMethodNotSupportedException): ResponseEntity<ErrorResponse> {
        log.debug("Method not supported: {}", ex.message)
        val body =
            ErrorResponse(
                errorCode = ErrorCode.METHOD_NOT_ALLOWED.name,
                message = localized("error.${ErrorCode.METHOD_NOT_ALLOWED.name}", ErrorCode.METHOD_NOT_ALLOWED.message),
            )
        val builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        ex.supportedHttpMethods?.takeIf { it.isNotEmpty() }?.let { builder.allow(*it.toTypedArray()) }
        return builder.body(body)
    }

    /** 지원하지 않는 Content-Type → 415. 수용 가능한 타입은 `Accept` 헤더로 알린다. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleMediaTypeNotSupported(ex: HttpMediaTypeNotSupportedException): ResponseEntity<ErrorResponse> {
        log.debug("Media type not supported: {}", ex.message)
        val body =
            ErrorResponse(
                errorCode = ErrorCode.UNSUPPORTED_MEDIA_TYPE.name,
                message =
                    localized(
                        "error.${ErrorCode.UNSUPPORTED_MEDIA_TYPE.name}",
                        ErrorCode.UNSUPPORTED_MEDIA_TYPE.message,
                    ),
            )
        val builder = ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        ex.supportedMediaTypes
            .takeIf { it.isNotEmpty() }
            ?.let { builder.header(HttpHeaders.ACCEPT, MediaType.toString(it)) }
        return builder.body(body)
    }

    /**
     * 날짜/시간 계산 실패 → 400. 컨트롤러가 범위 가드로 대부분 막지만(그쪽이 구체적 문구를 준다),
     * 가드가 없는 경로에서 극단값이 `plusDays` 오버플로로 새어 나오면 여기서 500 이 되는 것을 막는다.
     * DateTimeParseException 도 이 계층에 포함된다.
     *
     * ★여기만 `log.warn` 이다. 같은 예외 타입으로 **DB/스키마 이상**도 올 수 있기 때문이다 — 집계
     * 쿼리 결과를 날짜로 강제 변환하는 폴백(`LocalDate.parse(value.toString())`)이 그렇다. 그 경우
     * 응답은 "입력값이 올바르지 않습니다" 400 으로 위장되므로, debug 로 남기면 운영에서 흔적조차
     * 사라진다. 빈도가 낮아 노이즈 우려도 없다.
     */
    @ExceptionHandler(DateTimeException::class)
    fun handleDateTimeException(ex: DateTimeException): ResponseEntity<ErrorResponse> {
        log.warn("Date/time value rejected (client input or data anomaly): {}", ex.message)
        val body =
            ErrorResponse(
                errorCode = ErrorCode.VALIDATION_ERROR.name,
                message = localized("error.${ErrorCode.VALIDATION_ERROR.name}", ErrorCode.VALIDATION_ERROR.message),
            )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class)
    fun handleNotFound(ex: Exception): ResponseEntity<ErrorResponse> {
        val body =
            ErrorResponse(
                errorCode = ErrorCode.NOT_FOUND.name,
                message = localized("error.${ErrorCode.NOT_FOUND.name}", ErrorCode.NOT_FOUND.message),
            )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body)
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unhandled exception", ex)
        val body =
            ErrorResponse(
                errorCode = ErrorCode.INTERNAL_ERROR.name,
                message = localized("error.${ErrorCode.INTERNAL_ERROR.name}", ErrorCode.INTERNAL_ERROR.message),
            )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body)
    }
}
