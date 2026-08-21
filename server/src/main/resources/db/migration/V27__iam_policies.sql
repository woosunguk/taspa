-- AWS IAM 스타일 정책 기반 RBAC 1단계 — 정책 엔진 코어.
--   기존 인가(users.role=ROLE_ADMIN, org_memberships.role=ORG_ADMIN, M2M scope)는 그대로 둔다.
--   도입 경로: (1) 엔진·저장소 구축 → (2) 실제 인가 지점에 shadow 계측 배선(taspa.iam.shadow.enabled=true 시
--   엔진 판정을 레거시 판정과 병렬 비교해 불일치만 감사 IAM_SHADOW_MISMATCH 로 기록) → (3) 파리티 확인 후 집행 승격.
--   집행 전환 시에도 step-up(@RequireRecentAuth)·CSRF·위임베어러 거부·행단위 소유권 가드는 엔진 밖에 그대로 남는다.
--   정책 문서(document)는 JSON 직렬화 문자열(TEXT) — audit_events.detail 과 동일 규약(앱이 Jackson 으로 파싱).
--   평가 규칙(AWS 동일): 기본 암묵적 거부 → 명시적 Deny 하나라도 매치 시 거부 → Allow 매치 시 허용 → 그 외 거부.

-- 재사용 가능한 managed 정책. org_id NULL = 플랫폼(글로벌) 관리 정책, non-null = 조직(고객) 관리 정책.
--   system_managed = 플랫폼이 시드한 시스템 정책(콘솔 편집/삭제 금지, AWS "AWS managed policy" 대응).
CREATE TABLE iam_policies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(128) NOT NULL,
    org_id UUID REFERENCES organizations(id) ON DELETE CASCADE,   -- NULL = 플랫폼 전역
    description VARCHAR(512),
    document TEXT NOT NULL,                                        -- 정책 JSON {Version, Statement[]}
    system_managed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
-- 이름 유일성: 플랫폼 정책은 전역 유일, 조직 정책은 조직 내 유일(부분 유니크로 분리).
CREATE UNIQUE INDEX uq_iam_policy_global_name ON iam_policies(name) WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_iam_policy_org_name ON iam_policies(org_id, name) WHERE org_id IS NOT NULL;
CREATE INDEX idx_iam_policy_org ON iam_policies(org_id);

-- 사용자 그룹(IAM Group) — 정책을 그룹에 붙이면 멤버 전원에 적용. org_id NULL = 플랫폼 그룹.
CREATE TABLE iam_principal_groups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(128) NOT NULL,
    org_id UUID REFERENCES organizations(id) ON DELETE CASCADE,
    description VARCHAR(512),
    system_managed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_iam_group_global_name ON iam_principal_groups(name) WHERE org_id IS NULL;
CREATE UNIQUE INDEX uq_iam_group_org_name ON iam_principal_groups(org_id, name) WHERE org_id IS NOT NULL;
CREATE INDEX idx_iam_group_org ON iam_principal_groups(org_id);

-- 그룹 멤버십(user ↔ group). 유저/그룹 삭제 시 함께 정리.
CREATE TABLE iam_group_members (
    group_id UUID NOT NULL REFERENCES iam_principal_groups(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, user_id)
);
CREATE INDEX idx_iam_group_member_user ON iam_group_members(user_id);

-- managed 정책 부착: 정책 → principal(USER|GROUP). 정책 삭제 시 부착도 CASCADE.
--   principal_id 는 USER=users.id, GROUP=iam_principal_groups.id (다형 참조라 FK 없음 — 앱이 무결성 보장).
CREATE TABLE iam_policy_attachments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id UUID NOT NULL REFERENCES iam_policies(id) ON DELETE CASCADE,
    principal_type VARCHAR(16) NOT NULL,   -- USER | GROUP
    principal_id UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_iam_attach ON iam_policy_attachments(policy_id, principal_type, principal_id);
CREATE INDEX idx_iam_attach_principal ON iam_policy_attachments(principal_type, principal_id);

-- inline 정책: principal 에 직접 임베드된 문서(그 principal 소유, 재사용 불가). AWS inline policy 대응.
CREATE TABLE iam_inline_policies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_type VARCHAR(16) NOT NULL,   -- USER | GROUP
    principal_id UUID NOT NULL,
    name VARCHAR(128) NOT NULL,
    document TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_iam_inline ON iam_inline_policies(principal_type, principal_id, name);
CREATE INDEX idx_iam_inline_principal ON iam_inline_policies(principal_type, principal_id);
