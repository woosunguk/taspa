-- 정산 집계(청구서) — meal_transactions(APPROVED)를 조직×월로 집계한 후불 청구 draft/finalize.
--   invoices: (org_id, period) 당 1장. DRAFT 는 재생성(라인 full-replace) 가능, FINALIZED 는 불변.
--   invoice_lines: 사용자별 분해 라인. user_email·department_name 은 생성 시점 스냅샷(청구서 불변) —
--     department_id 는 의도적으로 FK 없음(부서 삭제·개편에도 확정 청구서 라인은 보존돼야 한다).
--   금액은 조직부담(amount_minor − self_paid_minor)만 — 개인부담(self_paid)은 청구 제외(설계 §4.3).
--   수수료·부가세·세금계산서·실 수납은 범위 밖(후속 — 실 자금이동 없음).
CREATE TABLE invoices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    period VARCHAR(7) NOT NULL,                  -- 'YYYY-MM' (org 타임존 월)
    period_start TIMESTAMP NOT NULL,             -- 집계 창 [start, end) 스냅샷(생성 시점 org 타임존 앵커).
    period_end TIMESTAMP NOT NULL,               --   타임존 변경 후에도 finalize 재검증·인접 월 정합의 기준.
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', -- DRAFT | FINALIZED
    subtotal_minor BIGINT NOT NULL DEFAULT 0,    -- 조직부담 합
    txn_count INT NOT NULL DEFAULT 0,
    generated_at TIMESTAMP NOT NULL DEFAULT now(),
    finalized_at TIMESTAMP,
    UNIQUE (org_id, period)                      -- 동시 generate 는 한쪽이 UNIQUE 위반 → 409
);

CREATE TABLE invoice_lines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id UUID NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    user_email VARCHAR(100) NOT NULL,            -- 스냅샷(청구서 불변)
    department_id UUID,                          -- FK 없음(이력 불변 — 부서 삭제돼도 라인 보존)
    department_name VARCHAR(120),                -- 스냅샷
    txn_count INT NOT NULL,
    amount_minor BIGINT NOT NULL                 -- 조직부담 합
);
CREATE INDEX idx_invoice_lines_invoice ON invoice_lines(invoice_id);
