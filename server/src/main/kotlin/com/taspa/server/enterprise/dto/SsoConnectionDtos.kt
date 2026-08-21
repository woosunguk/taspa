package com.taspa.server.enterprise.dto

import java.util.UUID

/** 도메인 매핑 표시. */
data class SsoDomainView(
    val domain: String,
    val verified: Boolean,
)

/**
 * 관리 콘솔 표시용 커넥션 뷰. OIDC client secret 원문은 절대 노출하지 않는다(hasOidcSecret 만).
 * SP 값(spEntityId/spAcsUrl/spMetadataUrl/oidcRedirectUri)은 상대 IdP 에 등록하도록 관리자에게 보여준다.
 */
data class SsoConnectionView(
    val id: UUID,
    val registrationId: String,
    val displayName: String,
    val protocol: String,
    // 연결된 조직(JIT 멤버십 활성화용). null 이면 어떤 org 에도 결속되지 않은 상태(관리 콘솔에서 연결).
    val orgId: UUID?,
    val enabled: Boolean,
    val enforced: Boolean,
    val trustIdpMfa: Boolean,
    val domains: List<SsoDomainView>,
    // OIDC
    val oidcIssuer: String?,
    val oidcAuthorizationUri: String?,
    val oidcTokenUri: String?,
    val oidcJwksUri: String?,
    val oidcUserInfoUri: String?,
    val oidcUserNameAttr: String?,
    val oidcClientId: String?,
    val oidcScopes: String?,
    val hasOidcSecret: Boolean,
    // SAML
    val samlIdpEntityId: String?,
    val samlSsoUrl: String?,
    val samlVerificationCert: String?,
    val samlWantAuthnSigned: Boolean,
    val samlEmailAttr: String?,
    val samlNameAttr: String?,
    // SP 값(관리자에게 표시 — 상대 IdP 등록용)
    val spEntityId: String,
    val spAcsUrl: String,
    val spMetadataUrl: String,
    val oidcRedirectUri: String,
)

/**
 * 커넥션 생성/수정 요청. update 시 registrationId·protocol 은 무시된다(변경 불가).
 * oidcClientSecret 은 값이 있을 때만 갱신(update 에서 빈 값은 기존 secret 유지).
 */
data class SsoConnectionRequest(
    val registrationId: String = "",
    val displayName: String = "",
    val protocol: String = "",
    val enabled: Boolean = true,
    val enforced: Boolean = true,
    val trustIdpMfa: Boolean = false,
    val domains: List<String> = emptyList(),
    // OIDC
    val oidcIssuer: String? = null,
    val oidcAuthorizationUri: String? = null,
    val oidcTokenUri: String? = null,
    val oidcJwksUri: String? = null,
    val oidcUserInfoUri: String? = null,
    val oidcUserNameAttr: String? = null,
    val oidcClientId: String? = null,
    val oidcClientSecret: String? = null,
    val oidcScopes: String? = null,
    // SAML
    val samlIdpEntityId: String? = null,
    val samlSsoUrl: String? = null,
    val samlVerificationCert: String? = null,
    val samlWantAuthnSigned: Boolean? = false,
    val samlEmailAttr: String? = null,
    val samlNameAttr: String? = null,
)

/** 도메인 verified 토글 요청. */
data class SsoDomainVerifyRequest(
    val domain: String = "",
    val verified: Boolean = false,
)
