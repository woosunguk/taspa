-- 가맹 예측 신호 설정(저장형).
--
-- 처음에는 신호를 요청 단위 실험으로만 두었으나("저장하면 누가 언제 켰는지 모른 채 내일의 발주가
-- 달라진다"), 운영 요구는 반대였다 — 매장이 찾아낸 최적 조합이 새로고침마다 사라지면 실험의 결론을
-- 쓸 수 없다. 저장하되 원래 우려는 **감사 로그**(MERCHANT_FORECAST_SETTINGS_UPDATED)가 답한다:
-- 누가 언제 무엇을 켰는지 기록이 남는다. 쿼리 파라미터는 여전히 저장값을 **요청 한 번만** 덮는다(실험).
CREATE TABLE merchant_forecast_settings (
    merchant_id       UUID PRIMARY KEY REFERENCES merchants(id) ON DELETE CASCADE,
    headcount_adjust  BOOLEAN   NOT NULL DEFAULT true,
    absence_aware     BOOLEAN   NOT NULL DEFAULT true,
    holiday_aware     BOOLEAN   NOT NULL DEFAULT true,
    event_aware       BOOLEAN   NOT NULL DEFAULT false,
    menu_aware        BOOLEAN   NOT NULL DEFAULT false,
    nowcast           BOOLEAN   NOT NULL DEFAULT true,
    method_selection  BOOLEAN   NOT NULL DEFAULT false,
    updated_at        TIMESTAMP NOT NULL DEFAULT now()
);
