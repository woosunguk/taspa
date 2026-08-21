-- 조직 1단계: 계층형 부서(조직도) + 사업장(사이트) + 멤버 구조적 배정.
--   departments: 자기참조 트리(parent_id). parent 삭제 시 서브트리 CASCADE(수동 삭제는 서비스가 자식 있으면 차단).
--     형제(같은 부모) 이름 유일 — 루트(parent_id IS NULL)와 자식을 부분 유니크 인덱스로 각각 강제한다.
--   sites: org 스코프 사업장(구내식당). org 내 이름 유일. timezone 은 org 와 동일하게 IANA/Postgres 존.
--   org_memberships.department_id/site_id: 구조적 배정(자유 텍스트 department 컬럼은 레거시 라벨로 유지).
--     배정 대상 삭제 시 SET NULL — 멤버는 남고 배정만 해제된다.
CREATE TABLE departments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    parent_id UUID REFERENCES departments(id) ON DELETE CASCADE,   -- 서브트리 삭제(수동 삭제는 서비스가 자식 있으면 차단)
    name VARCHAR(120) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_departments_org ON departments(org_id);
CREATE INDEX idx_departments_parent ON departments(parent_id);
CREATE UNIQUE INDEX uq_dept_sibling ON departments(org_id, parent_id, name) WHERE parent_id IS NOT NULL;
CREATE UNIQUE INDEX uq_dept_root ON departments(org_id, name) WHERE parent_id IS NULL;

CREATE TABLE sites (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    address VARCHAR(255),
    timezone VARCHAR(64) NOT NULL DEFAULT 'UTC',
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_sites_org ON sites(org_id);
CREATE UNIQUE INDEX uq_site_name ON sites(org_id, name);

ALTER TABLE org_memberships ADD COLUMN department_id UUID REFERENCES departments(id) ON DELETE SET NULL;
ALTER TABLE org_memberships ADD COLUMN site_id UUID REFERENCES sites(id) ON DELETE SET NULL;
