-- 가맹점 정액 단가 — POS 에서 계산원이 금액을 타이핑하지 않고 **정해진 가격으로 즉시 승인**하게 한다.
--
-- 왜 매장 단위인가: 가격은 **식당이 정하는 것**이다. 조직의 meal_policies.per_meal_limit_minor 는
-- "회사가 얼마까지 지원하는가"(보조 한도)이고 가격이 아니다 — 그 값을 가격으로 쓰면 직원 부담이
-- 구조적으로 항상 0 이 되고, 한도를 올리는 순간 식당 매출이 따라 오르는 이상한 결합이 생긴다.
--
-- NULL 을 허용하는 이유(기본값 없음): 단가가 없는 매장은 **지금까지와 똑같이** 금액을 직접 입력한다.
-- 카페처럼 품목마다 값이 다른 곳이 있고, 0 이나 임의 기본값을 넣으면 그 매장에서 잘못된 금액이
-- 자동 승인된다 — 돈이 오가는 경로에서 "설정 안 함"과 "0원"은 절대 같은 값이 될 수 없다.
ALTER TABLE merchants
    ADD COLUMN default_price_minor BIGINT;

-- 0 이하는 승인 자체가 불가능한 값이라 열에서 막는다(화면·API 검증이 빠져도 DB 가 최후 방어선).
ALTER TABLE merchants
    ADD CONSTRAINT ck_merchant_default_price_positive
        CHECK (default_price_minor IS NULL OR default_price_minor > 0);
