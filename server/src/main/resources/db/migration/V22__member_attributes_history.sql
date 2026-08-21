-- 조직 2단계: 임직원 HR 속성(멤버십 컬럼 가산) + 멤버십 변경 이력(SCD, append-only).
--   org_memberships 에 HR 속성 컬럼을 더한다(모두 nullable, employment_status 만 NOT NULL DEFAULT).
--   org_membership_history: 멤버십 변경 시점마다 현재 상태 스냅샷을 남기는 append-only 로그.
--     예측 모집단 분해·정산 자격·과거 귀속에 시점별 소속/역할/재직상태를 재구성하기 위한 정답데이터.
--     department_id/site_id 는 FK 없음(이력 불변성 — 배정 부서/사업장이 나중에 삭제돼도 과거 스냅샷은
--     그 id 를 그대로 보존한다). org_id 만 CASCADE(조직 삭제 시 이력도 함께 정리).
ALTER TABLE org_memberships ADD COLUMN employee_id VARCHAR(64);
ALTER TABLE org_memberships ADD COLUMN job_title VARCHAR(120);
ALTER TABLE org_memberships ADD COLUMN employment_type VARCHAR(24);              -- FULL_TIME/PART_TIME/CONTRACT/INTERN (nullable)
ALTER TABLE org_memberships ADD COLUMN hire_date DATE;
ALTER TABLE org_memberships ADD COLUMN employment_status VARCHAR(24) NOT NULL DEFAULT 'EMPLOYED';  -- EMPLOYED/ON_LEAVE/TERMINATED

CREATE TABLE org_membership_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    role VARCHAR(24) NOT NULL,
    department_id UUID,                 -- 스냅샷 시점의 배정(과거 값 보존 — FK 없음: 부서 삭제돼도 이력 불변)
    site_id UUID,                       -- FK 없음(이력 불변)
    employment_type VARCHAR(24),
    employment_status VARCHAR(24) NOT NULL,
    job_title VARCHAR(120),
    change_type VARCHAR(32) NOT NULL,   -- JOINED/ROLE_CHANGED/ASSIGNED/ATTRIBUTES_UPDATED/REMOVED
    recorded_at TIMESTAMP NOT NULL DEFAULT now(),
    recorded_by UUID
);
CREATE INDEX idx_membership_history_org_user_time ON org_membership_history(org_id, user_id, recorded_at);
