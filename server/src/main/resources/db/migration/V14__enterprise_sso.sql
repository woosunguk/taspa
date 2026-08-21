-- Stage E: 기업 SSO 연동(SAML 2.0 / 조직 OIDC). taspa 가 SP/RP 로서 조직 외부 IdP 로 인증한다.
--
-- sso_connections: 조직별 IdP 연결(프로토콜·엔드포인트·인증서·정책 플래그).
--   registration_id 는 경로에 쓰이는 안정 식별자(/oauth2/authorization/{regId}, /saml2/authenticate/{regId}).
--   민감값(oidc_client_secret)은 AesEncryptionService(mfa 키)로 암호화 저장한다.
--   protocol 은 OIDC | SAML (domain/sso/SsoProtocol).
CREATE TABLE sso_connections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    registration_id VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    protocol VARCHAR(8) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    enforced BOOLEAN NOT NULL DEFAULT true,
    trust_idp_mfa BOOLEAN NOT NULL DEFAULT false,
    -- 조직 OIDC
    oidc_issuer VARCHAR(512),
    oidc_authorization_uri VARCHAR(512),
    oidc_token_uri VARCHAR(512),
    oidc_jwks_uri VARCHAR(512),
    oidc_user_info_uri VARCHAR(512),
    oidc_user_name_attr VARCHAR(64),
    oidc_client_id VARCHAR(255),
    oidc_client_secret_encrypted VARCHAR(1024),
    oidc_scopes VARCHAR(255),
    -- SAML
    saml_idp_entity_id VARCHAR(512),
    saml_sso_url VARCHAR(512),
    saml_verification_cert TEXT,
    saml_want_authn_signed BOOLEAN DEFAULT false,
    saml_email_attr VARCHAR(128) DEFAULT 'email',
    saml_name_attr VARCHAR(128) DEFAULT 'name',
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

-- sso_domains: 이메일 도메인 → 커넥션 매핑(HRD). domain 은 소문자·trim 정규화.
--   verified=true 인 도메인만 로그인 라우팅·도메인 일치 강제에 쓰인다(관리자 수동 검증).
CREATE TABLE sso_domains (
    domain VARCHAR(255) PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES sso_connections(id) ON DELETE CASCADE,
    verified BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_sso_domains_connection ON sso_domains(connection_id);

-- 연합 신원에 커넥션 참조를 추가(멀티 커넥션·표시 확장). provider 값은 saml:{regId} / oidc:{regId}.
-- V5 의 UNIQUE(provider, provider_user_id)는 그대로 유지된다.
ALTER TABLE federated_identities ADD COLUMN connection_id UUID REFERENCES sso_connections(id) ON DELETE SET NULL;
