-- 조직 스코프 활동로그(audit) 뷰: detail 은 TEXT(JSON 문자열)라 JSON 연산자로 org 를 거를 수 없다.
-- org 결속 감사 이벤트에만 채워지는 전용 컬럼(nullable — 로그인·MFA 등 전역 이벤트는 NULL)을 추가하고,
-- (org_id, created_at) 인덱스로 조직별 최신순 조회를 지탱한다.
ALTER TABLE audit_events ADD COLUMN org_id UUID;
CREATE INDEX idx_audit_events_org_time ON audit_events(org_id, created_at);
