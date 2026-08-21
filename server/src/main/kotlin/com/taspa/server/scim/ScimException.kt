package com.taspa.server.scim

import org.springframework.http.HttpStatus

/**
 * SCIM 프로토콜 오류(RFC 7644 §3.12). ScimErrorHandler 가
 * urn:ietf:params:scim:api:messages:2.0:Error 표현으로 변환한다(기존 GlobalExceptionHandler 포맷과 분리).
 * scimType 은 RFC 정의 키워드만 사용한다(invalidFilter/uniqueness/invalidValue/invalidPath/invalidSyntax).
 */
class ScimException(
    val status: HttpStatus,
    val scimType: String? = null,
    val detail: String,
) : RuntimeException(detail)
