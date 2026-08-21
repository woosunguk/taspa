package com.taspa.server.enterprise

import com.taspa.server.federation.SocialAttributes
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal
import org.springframework.stereotype.Component

/**
 * SAML 어서션 principal → SocialAttributes 정규화(소셜 SocialAttributesExtractor 미러링).
 *
 * - provider = `saml:{registrationId}` (federated_identities.provider), providerUserId = NameID.
 * - email 은 커넥션의 samlEmailAttr(기본 "email") 어트리뷰트, 없으면 NameID 가 이메일 형태면 그것.
 * - emailVerifiedByProvider = true: 조직 IdP 가 사용자 신원을 보증한다(소셜 구글과 동일 취급).
 *   단 도메인 일치 강제(정책 5)가 성공 핸들러에서 별도로 소유를 강제한다.
 */
@Component
class Saml2AttributesExtractor(
    private val ssoConnectionService: SsoConnectionService,
) {
    fun extract(
        registrationId: String,
        principal: Saml2AuthenticatedPrincipal,
    ): SocialAttributes {
        val connection = ssoConnectionService.findByRegistrationId(registrationId)
        val emailAttr = connection?.samlEmailAttr?.takeIf { it.isNotBlank() } ?: "email"
        val nameAttr = connection?.samlNameAttr?.takeIf { it.isNotBlank() } ?: "name"

        val nameId = principal.name
        val emailFromAttr = firstString(principal, emailAttr)
        val email = emailFromAttr ?: nameId.takeIf { it.contains('@') }

        return SocialAttributes(
            provider = "saml:$registrationId",
            providerUserId = nameId,
            email = normalizeEmail(email),
            emailVerifiedByProvider = true,
            displayName = firstString(principal, nameAttr),
            connectionId = connection?.id,
        )
    }

    private fun firstString(
        principal: Saml2AuthenticatedPrincipal,
        attribute: String,
    ): String? =
        principal
            .getFirstAttribute<Any?>(attribute)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun normalizeEmail(value: String?): String? = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}
