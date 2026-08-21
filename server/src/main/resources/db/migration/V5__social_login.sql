-- 소셜 로그인(연합 신원): 소셜 전용 계정은 비밀번호가 없으므로 NOT NULL 을 해제한다.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE federated_identities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    email_at_link VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_user_id)
);

CREATE INDEX idx_federated_identities_user ON federated_identities(user_id);
