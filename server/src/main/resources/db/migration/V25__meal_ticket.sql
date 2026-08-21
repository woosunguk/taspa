-- 식권 QR 폐쇄루프(L1 MVP) — 실 자금이동 없음(장부 기록만). docs/design/meal-platform-system-design.md §0·§4·§6.
--   merchants: 가맹(식당·편의점·카페). site_id 는 구내식당 운영 사업장(선택) — 소비 이벤트 site 귀속의 근원.
--   meal_policies: org 단위 한도/끼니창 정책(행 없으면 코드 기본값). 시간창은 org 타임존 앵커로 판정한다.
--   meal_qr_tokens: QR 불투명 핸들(128bit+ 랜덤 → SHA-256 해시만 저장, 60초 TTL, 단일사용 used_at).
--     서명 JWS 토큰은 오프라인 검증 단계(L2+)용 — 이 단계에서는 서버 왕복 검증만 한다.
--   meal_transactions: 승인 장부(폐쇄루프 기록). auth_id 는 소비 이벤트 external_id 로 재사용된다(seam 연결).
--     (merchant_id, pos_txn_id) UNIQUE 는 POS 재전송 멱등키.
CREATE TABLE merchants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(200) NOT NULL,
    category VARCHAR(24) NOT NULL DEFAULT 'RESTAURANT',    -- RESTAURANT | CONVENIENCE | CAFE
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',          -- PENDING | ACTIVE | SUSPENDED
    site_id UUID REFERENCES sites(id) ON DELETE SET NULL,  -- 구내식당 운영 사업장(선택)
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE meal_policies (
    org_id UUID PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    per_meal_limit_minor BIGINT NOT NULL DEFAULT 12000,
    daily_meal_count INT NOT NULL DEFAULT 1,
    monthly_cap_minor BIGINT NOT NULL DEFAULT 200000,
    breakfast_start TIME NOT NULL DEFAULT '06:00', breakfast_end TIME NOT NULL DEFAULT '10:30',
    lunch_start TIME NOT NULL DEFAULT '10:30', lunch_end TIME NOT NULL DEFAULT '15:00',
    dinner_start TIME NOT NULL DEFAULT '15:00', dinner_end TIME NOT NULL DEFAULT '22:00',
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE meal_qr_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_meal_qr_user_time ON meal_qr_tokens(user_id, created_at);

CREATE TABLE meal_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auth_id VARCHAR(64) NOT NULL UNIQUE,                   -- 소비 external_id
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    merchant_id UUID NOT NULL REFERENCES merchants(id),
    amount_minor BIGINT NOT NULL,
    self_paid_minor BIGINT NOT NULL DEFAULT 0,             -- 한도 초과 개인부담
    meal_window VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'APPROVED',        -- APPROVED | VOIDED
    pos_txn_id VARCHAR(128) NOT NULL,
    approved_at TIMESTAMP NOT NULL DEFAULT now(),
    voided_at TIMESTAMP,
    UNIQUE (merchant_id, pos_txn_id)                       -- POS 멱등키
);
CREATE INDEX idx_meal_tx_org_time ON meal_transactions(org_id, approved_at);
CREATE INDEX idx_meal_tx_user_time ON meal_transactions(user_id, approved_at);

-- 소비 이벤트 site 귀속(선택) — redemption→consumption 적재 시 merchant.site_id 를 전달한다(사이트 롤업 예측용).
ALTER TABLE consumption_events ADD COLUMN site_id UUID REFERENCES sites(id) ON DELETE SET NULL;
CREATE INDEX idx_consumption_site ON consumption_events(site_id) WHERE site_id IS NOT NULL;
