# Phase 0 — 공통 기반 인프라 + iCalendar 연동 (결정 불필요)

결제·예측 두 설계가 공유하는 토대. 제품·규제 결정이 필요 없는 것만. 기존 174 테스트·게이트 불변식 무손상.
마이그레이션 헤드 V14 → **신규 V15~V16**. auth-playground·git init 금지. 빌드는 포그라운드 확인 후 보고.

## A. 조직 테넌시 — organizations + org_memberships (ADR 0002 대안 C)

**V15__org_tenancy.sql**
```sql
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
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    joined_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (org_id, user_id)
);
CREATE INDEX idx_org_memberships_user ON org_memberships(user_id);
CREATE INDEX idx_org_memberships_org ON org_memberships(org_id);
ALTER TABLE sso_connections ADD COLUMN org_id UUID REFERENCES organizations(id) ON DELETE SET NULL;
```

- `domain/org/`: `Organization`, `OrgMembership`, `OrgRole`(MEMBER/ORG_ADMIN), `OrgStatus` + Spring Data repositories(`findByUserId`, `findByOrgIdAndUserId`, `existsByOrgIdAndUserId`).
- `org/OrganizationService`: 조직 CRUD(생성 시 slug 정규화·유니크), 멤버십 upsert/조회/역할변경/제거. 자기보호(마지막 ORG_ADMIN 강등 방지)는 최소 구현.
- **JIT 멤버십**: `federation/FederatedLoginSuccessHandler`에서 로그인 성공 시, 사용한 `sso_connection.org_id`가 있으면 `org_memberships`에 (user, org, MEMBER) upsert. sso_connections에 org_id 세팅은 관리 콘솔에서(아래) 또는 스펙 범위 밖(수동). JIT은 org_id가 있을 때만 — 잘못된 조직 자동가입 금지.
- **관리 콘솔** `/admin/orgs`(선택, 경량): 조직 목록/생성/상태, org별 멤버 목록·역할, sso_connection↔org 연결. @RequireRecentAuth + audit `ADMIN_ORG_*`. 관리 네비에 "조직" 추가. (콘솔이 부담되면 REST API `/api/admin/orgs`만이라도 — 단 hasRole ADMIN.)

## B. Scope 화이트리스트 설정화 (하드코딩 제거)

- `admin/AdminClientService.kt` L212 `ALLOWED_SCOPES` 하드코딩 → **`@ConfigurationProperties("taspa.oauth")`의 `allowedScopes: Set<String>`**로 이관. 기본값(application.yml)에 **기존 3개 + 플랫폼 scope** 포함:
  `openid, profile, email, org.read, meal.pay, merchant.read, merchant.write, settlement.read, settlement.write, meal.consumption.read, meal.forecast.read, meal.forecast.write, calendar.read`
- `resolveScopes` 검증은 이 설정을 참조. 미설정 시 기존 3개로 폴백(안전). 기존 클라이언트·테스트 무손상.
- 목적: 관리 콘솔에서 결제·예측·M2M 클라이언트를 이 scope로 등록 가능하게(결제·예측 설계의 1순위 수정 지점 공통 해소).

## C. TokenCustomizer org 클레임

- `token/TokenCustomizerConfig.kt` 확장: `authorizedScopes`에 **`org.read` 포함 시에만** org 클레임 발급(최소권한).
  - `org_memberships`에서 사용자의 활성 멤버십 조회. 단일이면 `org_id`(UUID) + `org_role`; 복수면 `orgs: [{id, role}]` 배열(+ 대표 org_id=첫 활성).
  - org 정보는 PII가 아니므로 **access_token·id_token 양쪽** 허용(리소스 서버가 인가에 사용).
  - client_credentials(사용자 없음) 경로는 기존처럼 early-return이라 org 클레임 미발급(NPE 없음) — 확인.
- OrgMembershipRepository를 TokenCustomizer에 주입(경량 조회, 캐시는 후순위).

## D. M2M — 확인만
- `oidc/RegisteredClientConfig`의 CLIENT_CREDENTIALS 이미 지원. 신규 scope(B)로 서비스 클라이언트 등록이 되는지 통합테스트로 확인(예: `meal.forecast.write` 허용 client_credentials 토큰 발급). 신규 코드 최소.

## E. iCalendar 연동 (RFC 5545)

