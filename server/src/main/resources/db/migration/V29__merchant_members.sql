-- 가맹점 사람 신원(가맹 관리자) + 가맹 그레인 집계 앵커.
--
-- 지금까지 가맹점은 **기계 신원만** 있었다(POS = client_credentials + merchant_id 클레임). 결제 승인이
-- 기계 전용인 것은 유지하되(사람 토큰이 meal:Redeem 에 닿지 못하는 불변식), 가맹 관리자가 자기 매장의
-- 식수 로그와 예측을 **로그인해서** 보는 경로를 연다. 조회는 자기 매장 데이터라 테넌시 문제가 없다.
CREATE TABLE merchant_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(24) NOT NULL DEFAULT 'MERCHANT_ADMIN',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | SUSPENDED
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (merchant_id, user_id)
);
CREATE INDEX idx_merchant_members_user ON merchant_members(user_id);
CREATE INDEX idx_merchant_members_merchant ON merchant_members(merchant_id);

-- 가맹 그레인 집계의 날짜 버킷 앵커.
--   조직 집계는 organizations.timezone 을 쓰지만(V18), 가맹점은 여러 조직 손님을 받을 수 있어 조직 타임존을
--   빌려 쓸 수 없다. 사업장에 연결된 가맹점이라도 site.timezone 은 "그 회사의 사업장" 기준이라 의미가 다르다.
--   매장이 영업하는 지역 시간으로 하루 경계를 끊어야 "오늘 몇 인분"이 매장 감각과 일치한다.
ALTER TABLE merchants ADD COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'UTC';
