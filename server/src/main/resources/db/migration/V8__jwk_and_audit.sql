-- Stage A: JWK 서명 키 영속화(재시작 생존 + 회전) / 감사 이벤트 DB 영속화.

-- 서명 키: RSAKey.toJSONString()(개인키 포함)을 AES-GCM 으로 암호화해 저장한다.
-- ACTIVE 1개가 서명에 쓰이고, RETIRED 는 유예 기간 동안 JWKS 에 남아 기존 토큰 검증을 지탱한다.
CREATE TABLE jwk_keys (
    kid VARCHAR(64) PRIMARY KEY,
    key_json_encrypted TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,           -- ACTIVE | RETIRED
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    activated_at TIMESTAMP,
    retired_at TIMESTAMP
);

-- 감사 이벤트: 로그 전용이던 AuditEventService 의 영속 저장소. detail 은 JSON 직렬화 문자열.
CREATE TABLE audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type VARCHAR(64) NOT NULL,
    user_id UUID,
    detail TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_events_user_time ON audit_events(user_id, created_at);
CREATE INDEX idx_audit_events_type_time ON audit_events(type, created_at);