목적: 조직 캘린더(공휴일·근무일정·행사)를 표준 .ics로 흡수 → 예측의 ①캘린더/④행사 피처 소스. 표준이라 결정 불필요.

- **의존성**: `net.sf.biweekly:biweekly:0.6.8`(경량·간결) — server/build.gradle.kts에 추가. (ical4j 대안이나 biweekly가 간단.)
- **V16__calendar.sql**
  ```sql
  CREATE TABLE calendar_feeds (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
      name VARCHAR(120) NOT NULL,
      type VARCHAR(16) NOT NULL,          -- HOLIDAY | WORK | EVENT
      source_url VARCHAR(1024),           -- 구독형(null이면 업로드형)
      enabled BOOLEAN NOT NULL DEFAULT true,
      last_synced_at TIMESTAMP,
      last_sync_status VARCHAR(16),
      created_at TIMESTAMP NOT NULL DEFAULT now()
  );
  CREATE TABLE calendar_events (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
      feed_id UUID REFERENCES calendar_feeds(id) ON DELETE CASCADE,
      uid VARCHAR(512) NOT NULL,          -- VEVENT UID (feed 내 유니크 upsert 키)
      summary VARCHAR(500),
      category VARCHAR(64),               -- CATEGORIES 또는 feed.type 파생
      starts_at TIMESTAMP NOT NULL,
      ends_at TIMESTAMP,
      all_day BOOLEAN NOT NULL DEFAULT false,
      source VARCHAR(16) NOT NULL,        -- UPLOAD | FEED
      created_at TIMESTAMP NOT NULL DEFAULT now(),
      UNIQUE (feed_id, uid, starts_at)
  );
  CREATE INDEX idx_calendar_events_org_time ON calendar_events(org_id, starts_at);
  ```
- `calendar/CalendarService`: biweekly로 .ics 파싱(VEVENT: UID·SUMMARY·DTSTART·DTEND·CATEGORIES·RRULE). **RRULE은 조회 윈도우 내로만 확장**(무한 반복 폭발 방지, 예: 향후 400일 상한). 업서트(feed_id+uid+starts_at). 타임존→서버 기준 정규화, all-day(DATE) 처리.
- **수집 2경로**: (1) 업로드 — org API로 .ics 본문 POST. (2) 구독 — source_url 등록 + 스케줄 동기화 잡(기존 스케줄러 패턴, 예 일 1회). 외부 URL fetch는 **타임아웃(예 5s)·크기상한(예 5MB)** 강제.
- **★SSRF 방어(필수)**: source_url은 **https만 허용**, 호스트 해석 IP가 사설/루프백/링크로컬/메타데이터(169.254.169.254)면 거부. 리다이렉트도 재검증. 관리자/조직관리자만 feed 등록 가능(권한).
- **조회 API**: `GET /api/orgs/{orgId}/calendar/events?from&to` — org 스코프, 인증 필요(예측 서비스가 M2M `calendar.read`로 pull, 또는 org_admin 세션). 페이징/윈도우 상한. 본인 org만.
- 관리/조직 콘솔에 캘린더 피드 등록·수동 동기화·이벤트 목록(경량 UI 또는 API만).
- 신규 UI 문자열은 **i18n 키(messages_ko/en 양쪽)** — Stage 6에서 전 템플릿 외부화했으므로 하드코딩 국문 금지.

## 테스트
- 조직/멤버십 CRUD·JIT(sso 로그인 시 org_id 있으면 멤버십 upsert)·역할.
- scope 설정화: 신규 scope로 클라이언트 등록 성공, 미허용 scope 거부, 기존 3개 폴백.
- org 클레임: `org.read` scope 시 토큰에 org_id/org_role, 없으면 미발급, client_credentials 무영향.
- iCal: 정상 .ics 파싱·업서트, RRULE 윈도우 확장 상한, malformed 안전 실패, **SSRF(사설IP·http·메타데이터 URL 거부)**, org 격리(타 org 이벤트 접근 불가), 조회 윈도우.
- 기존 174 무손상. e2e 무손상(신규 페이지 있으면 셀렉터 비간섭).

## 제약
- 결제·예측 **도메인 로직은 만들지 않는다**(제품·규제 결정 필요) — 공통 기반 + 캘린더만.
- 각 스테이지 구현 → 3방향 적대 리뷰 → 확정 결함 수정 → `./gradlew build` 포그라운드 통과.
