-- 부서·사업장 단위 식대 정책 재정의.
--
-- 조직 기본값(meal_policies, org 당 1행)을 **필드 단위로** 덮어쓴다. 전체 정책을 복제하지 않고
-- 재정의한 컬럼만 NOT NULL 인 이유: 조직이 1식 한도를 올리면 "개발팀은 점심시간만 다르다"는 재정의가
-- 그 변경을 자동으로 물려받아야 한다. 전체 복제였다면 부서마다 손으로 따라 고쳐야 하고, 빠뜨린
-- 부서가 옛 한도로 남는다(그리고 아무도 모른다).
--
-- ★기존 테이블 ALTER 0. 신규 테이블 하나만 추가한다.
CREATE TABLE meal_policy_overrides (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,

    -- 축마다 별도 nullable FK. 단일 scope_id 다형 참조로는 FK 를 걸 수 없고, FK 가 없으면 삭제된
    -- 부서의 재정의가 살아남아 계속 돈을 쓴다(이력 테이블 V31 이 FK 를 안 거는 것과 정반대 이유 —
    -- 이력은 불변이어야 하고 live 는 정합해야 한다).
    department_id UUID REFERENCES departments(id) ON DELETE CASCADE,
    site_id       UUID REFERENCES sites(id)       ON DELETE CASCADE,

    -- 재정의하지 않는 필드는 NULL = 상위값 물려받음.
    per_meal_limit_minor BIGINT,
    daily_meal_count     INT,
    monthly_cap_minor    BIGINT,
    breakfast_start TIME, breakfast_end TIME,
    lunch_start     TIME, lunch_end     TIME,
    dinner_start    TIME, dinner_end    TIME,

    -- 기간 한정 재정의(연말 회식 기간 한도 상향 등). 컬럼을 처음부터 넣어 두면 그 기능을 켤 때
    -- 스키마 변경이 필요 없다 — 마이그레이션 하나를 아끼려는 게 아니라, 나중에 추가하면 기존 행의
    -- 기본값을 정하는 문제가 생기기 때문이다(NULL=상시 라는 의미를 처음부터 못 박는다).
    effective_from DATE,
    effective_to   DATE,
    reason VARCHAR(200),

    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_by UUID,

    -- 정확히 한 축에만 붙는다. 부서이면서 동시에 사업장인 재정의는 의미가 없고, 둘 다 NULL 이면
    -- 그건 조직 기본값이라 meal_policies 가 있어야 할 자리다.
    CONSTRAINT ck_mpo_one_scope CHECK ((department_id IS NULL) <> (site_id IS NULL)),

    -- 끼니창은 쌍이 원자 단위 — 시작만 재정의하면 상위의 종료와 짝이 맞지 않아 창이 뒤집힌다.
    CONSTRAINT ck_mpo_breakfast CHECK ((breakfast_start IS NULL) = (breakfast_end IS NULL)),
    CONSTRAINT ck_mpo_lunch     CHECK ((lunch_start     IS NULL) = (lunch_end     IS NULL)),
    CONSTRAINT ck_mpo_dinner    CHECK ((dinner_start    IS NULL) = (dinner_end    IS NULL)),

    -- 자정을 넘는 창은 저장 자체를 막는다(받아 두면 그 끼니가 조용히 사라진다 — 서비스 검증과 이중).
    CONSTRAINT ck_mpo_win_order CHECK ((breakfast_start IS NULL OR breakfast_start < breakfast_end)
                                   AND (lunch_start     IS NULL OR lunch_start     < lunch_end)
                                   AND (dinner_start    IS NULL OR dinner_start    < dinner_end)),

    CONSTRAINT ck_mpo_amounts CHECK ((per_meal_limit_minor IS NULL OR per_meal_limit_minor >= 0)
                                 AND (monthly_cap_minor    IS NULL OR monthly_cap_minor    >= 0)
                                 AND (daily_meal_count     IS NULL OR daily_meal_count     >= 1)),

    CONSTRAINT ck_mpo_period CHECK (effective_from IS NULL OR effective_to IS NULL
                                    OR effective_from <= effective_to),

    -- 아무것도 재정의하지 않는 행은 금지. 있어도 해석에 영향이 없어 "설정했는데 안 바뀐다"만 만든다.
    CONSTRAINT ck_mpo_not_empty CHECK (num_nonnulls(per_meal_limit_minor, daily_meal_count,
        monthly_cap_minor, breakfast_start, lunch_start, dinner_start) > 0)
);

-- ★상시 재정의(기간 없음)만 노드당 1행. 기간 한정은 여러 행을 허용해야 "12월 한 달만" 같은 설정이
--   상시 재정의와 공존한다. 부분 유니크가 그 둘을 한 테이블에서 구분하는 장치다.
CREATE UNIQUE INDEX uq_mpo_department_standing ON meal_policy_overrides (org_id, department_id)
    WHERE department_id IS NOT NULL AND effective_from IS NULL AND effective_to IS NULL;
CREATE UNIQUE INDEX uq_mpo_site_standing ON meal_policy_overrides (org_id, site_id)
    WHERE site_id IS NOT NULL AND effective_from IS NULL AND effective_to IS NULL;

-- 해석 경로 조회 인덱스(org 의 전 재정의를 한 번에 읽어 메모리에서 병합한다 — 부서 트리를 타고
-- 올라가며 행마다 질의하면 깊이만큼 왕복이 생기고, 그 왕복이 redeem 의 잠금 구간 안에서 일어난다).
CREATE INDEX idx_mpo_org ON meal_policy_overrides (org_id);

-- 신규 빈 테이블이라 일반 CREATE INDEX(V21·V31 선례). CONCURRENTLY 규약은 이미 쓰기가 흐르는
-- 기존 테이블용이고, 여기서 쓰면 사이드카 executeInTransaction=false 가 CREATE TABLE 까지 끌고 나간다.
