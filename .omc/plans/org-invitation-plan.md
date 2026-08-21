# 조직 초대(Invitation) 시스템

개인이 조직에 들어오는 표준 온보딩 경로: 관리자/조직관리자가 이메일로 초대 → 수락 시 org_memberships 생성.
기존 인증(로그인 게이트·이메일 인증·토큰 SHA-256 해시 저장·MailService·step-up·rate-limit) 재사용. 기존 248 테스트·게이트·i18n(신규 UI/메일 messages_ko/en 양쪽)·인증 플로우 무손상. 마이그레이션 헤드 V18 → 신규 **V19**. auth-playground·git init 금지. 빌드 포그라운드 확인.

## 데이터 — V19__org_invitations.sql
```sql
CREATE TABLE org_invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email VARCHAR(100) NOT NULL,            -- 소문자 정규화(이메일 상한 100 = users와 정합)
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
CREATE UNIQUE INDEX uq_org_invitation_pending ON org_invitations(org_id, email) WHERE status = 'PENDING';
```

## 구성
- `domain/org/OrgInvitation` + `OrgInvitationRepository`(findByTokenHash·findByOrgIdAndStatus·존재검사) + `InvitationStatus` enum.
- `org/OrgInvitationService`:
  - `invite(orgId, email, role, department, inviterId)` — 이메일 정규화, org ACTIVE 확인, **이미 활성 멤버면 거부(중복)**, 기존 PENDING 있으면 재사용/갱신(uq 제약), rawToken 생성·해시 저장·만료(기본 7d 설정 `taspa.org-invitation.expiry-days`), 초대 메일 발송, audit `ORG_INVITE_CREATED`.
  - `accept(rawToken, currentUser)` — 토큰 해시 조회·PENDING·미만료 검증, **currentUser.email(소문자) == invitation.email 강제**(불일치 시 거부 — 초대 하이재킹 차단), currentUser는 인증+이메일검증 상태여야 함, org ACTIVE 확인 → `org_memberships` upsert(role·department) → 초대 ACCEPTED(accepted_at/by) → audit `ORG_INVITE_ACCEPTED`. 이미 멤버면 멱등 성공.
  - `revoke(orgId, invitationId, actor)` — PENDING→REVOKED, audit.
  - `listPending(orgId)`, `expireOverdue()`(RetentionCleanupJob 훅 또는 조회 시 lazy).
- `mail/MailService.sendOrgInvitation(email, orgName, acceptUrl, expiresAt)` — 국문/영문 로케일, 수락 링크 `{base-url}/orgs/invite/accept?token=...`.

## API / 화면
- **초대 관리 API** (경로 `/api/orgs/{orgId}/invitations` — Phase 0 리소스서버 체인, 세션/베어러):
  - `POST` 초대 생성, `GET` PENDING 목록, `DELETE /{id}` 취소.
  - **인가**: 플랫폼 ADMIN **또는 해당 org의 활성 ORG_ADMIN**(`organizationService.isOrgAdmin(orgId, userId)`) — 캘린더/소비의 authorize 헬퍼 패턴. **타 org 초대/열람 불가**. 생성/취소는 민감 → `@RequireRecentAuth` 또는 step-up 정합.
- **수락 흐름**(사용자 대면):
  - `GET /orgs/invite/accept?token=...` — 인증 필요(미인증 시 로그인/가입 유도 후 복귀). 초대 org명·역할 표시. 로그인 사용자 이메일 == 초대 이메일이면 "수락" 버튼, 불일치면 안내(다른 계정으로 로그인).
  - `POST /orgs/invite/accept`(CSRF) — 토큰 소비·멤버십 생성·ACCEPTED. 완료 후 /account 또는 org 안내.
- **관리 콘솔 UI**: `/admin/orgs` 조직 상세에 "초대" 섹션(초대 폼 email·role·department + PENDING 목록·취소). i18n 키(admin.orgs.invite.*·invite.accept.*·mail.orgInvite.*), 네비 기존 유지.

## 보안 (리뷰 집중)
- 토큰: 256bit 랜덤·**해시만 저장**·단일사용·만료. 원문은 메일/URL로만.
- **이메일 일치 강제** on accept — a@x 초대를 b@y가 수락 불가(하이재킹 차단). 인증+이메일검증 세션 필수.
- **인가 격리** — 초대 생성/목록/취소는 플랫폼 ADMIN 또는 그 org의 ORG_ADMIN만, 타 org 불가.
- **남용 방지** — org·시간당 초대 발송 상한(기존 RateLimitFilter 또는 서비스 카운트), 미존재 이메일에도 동일 응답(계정 열거 금지), 한 (org,email) PENDING 1건.
- 만료/취소/사용됨 토큰 거부. 이미 멤버 수락은 멱등.
- audit: ORG_INVITE_CREATED/ACCEPTED/REVOKED.

## 테스트
- 초대 생성(권한: 플랫폼ADMIN·해당 ORG_ADMIN 허용 / 타 org ORG_ADMIN·일반멤버 거부), PENDING 1건 제약.
- 수락: 정상(멤버십 생성)·**이메일 불일치 거부**·만료 거부·취소 거부·재사용 거부·이미 멤버 멱등·미인증 유도.
- 취소·목록·org 격리.
- 메일 발송(mock 캡처·수락 URL·토큰).
- 기존 248·e2e 무손상.
- e2e(선택): 초대 발송(Mailpit)→링크→로그인→수락→소속 확인 시나리오는 통합테스트로 대체 가능(보고 명시).

## 제약
- 구현 → 3방향 적대 리뷰 → 확정 결함 수정 → `./gradlew build` 포그라운드 통과.
- 결제·예측 도메인 로직 범위 밖. ORG_ADMIN 전용 자율 콘솔 UI(gap #3)는 이 배치 범위 밖(초대 API 인가만 ORG_ADMIN 허용).
