-- 패스키(WebAuthn) 저장 모델.
-- external_id 는 인증기(authenticator)에 노출되는 사용자 핸들(base64url, 랜덤 32B)로,
-- users.id(UUID)를 외부에 노출하지 않기 위해 별도로 둔다.

CREATE TABLE webauthn_user_entities (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    external_id VARCHAR(256) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE webauthn_credentials (
    credential_id VARCHAR(1024) PRIMARY KEY,
    user_entity_external_id VARCHAR(256) NOT NULL REFERENCES webauthn_user_entities(external_id) ON DELETE CASCADE,
    credential_type VARCHAR(32) NOT NULL,
    public_key_cose BYTEA NOT NULL,
    signature_count BIGINT NOT NULL DEFAULT 0,
    uv_initialized BOOLEAN NOT NULL DEFAULT false,
    transports VARCHAR(255),
    backup_eligible BOOLEAN NOT NULL DEFAULT false,
    backup_state BOOLEAN NOT NULL DEFAULT false,
    attestation_object BYTEA,
    attestation_client_data_json BYTEA,
    label VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP
);

CREATE INDEX idx_webauthn_credentials_user ON webauthn_credentials(user_entity_external_id);
