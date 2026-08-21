package com.taspa.server.federation

import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

/**
 * userinfo 원격 호출용 타임아웃 설정 RestTemplate 을 만든다.
 * DefaultOAuth2UserService 기본 RestTemplate 은 SimpleClientHttpRequestFactory 기본값(connect/read 무한)이라,
 * 카카오·네이버·구글 userinfo 가 느려지면 OAuth2 콜백 스레드가 무한 대기한다 → connect/read 5s 상한.
 * 기본 서비스와 동일하게 OAuth2ErrorResponseErrorHandler 를 달아 4xx/5xx 파싱 동작을 보존한다.
 */
private fun timeoutRestOperations(): RestTemplate {
    val factory =
        SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(5_000)
            setReadTimeout(5_000)
        }
    return RestTemplate().apply {
        requestFactory = factory
        errorHandler = OAuth2ErrorResponseErrorHandler()
    }
}

/**
 * 순수 OAuth2 공급자(카카오·네이버)용 커스텀 UserService — DefaultOAuth2UserService 델리게이트.
 *
 * 네이버 userinfo 는 {resultcode, message, response:{id, email, name, ...}} 로 실제 프로필이
 * response 아래에 중첩되어 있고 최상위에는 사용자 식별자가 없다. 등록 시 user-name-attribute 를
 * "response" 로 두어 델리게이트 파싱을 통과시킨 뒤, 여기서 response 를 평탄화하고 "id" 를
 * name attribute 로 하는 DefaultOAuth2User 로 재구성한다. 카카오는 최상위 id 를 그대로 쓴다.
 */
@Component
class FederatedOAuth2UserService : OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private val delegate = DefaultOAuth2UserService().apply { setRestOperations(timeoutRestOperations()) }

    override fun loadUser(userRequest: OAuth2UserRequest): OAuth2User {
        val user = delegate.loadUser(userRequest)
        if (userRequest.clientRegistration.registrationId != SocialProviders.NAVER) {
            return user
        }
        val response =
            user.attributes["response"] as? Map<*, *>
                ?: throw OAuth2AuthenticationException(
                    OAuth2Error("invalid_user_info_response"),
                    "naver userinfo has no response object",
                )
        val flattened = response.entries.associate { (key, value) -> key.toString() to (value as Any) }
        if (flattened["id"] == null) {
            throw OAuth2AuthenticationException(
                OAuth2Error("invalid_user_info_response"),
                "naver userinfo response has no id",
            )
        }
        return DefaultOAuth2User(setOf(SimpleGrantedAuthority("OAUTH2_USER")), flattened, "id")
    }
}

/**
 * OIDC 공급자(구글)용 커스텀 UserService — 기본 OidcUserService 델리게이트.
 * 구글은 표준 클레임(sub/email/email_verified/name)을 그대로 쓰므로 변환이 없다.
 * 정규화는 SocialAttributesExtractor 가 담당한다.
 */
@Component
class FederatedOidcUserService : OAuth2UserService<OidcUserRequest, OidcUser> {
    // OidcUserService 는 내부적으로 DefaultOAuth2UserService 로 userinfo 를 호출한다 —
    // 타임아웃 설정 서비스를 주입해 구글 userinfo 지연 시에도 콜백 스레드가 무한 대기하지 않게 한다.
    private val delegate =
        OidcUserService().apply {
            setOauth2UserService(DefaultOAuth2UserService().apply { setRestOperations(timeoutRestOperations()) })
        }

    override fun loadUser(userRequest: OidcUserRequest): OidcUser = delegate.loadUser(userRequest)
}
