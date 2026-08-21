package com.taspa.server.federation

import com.taspa.server.enterprise.SsoConnectionService
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Component

/**
 * 공급자별 userinfo 응답(중첩 구조 포함)을 SocialAttributes 로 정규화한다.
 *
 * - google(OIDC): 표준 클레임 sub/email/email_verified/name.
 * - kakao: 최상위 id(Long) + kakao_account { email, is_email_valid, is_email_verified, profile { nickname } }.
 *   이메일은 동의 거부 시 없을 수 있다.
 * - naver: FederatedOAuth2UserService 가 response 를 평탄화한 뒤이므로 id/email/name/nickname 이 최상위.
 *   검증 플래그가 없어 항상 미검증 취급.
 * - 조직 OIDC(Stage E): registrationId 가 소셜 3종이 아니고 sso_connections 에 OIDC 커넥션이 있으면
 *   구글과 동일한 표준 클레임 구조로 추출한다. provider = `oidc:{registrationId}`.
 */
@Component
class SocialAttributesExtractor(
    private val ssoConnectionService: SsoConnectionService,
) {
    fun extract(
        registrationId: String,
        principal: OAuth2User,
    ): SocialAttributes =
        when (registrationId) {
            SocialProviders.GOOGLE -> extractGoogle(principal)
            SocialProviders.KAKAO -> extractKakao(principal)
            SocialProviders.NAVER -> extractNaver(principal)
            else -> extractOrgOidc(registrationId, principal)
        }

    /**
     * 조직 OIDC 표준 클레임 추출(구글과 동일 구조). userNameAttr(기본 sub)로 providerUserId 를 잡고
     * email/email_verified/name 표준 클레임을 읽는다. 도메인 일치 강제는 FederatedLoginSuccessHandler 가 담당.
     */
    private fun extractOrgOidc(
        registrationId: String,
        principal: OAuth2User,
    ): SocialAttributes {
        val connection =
            ssoConnectionService.findByRegistrationId(registrationId)
                ?: throw IllegalArgumentException("Unsupported provider: $registrationId")
        val attributes = principal.attributes
        val userNameAttr = connection.oidcUserNameAttr?.takeIf { it.isNotBlank() } ?: "sub"
        return SocialAttributes(
            provider = "oidc:$registrationId",
            providerUserId =
                requireNotNull(attributes[userNameAttr]?.toString()) {
                    "org oidc userinfo has no $userNameAttr"
                },
            email = normalizeEmail(attributes["email"]),
            emailVerifiedByProvider = booleanOf(attributes["email_verified"]),
            displayName = attributes["name"]?.toString()?.takeIf { it.isNotBlank() },
            connectionId = connection.id,
        )
    }

    private fun extractGoogle(principal: OAuth2User): SocialAttributes {
        val attributes = principal.attributes
        return SocialAttributes(
            provider = SocialProviders.GOOGLE,
            providerUserId = requireNotNull(attributes["sub"]?.toString()) { "google userinfo has no sub" },
            email = normalizeEmail(attributes["email"]),
            emailVerifiedByProvider = booleanOf(attributes["email_verified"]),
            displayName = attributes["name"]?.toString()?.takeIf { it.isNotBlank() },
        )
    }

    private fun extractKakao(principal: OAuth2User): SocialAttributes {
        val attributes = principal.attributes
        val account = attributes["kakao_account"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val profile = account["profile"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val email = normalizeEmail(account["email"])
        // 검증된 이메일 = is_email_valid 와 is_email_verified 가 둘 다 true.
        val verified =
            email != null &&
                booleanOf(account["is_email_valid"]) &&
                booleanOf(account["is_email_verified"])
        return SocialAttributes(
            provider = SocialProviders.KAKAO,
            providerUserId = requireNotNull(attributes["id"]?.toString()) { "kakao userinfo has no id" },
            email = email,
            emailVerifiedByProvider = verified,
            displayName = profile["nickname"]?.toString()?.takeIf { it.isNotBlank() },
        )
    }

    private fun extractNaver(principal: OAuth2User): SocialAttributes {
        val attributes = principal.attributes
        return SocialAttributes(
            provider = SocialProviders.NAVER,
            providerUserId = requireNotNull(attributes["id"]?.toString()) { "naver userinfo has no id" },
            email = normalizeEmail(attributes["email"]),
            // 네이버는 이메일 검증 플래그를 제공하지 않는다 → 항상 미검증 취급.
            emailVerifiedByProvider = false,
            displayName = (attributes["name"] ?: attributes["nickname"])?.toString()?.takeIf { it.isNotBlank() },
        )
    }

    private fun booleanOf(value: Any?): Boolean =
        when (value) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            else -> false
        }

    /**
     * 공급자 이메일 클레임 정규화 — trim + 소문자. 카카오 등 일부 공급자는 대소문자 정규화를
     * 보장하지 않아, 소문자화 없이 로컬 계정과 매칭하면 같은 사람의 계정이 둘로 갈라진다.
     */
    private fun normalizeEmail(value: Any?): String? =
        value
            ?.toString()
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
}
