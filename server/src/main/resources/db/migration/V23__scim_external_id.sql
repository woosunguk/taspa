-- 조직 3c: SCIM 2.0 프로비저닝(Users 전용) — HR 시스템(Workday·Azure AD 등)이 부여하는 외부 식별자.
--   externalId 는 "그 org 의 SCIM 클라이언트" 네임스페이스에 속하므로 org 범위 유니크(부분 인덱스)로 강제한다.
--   전역 유니크가 아니어야 같은 사용자가 서로 다른 org 의 HR 시스템에서 각자의 externalId 를 가질 수 있다.
ALTER TABLE org_memberships ADD COLUMN scim_external_id VARCHAR(255);
CREATE UNIQUE INDEX uq_membership_scim_external ON org_memberships(org_id, scim_external_id) WHERE scim_external_id IS NOT NULL;
