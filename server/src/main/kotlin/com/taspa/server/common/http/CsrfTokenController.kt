package com.taspa.server.common.http

import org.springframework.http.ResponseEntity
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** SPA 가 상태변경 요청에 실을 CSRF 토큰. */
data class CsrfTokenView(
    val headerName: String,
    val token: String,
)

/**
 * SPA 용 CSRF 토큰 조달 엔드포인트.
 *
 * 서버 렌더링 화면은 `<meta name="_csrf">` 로 토큰을 받지만, 별도로 렌더링되는 SPA 는 그 태그를 읽을 수 없다.
 * 토큰은 **세션에 묶여 있고**(HttpSessionCsrfTokenRepository) 이 엔드포인트는 인증된 세션에서만 접근
 * 가능하므로(default 체인 anyRequest().authenticated()), 노출 범위는 meta 태그와 동일하다.
 * 타 오리진 스크립트는 CORS 가 기본 거부라 응답을 읽지 못한다.
 *
 * GET 이므로 CsrfFilter 검증 대상이 아니다(토큰을 받으러 오는 요청이 토큰을 요구하는 순환을 만들지 않는다).
 */
@RestController
class CsrfTokenController {
    @GetMapping("/api/csrf")
    fun token(token: CsrfToken?): ResponseEntity<CsrfTokenView> =
        token?.let { ResponseEntity.ok(CsrfTokenView(it.headerName, it.token)) }
            ?: ResponseEntity.noContent().build()
}
