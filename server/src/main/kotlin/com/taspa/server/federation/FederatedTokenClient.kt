package com.taspa.server.federation

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.http.converter.FormHttpMessageConverter
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter
import org.springframework.web.client.RestClient

/**
 * 소셜 로그인 **토큰 교환**(authorization_code → access_token) 전용 타임아웃 클라이언트.
 *
 * 기본 토큰 응답 클라이언트는 RestClient 기본 요청 팩토리(connect/read 무한)를 쓴다. 구글·카카오·네이버의
 * 토큰 엔드포인트가 지연되면 OAuth2 콜백을 처리하는 Tomcat 워커가 무한 대기하고, 이는 워커 고갈 → IdP 전면
 * 마비로 번진다. userinfo 는 이미 같은 이유로 방어돼 있는데(FederatedUserServices) 그 **앞단인 토큰 교환**이
 * 비어 있었다 — 콜백 경로 전체를 상한 안에 둔다.
 *
 * 메시지 컨버터·에러 핸들러는 기본 구성과 동일하게 구성해 토큰 응답 파싱과 OAuth2 에러 변환 동작을 보존한다.
 */
@Configuration
class FederatedTokenClient {
    @Bean
    fun authorizationCodeTokenResponseClient(): OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> {
        val factory =
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(TIMEOUT_MILLIS)
                setReadTimeout(TIMEOUT_MILLIS)
            }
        val restClient =
            RestClient
                .builder()
                .requestFactory(factory)
                .messageConverters { converters ->
                    converters.clear()
                    converters.add(FormHttpMessageConverter())
                    converters.add(OAuth2AccessTokenResponseHttpMessageConverter())
                }.defaultStatusHandler(OAuth2ErrorResponseErrorHandler())
                .build()
        return RestClientAuthorizationCodeTokenResponseClient().apply { setRestClient(restClient) }
    }

    private companion object {
        /** userinfo(FederatedUserServices)와 동일한 5초 상한 — 콜백 경로 전체가 같은 예산 안에 있다. */
        const val TIMEOUT_MILLIS = 5_000
    }
}
