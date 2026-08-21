-- 부분 환불 — 그전까지는 **전액 취소(void)만** 가능했다.
--
-- 현장에서 실제로 일어나는 일: 15,000원을 승인했는데 주문 하나가 잘못 나가 3,000원을 돌려줘야 한다.
-- 지금은 방법이 전액 취소 후 12,000원으로 재승인뿐인데, 그러려면 손님이 QR 을 다시 발급받아야 하고
-- (토큰은 단일 사용) 거래 하나가 장부에 둘로 남는다.
--
-- ★설계의 핵심은 **어느 주머니로 돌려주는가**다. 조직이 12,000(1식 한도), 직원이 3,000을 냈을 때
--   3,000을 환불하면 직원이 받아야 한다 — 남은 12,000원짜리 식사는 조직 한도 안에 온전히 들어가기
--   때문이다. 그래서 환불 후 금액으로 **분담을 다시 계산하고 차액을 각자에게 돌린다**. "개인부담 먼저"
--   같은 규칙을 따로 두지 않아도 그 결과가 자연히 나오고, 승인 로직과 같은 식을 쓰므로 어긋날 수 없다.
--
-- meal_transactions.amount_minor/self_paid_minor 는 **환불 후 현재값**으로 갱신한다. 그래야 기존 집계
-- (청구서·월 한도·자격 조회의 `amount - self_paid`)가 **쿼리를 한 줄도 안 고치고** 그대로 맞는다.
-- 원래 금액은 `amount_minor + refunded_minor` 로 복원된다.
ALTER TABLE meal_transactions ADD COLUMN refunded_minor BIGINT NOT NULL DEFAULT 0;

-- 환불 원장. 부분 환불은 여러 번 일어날 수 있어 거래 컬럼만으로는 "언제 얼마를 왜" 를 남길 수 없다.
-- (#83 이중부기 원장이 들어오면 이 테이블이 그 금액축의 출발점이 된다.)
CREATE TABLE meal_refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES meal_transactions(id) ON DELETE CASCADE,
    merchant_id UUID NOT NULL,

    -- POS 가 생성해 재시도에서 재사용하는 멱등키. 통신 단절 후 재시도가 **이중 환불**이 되면
    -- 그건 그대로 회사·직원의 손실이다(승인의 pos_txn_id 와 같은 이유·같은 형태).
    pos_refund_id VARCHAR(64) NOT NULL,

    amount_minor BIGINT NOT NULL,
    org_refunded_minor BIGINT NOT NULL,
    self_refunded_minor BIGINT NOT NULL,
    reason VARCHAR(200),
    refunded_at TIMESTAMP NOT NULL DEFAULT now(),

    CONSTRAINT uq_meal_refund_pos UNIQUE (merchant_id, pos_refund_id),
    CONSTRAINT ck_meal_refund_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_meal_refund_split CHECK (
        org_refunded_minor >= 0 AND self_refunded_minor >= 0
        AND org_refunded_minor + self_refunded_minor = amount_minor
    )
);

CREATE INDEX idx_meal_refund_transaction ON meal_refunds (transaction_id);
