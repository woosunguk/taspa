-- 부서 서브트리 위임 — "부서장이 자기 조직만 관리한다".
--
-- Google Workspace 의 조직 단위(OU) 위임에 대응한다. 지금까지 조직 관리 권한은 ORG_ADMIN 하나뿐이라
-- 전부이거나 전무였다: 개발본부장에게 자기 본부 인원 관리를 맡기려면 **전사 관리자**를 줘야 했고,
-- 그건 다른 본부의 인사·식대 예산까지 여는 것이었다.
--
-- 정책 문서를 직접 저장하지 않고 전용 테이블을 두는 이유:
--  ① 콘솔이 "누가 어느 부서를 위임받았나"를 물으려면 JSON 을 파싱해야 한다(인덱스도 못 탄다).
--  ② ORG_ADMIN 승격과의 **상호배제**를 트랜잭션 안에서 강제하려면 행이 필요하다.
-- 실제 인가 정책은 이 행에서 `LegacyPolicyBridge.departmentDelegate` 가 매 요청 합성한다 —
-- 저장된 정책 문서를 사람이 손으로 고칠 수 있으면 위임 경계가 정책 편집으로 넓혀질 수 있기 때문이다.
CREATE TABLE org_department_delegations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,

    -- 부서가 사라지면 위임도 사라진다. 남겨 두면 존재하지 않는 서브트리에 대한 권한이 되는데,
    -- 그 부서 id 가 재사용될 일은 없어도 "권한이 있는데 대상이 없는" 상태를 만들 이유가 없다.
    department_id UUID NOT NULL REFERENCES departments(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    granted_by UUID,                       -- users.id, FK 없음(부여자 계정이 삭제돼도 기록 보존)
    created_at TIMESTAMP NOT NULL DEFAULT now(),

    -- 한 사람은 한 조직에서 **하나의 서브트리**만 위임받는다. 여러 개를 허용하면 "어느 경로로 통과했나"가
    -- 감사에서 모호해지고, 두 서브트리의 합집합이 사실상 전사가 되는 경우를 사람이 알아채기 어렵다.
    CONSTRAINT uq_odd_org_user UNIQUE (org_id, user_id)
);

CREATE INDEX idx_odd_org ON org_department_delegations (org_id);
CREATE INDEX idx_odd_user ON org_department_delegations (user_id);
