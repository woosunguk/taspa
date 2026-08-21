package com.taspa.server.domain.sso

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * 조직(기업) IdP 커넥션. 하나의 조직을 하나의 프로토콜(OIDC | SAML)로 연결한다.
 *
 * registrationId 는 인증 경로에 그대로 쓰이는 안정 식별자다:
 *  - OIDC: /oauth2/authorization/{registrationId}, /login/oauth2/code/{registrationId}
 *  - SAML: /saml2/authenticate/{registrationId}, /login/saml2/sso/{registrationId}
 *
 * OIDC client secret 은 oidcClientSecretEncrypted(AES-GCM) 로만 보관한다.
 */
@Entity
@Table(name = "sso_connections")
class SsoConnection(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    val id: UUID? = null,
    @Column(name = "registration_id", nullable = false, unique = true, length = 64)
    var registrationId: String,
    @Column(name = "display_name", nullable = false, length = 100)
    var displayName: String,
    @Column(name = "protocol", nullable = false, length = 8)
    var protocol: String,
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,
    /** SSO 강제: 도메인이 이 커넥션과 매칭되면 로컬 로그인보다 먼저 IdP 로 단락 리다이렉트한다. */
    @Column(name = "enforced", nullable = false)
    var enforced: Boolean = true,
    /** true 면 조직 로그인 성공 후 로컬 MFA 게이트를 건너뛴다(외부 IdP MFA 신뢰). 기본 false. */
    @Column(name = "trust_idp_mfa", nullable = false)
    var trustIdpMfa: Boolean = false,
    /**
     * 이 커넥션이 매핑된 조직(Phase 0-A). NULL 이면 JIT 멤버십을 만들지 않는다 —
     * org_id 가 있을 때만 로그인 성공 시 (user, org, MEMBER) 멤버십을 upsert 한다(잘못된 조직 자동가입 금지).
     */
    @Column(name = "org_id")
    var orgId: UUID? = null,
    // ---- 조직 OIDC ----
    @Column(name = "oidc_issuer", length = 512)
    var oidcIssuer: String? = null,
    @Column(name = "oidc_authorization_uri", length = 512)
    var oidcAuthorizationUri: String? = null,
    @Column(name = "oidc_token_uri", length = 512)
    var oidcTokenUri: String? = null,
    @Column(name = "oidc_jwks_uri", length = 512)
    var oidcJwksUri: String? = null,
    @Column(name = "oidc_user_info_uri", length = 512)
    var oidcUserInfoUri: String? = null,
    @Column(name = "oidc_user_name_attr", length = 64)
    var oidcUserNameAttr: String? = null,
    @Column(name = "oidc_client_id", length = 255)
    var oidcClientId: String? = null,
    @Column(name = "oidc_client_secret_encrypted", length = 1024)
    var oidcClientSecretEncrypted: String? = null,
    @Column(name = "oidc_scopes", length = 255)
    var oidcScopes: String? = null,
    // ---- SAML ----
    @Column(name = "saml_idp_entity_id", length = 512)
    var samlIdpEntityId: String? = null,
    @Column(name = "saml_sso_url", length = 512)
    var samlSsoUrl: String? = null,
    @Column(name = "saml_verification_cert", columnDefinition = "text")
    var samlVerificationCert: String? = null,
    @Column(name = "saml_want_authn_signed")
    var samlWantAuthnSigned: Boolean? = false,
    @Column(name = "saml_email_attr", length = 128)
    var samlEmailAttr: String? = "email",
    @Column(name = "saml_name_attr", length = 128)
    var samlNameAttr: String? = "name",
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @PreUpdate
    fun onPreUpdate() {
        updatedAt = Instant.now()
    }

    fun protocolEnum(): SsoProtocol = SsoProtocol.valueOf(protocol)
}
