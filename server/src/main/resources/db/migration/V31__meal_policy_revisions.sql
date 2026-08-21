-- 식대 정책 변경 이력(append-only).
--
-- org_membership_history(V22)가 **인원 축**의 정답데이터인 것처럼 이것이 **금액 축**의 정답데이터다 —
-- 청구서 이의제기("이 달 한도가 왜 이렇죠?")·컴플라이언스·과거 재현의 근거가 여기 하나뿐이다.
-- audit_events 로 대신하지 않는 이유: 감사 로그는 보존 정책에 따라 잘려 나가는 운영 기록이고,
-- 이쪽은 금액 계산의 근거라 청구서만큼 오래 남아야 한다.
--
-- ★scope_id 에 FK 를 걸지 않는다(V22 org_membership_history 선례와 같은 이유): 삭제된 부서의 과거
--   정책도 남아야 그 시절 청구서를 재현할 수 있다. 대신 이름 스냅샷(scope_label)을 함께 남긴다.
--   반대로 live 재정의 테이블(후속 단계)에는 FK+CASCADE 를 건다 — 죽은 노드의 재정의가 계속 돈을
--   쓰는 것은 막아야 하기 때문이다. 이력은 불변, live 는 정합 — 두 테이블의 역할이 다르다.
CREATE TABLE meal_policy_revisions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    scope_type VARCHAR(16) NOT NULL,        -- ORG | DEPARTMENT | SITE (이 단계는 ORG 만 기록한다)
    scope_id UUID,                          -- ORG 이면 NULL
    scope_label VARCHAR(120),               -- 부서/사업장 이름 스냅샷(삭제 후 재현용)
    change_type VARCHAR(24) NOT NULL,       -- CREATED | UPDATED | REMOVED
    document TEXT NOT NULL,                 -- 변경 **후** 전체 스냅샷 JSON(REMOVED 는 직전 상태)
    -- false = 플랫폼 관리자(비멤버) 행위. 조직 입장에서 "우리가 안 바꿨는데 한도가 달라졌다"를
    -- 사후에 가려낼 수 있어야 하므로 행위자 신분을 값으로 남긴다.
    actor_is_org_member BOOLEAN NOT NULL,
    recorded_at TIMESTAMP NOT NULL DEFAULT now(),
    recorded_by UUID                        -- users.id, FK 없음(행위자 계정이 삭제돼도 이력은 보존)
);

-- ★일반 CREATE INDEX 를 쓴다. V30 이후의 CONCURRENTLY 규약은 *이미 쓰기가 흐르는 기존 테이블*에
--   인덱스를 얹을 때의 것인데, 여기는 같은 마이그레이션에서 만든 빈 테이블이라 블록할 INSERT 자체가
--   없다. 게다가 CONCURRENTLY 를 쓰면 사이드카 executeInTransaction=false 가 스크립트 전체에 걸려
--   CREATE TABLE 까지 트랜잭션 밖으로 나간다. V21(신규 테이블 + 일반 인덱스) 선례와 같다.
CREATE INDEX idx_meal_policy_revision_org_time ON meal_policy_revisions (org_id, recorded_at DESC);
CREATE INDEX idx_meal_policy_revision_scope ON meal_policy_revisions (org_id, scope_type, scope_id, recorded_at DESC);
