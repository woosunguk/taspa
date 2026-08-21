-- Phase 0-A: 조직 테넌시(ADR 0002 대안 C). organizations + org_memberships.
--   organizations: 테넌트 경계. slug 는 URL/식별용 정규화 키(소문자·[a-z0-9-]).
--   org_memberships: 사용자-조직 다대다 + 역할(MEMBER | ORG_ADMIN). (org_id, user_id) 유니크.
--   sso_connections.org_id: 조직 IdP 커넥션을 조직에 연결한다 — JIT 멤버십은 이 값이 있을 때만 동작.
CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | SUSPENDED
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE org_memberships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(24) NOT NULL DEFAULT 'MEMBER',      -- MEMBER | ORG_ADMIN
    department VARCHAR(120),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',    -- ACTIVE | SUSPENDED
    joined_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (org_id, user_id)
);

CREATE INDEX idx_org_memberships_user ON org_memberships(user_id);
CREATE INDEX idx_org_memberships_org ON org_memberships(org_id);

-- 조직 IdP 커넥션 → 조직 매핑. NULL 이면 JIT 멤버십을 만들지 않는다(잘못된 조직 자동가입 금지).
ALTER TABLE sso_connections ADD COLUMN org_id UUID REFERENCES organizations(id) ON DELETE SET NULL;
