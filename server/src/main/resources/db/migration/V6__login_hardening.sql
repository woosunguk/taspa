-- Stage B(로그인 강화) 테이블 일괄: 신뢰 기기 / 로그인 이벤트(알림 판정) / 매직 링크 토큰.

-- 신뢰 기기: MFA 30일 스킵. 토큰 원본은 저장하지 않고 SHA-256 해시만 저장한다(쿠키 taspa_td).
CREATE TABLE trusted_devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    ua_label VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    last_used_at TIMESTAMP,
    expires_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_trusted_devices_user ON trusted_devices(user_id);

-- 로그인 이벤트: 완전 인증마다 1행. 신규 기기(새 로그인 알림) 판정과 감사 이력에 쓰인다.
CREATE TABLE login_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ip VARCHAR(64),
    ua_label VARCHAR(255),
    method VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_login_events_user_time ON login_events(user_id, created_at);

-- 매직 링크 토큰: 단일 사용(used_at), 15분 만료. 원본은 메일 링크로만 전달되고 해시만 저장한다.
CREATE TABLE magic_link_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP
);

CREATE INDEX idx_magic_link_tokens_user ON magic_link_tokens(user_id);
