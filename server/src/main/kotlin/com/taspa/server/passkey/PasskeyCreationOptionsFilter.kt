package com.taspa.server.passkey

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.security.authorization.AuthenticatedAuthorizationManager
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.security.web.webauthn.jackson.WebauthnJackson2Module
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations
import org.springframework.security.web.webauthn.registration.PublicKeyCredentialCreationOptionsRepository
import org.springframework.web.filter.OncePerRequestFilter
import java.util.function.Supplier

/**
 * SS 6.4.4 PublicKeyCredentialCreationOptionsFilter 의 동작 동일 대체 필터(바이트코드 실측 이식).
 *
 * 원본 필터는 옵션 저장소가 HttpSession 고정(setter 없음)이라 JDBC 세션 직렬화 블로커
 * (JdbcCreationOptionsRepository KDoc)를 우회할 수 없고, 6.4 DSL 에는 저장소 주입 옵션도 없다
 * (7.x 와 혼동 금지). SecurityConfig 가 빌드된 체인에서 원본 필터를 이 필터로 in-place 교체한다.
 *
 * 동작(원본과 동일): POST /webauthn/register/options 매칭 → 인증 확인(미인증 400) →
 * rpOperations 로 옵션 생성 → 저장소 save → WebauthnJackson2Module 로 JSON 응답.
 */
class PasskeyCreationOptionsFilter(
    private val rpOperations: WebAuthnRelyingPartyOperations,
    private val creationOptionsRepository: PublicKeyCredentialCreationOptionsRepository,
) : OncePerRequestFilter() {
    private val matcher = AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/webauthn/register/options")
    private val authorization = AuthenticatedAuthorizationManager.authenticated<HttpServletRequest>()
    private val securityContextHolderStrategy = SecurityContextHolder.getContextHolderStrategy()
    private val converter: HttpMessageConverter<Any> =
        MappingJackson2HttpMessageConverter(
            Jackson2ObjectMapperBuilder.json().modules(WebauthnJackson2Module()).build(),
        )

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!matcher.matches(request)) {
            filterChain.doFilter(request, response)
            return
        }
        val deferredContext = securityContextHolderStrategy.deferredContext
        val authentication = Supplier<Authentication> { deferredContext.get().authentication }
        val result = authorization.authorize(authentication, request)
        if (result == null || !result.isGranted) {
            response.status = HttpStatus.BAD_REQUEST.value()
            return
        }
        val options =
            rpOperations.createPublicKeyCredentialCreationOptions(
                ImmutablePublicKeyCredentialCreationOptionsRequest(authentication.get()),
            )
        creationOptionsRepository.save(request, response, options)
        response.status = HttpStatus.OK.value()
        response.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        converter.write(options, MediaType.APPLICATION_JSON, ServletServerHttpResponse(response))
    }
}
