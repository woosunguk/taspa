-- 조직 초대(Invitation) 시스템. 관리자/조직관리자가 이메일로 초대 → 수락 시 org_memberships 생성.
--   token_hash: SecureTokenGenerator 256bit 원문의 SHA-256 hex(원문은 메일 링크로만, 저장은 해시만).
--   status: PENDING | ACCEPTED | REVOKED | EXPIRED. (org_id, email) 은 PENDING 상태에서 1건만 허용한다
--           (부분 유니크 인덱스 — 중복 초대 폭주 방지). email 은 소문자 정규화(users.email 과 상한 100 정합).
CREATE TABLE org_invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email VARCHAR(100) NOT NULL,            -- 소문자 정규화(이메일 상한 100 = users 와 정합)
    role VARCHAR(24) NOT NULL DEFAULT 'MEMBER',   -- MEMBER | ORG_ADMIN
    department VARCHAR(120),
    token_hash VARCHAR(64) NOT NULL UNIQUE, -- SecureTokenGenerator 256bit → SHA-256 hex
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING | ACCEPTED | REVOKED | EXPIRED
    invited_by UUID,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    expires_at TIMESTAMP NOT NULL,
    accepted_at TIMESTAMP,
    accepted_by UUID
);
CREATE INDEX idx_org_invitations_org ON org_invitations(org_id);
CREATE INDEX idx_org_invitations_email ON org_invitations(email);
-- (org, email) 당 PENDING 은 1건만 — 재초대는 기존 PENDING 을 갱신(재사용)한다.
CREATE UNIQUE INDEX uq_org_invitation_pending ON org_invitations(org_id, email) WHERE status = 'PENDING';
