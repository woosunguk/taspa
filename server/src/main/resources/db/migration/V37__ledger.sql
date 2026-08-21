-- 이중부기 원장 — **append-only 금액 사실의 단일 출처**.
--
-- ★왜 지금 필요해졌는가: V36(부분 환불)이 `meal_transactions.amount_minor` 를 **변경 가능**하게 만들었다.
--   집계 쿼리를 한 줄도 안 고치려는 선택이었고 그 목적은 달성했지만, 대가로 거래 테이블이
--   "지금 얼마인가"만 말하고 "그때 얼마였나"를 못 말하게 됐다. 6월분을 청구한 뒤 7월에 환불하면
--   6월 거래 행의 금액이 소급해서 바뀐다 — 확정된 청구서 스냅샷이 아니면 6월을 재현할 방법이 없다.
--   원장은 **사건을 지우지 않고 반대 분개를 덧붙이므로** 어느 시점이든 절단해서 재구성할 수 있다.
--
-- 플랫폼은 통과 지점이다: 조직이 우리에게 내고(미수금), 우리가 가맹에 준다(미지급금). 직원 개인부담은
-- 계산대에서 직접 오가므로 **우리 돈이 아니다** — 분개하지 않고 사건 메타로만 남긴다(그걸 분개에 넣으면
-- 대차가 맞지 않거나, 맞추려고 가공 계정을 만들게 되는데 둘 다 원장을 거짓말하게 만든다).
CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,

    -- REDEEM | VOID | REFUND. 사건의 종류이지 상태가 아니다(원장은 상태를 갖지 않는다).
    entry_type VARCHAR(24) NOT NULL,

    -- 원인 거래. FK 를 걸지 않는다 — 거래가 지워져도 그때 돈이 오간 사실은 남아야 한다
    -- (meal_policy_revisions 가 scope_id 에 FK 를 걸지 않는 것과 같은 이유).
    transaction_id UUID,
    refund_id UUID,
    merchant_id UUID,
    user_id UUID,

    /* 직원 개인부담 — 분개 대상이 아닌 메모. 정산 대사에서 "장부와 왜 다른가"를 설명한다. */
    self_paid_minor BIGINT NOT NULL DEFAULT 0,

    -- 사건이 **일어난** 시각(거래 승인 시각). created_at 과 다를 수 있다(백필·지연 기록).
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),

    -- 같은 사건을 두 번 기록하지 않는다. 재시도·백필이 겹쳐도 원장이 부풀지 않는다.
    --
    -- ★`NULLS NOT DISTINCT` 가 필수다. PostgreSQL 기본값은 UNIQUE 에서 NULL 을 서로 **다른 값**으로
    --   보므로, REDEEM/VOID 행처럼 refund_id 가 NULL 인 조합은 (REDEEM, tx1, NULL) 이 몇 개든 통과한다 —
    --   즉 중복 방지가 통째로 무력화된다. 원장이 부풀면 조직에 청구되는 금액이 부풀고, 그건 조용히
    --   두 배 청구된다. (PG 15+ 문법)
    CONSTRAINT uq_ledger_entry_source UNIQUE NULLS NOT DISTINCT (entry_type, transaction_id, refund_id)
);

-- 분개 한 줄. 한 사건의 부호 합은 항상 0 이어야 한다(대차평형) — 그 불변식은 애플리케이션이 강제하고
-- 대사(reconciliation)가 매번 재확인한다. DB CHECK 로는 행 간 합계를 볼 수 없다.
CREATE TABLE ledger_postings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_id UUID NOT NULL REFERENCES ledger_entries(id) ON DELETE CASCADE,
    org_id UUID NOT NULL,

    -- ORG_RECEIVABLE(조직이 우리에게 낼 돈, 차변 +) | MERCHANT_PAYABLE(우리가 가맹에 줄 돈, 대변 −)
    account VARCHAR(32) NOT NULL,

    -- **부호 있는 금액**. 차변 +, 대변 −. 취소·환불은 반대 부호의 새 줄이지 기존 줄의 수정이 아니다.
    amount_minor BIGINT NOT NULL,

    merchant_id UUID,
    user_id UUID,
    occurred_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_ledger_entry_org_time ON ledger_entries (org_id, occurred_at);
CREATE INDEX idx_ledger_posting_org_account_time ON ledger_postings (org_id, account, occurred_at);
CREATE INDEX idx_ledger_posting_entry ON ledger_postings (entry_id);

-- ── 기존 거래 백필 ─────────────────────────────────────────────────────
--
-- 백필하지 않으면 원장이 도입 시점부터만 존재해 대사가 "과거 전체 불일치"를 보고한다. 그러면 아무도
-- 그 보고를 안 보게 되고(경보 피로), 진짜 불일치가 묻힌다. 지금 시점의 거래 상태로 채운다.
--
-- ★현재 상태만 채우고 **환불 이력은 재생하지 않는다.** V36 이전 데이터에는 환불이 존재하지 않고
--   (기능이 없었다), V36 이후 환불은 애플리케이션이 실시간으로 분개한다. 백필이 하는 일은
--   "이 거래의 현재 조직부담"을 REDEEM 한 줄로 세우는 것뿐이다 — 그 이상을 지어내면 원장이
--   실제로 일어나지 않은 사건을 갖게 된다.
INSERT INTO ledger_entries (org_id, entry_type, transaction_id, merchant_id, user_id, self_paid_minor, occurred_at)
SELECT org_id, 'REDEEM', id, merchant_id, user_id, self_paid_minor, approved_at
  FROM meal_transactions
 WHERE status = 'APPROVED';

INSERT INTO ledger_postings (entry_id, org_id, account, amount_minor, merchant_id, user_id, occurred_at)
SELECT e.id, e.org_id, 'ORG_RECEIVABLE', t.amount_minor - t.self_paid_minor, e.merchant_id, e.user_id, e.occurred_at
  FROM ledger_entries e JOIN meal_transactions t ON t.id = e.transaction_id
 WHERE e.entry_type = 'REDEEM';

INSERT INTO ledger_postings (entry_id, org_id, account, amount_minor, merchant_id, user_id, occurred_at)
SELECT e.id, e.org_id, 'MERCHANT_PAYABLE', -(t.amount_minor - t.self_paid_minor), e.merchant_id, e.user_id, e.occurred_at
  FROM ledger_entries e JOIN meal_transactions t ON t.id = e.transaction_id
 WHERE e.entry_type = 'REDEEM';
