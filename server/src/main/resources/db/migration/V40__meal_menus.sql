-- 식단(메뉴) 마스터.
--
-- 왜 필요한가: `consumption_events.menu_ref`(V17)는 선언만 있고 **채우는 프로덕션 코드가 0곳**이었다.
-- 소비 집계 API 가 `groupBy=menu` 를 지원한다고 광고하는데 값은 항상 null 이라, "설정이 있는데 안 도는"
-- 상태였다(그건 없는 것보다 나쁘다 — 쓰는 사람은 된다고 믿는다). 메뉴를 등록하면 승인 시 실적에 귀속되고
-- 그 축이 처음으로 실제 값을 갖는다.
--
-- 한 끼니에 메뉴가 여러 개일 수 있다(A코너 한식 / B코너 분식). 그래서 (날짜, 끼니) 당 1행이 아니라 N행이다.
CREATE TABLE meal_menus (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id           UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    -- null = 조직 전체 공통 식단. 사업장별 식단이 있으면 그 사업장 행이 우선한다(해석은 서비스가 한다).
    -- 사업장이 사라지면 그 식단도 의미가 없어 함께 지운다(정책 재정의 V32 와 같은 이유 — 죽은 노드가
    -- 계속 발주 근거로 남지 않게).
    site_id          UUID         REFERENCES sites(id) ON DELETE CASCADE,
    menu_date        DATE         NOT NULL,
    meal_window      VARCHAR(16)  NOT NULL,
    name             VARCHAR(200) NOT NULL,
    -- 수요 성격이 다른 메뉴를 구분한다(특식은 참여율이 오르고 채식은 소수다). 예측 신호의 입력.
    category         VARCHAR(32)  NOT NULL DEFAULT 'REGULAR',
    -- 'A코너' 같은 배식 위치(선택). 표시용이며 판정에 쓰지 않는다.
    corner           VARCHAR(60),
    -- 조직이 세운 발주 계획(선택). 예측값과 나란히 보여 "얼마나 달랐나"를 사후에 확인하는 근거다.
    planned_portions INT,
    sort_order       INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_meal_menu_portions CHECK (planned_portions IS NULL OR planned_portions >= 0),
    CONSTRAINT ck_meal_menu_name CHECK (length(btrim(name)) > 0)
);

-- 같은 끼니에 같은 이름을 두 번 등록하지 않는다(중복 등록이 발주량을 두 배로 만든다).
-- ★NULLS NOT DISTINCT 필수 — PostgreSQL 기본값은 UNIQUE 에서 NULL 을 서로 다른 값으로 보므로,
--   site_id 가 null 인 "조직 공통" 식단은 같은 이름으로 **몇 번이든 통과한다**(V37 원장에서 똑같은
--   함정을 겪었다). 조직 공통 식단이 정확히 이 테이블의 기본 사용법이라 그냥 두면 거의 항상 새는 제약이다.
CREATE UNIQUE INDEX uq_meal_menu_slot
    ON meal_menus (org_id, menu_date, meal_window, site_id, name) NULLS NOT DISTINCT;

-- 조회는 항상 org × 날짜 구간이다(식단 화면·예측 셀 조립·승인 시 귀속).
CREATE INDEX idx_meal_menu_org_date ON meal_menus (org_id, menu_date);
