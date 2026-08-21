# Phase 0ب — 관리 콘솔 UI 완성 + 소비 이벤트 수집 seam (결정 불필요)

Phase 0가 만든 조직·캘린더의 관리 UI를 붙이고(현재 REST만), 예측의 정답데이터가 흘러들 소비 이벤트 수집 골격을 추가.
기존 218 테스트·게이트·i18n(신규 UI는 messages_ko/en 양쪽 키, 하드코딩 국문 금지)·모든 인증 플로우 무손상. 마이그레이션 헤드 V16 → 신규 **V17**. auth-playground·git init 금지. 빌드 포그라운드 확인.
결제·예측 **도메인 로직은 만들지 않는다** — 관리 UI + 제네릭 소비 이벤트 seam만.

## A. 조직 관리 콘솔 UI (`/admin/orgs`, 플랫폼 ADMIN)

- 기존 `AdminOrgController`(`/api/admin/orgs`)를 소비하는 Thymeleaf 페이지: 목록(이름·slug·상태·멤버수), 생성(name·slug), 상태변경(ACTIVE↔SUSPENDED), 상세(멤버 목록·역할변경 MEMBER↔ORG_ADMIN·제거, sso_connection 연결 표시/설정).
- 관리 콘솔 네비(dashboard/clients/users/audit/sso)에 "조직" 추가. account.html/admin/* 의 기존 fetch+meta CSRF 패턴 재사용. @RequireRecentAuth·audit는 기존 API가 담당.
- **i18n 키 필수**(admin.orgs.*), messages_ko/en 양쪽. I18nMessagesTest parity 통과.

## B. 캘린더 관리 콘솔 UI + 누락 API (`/admin/calendar`, 플랫폼 ADMIN)

- **누락 API 보강**(Phase 0 미구현): 피드 삭제 `DELETE /api/admin/orgs/{org}/calendar/feeds/{id}`, 활성 토글 `PATCH .../feeds/{id}` (enabled), 수동 동기화 트리거는 기존 유지. 이벤트 삭제는 CASCADE.
- Thymeleaf 페이지: org 선택 → 피드 목록(이름·type·source·enabled·last_synced_at·last_sync_status), 피드 등록(업로드 .ics 또는 구독 URL + type), 수동 동기화, 삭제/비활성, 최근 이벤트 미리보기(페이징). 네비에 "캘린더" 추가.
- 업로드는 multipart 또는 텍스트 붙여넣기. 구독 URL 등록 시 **기존 SSRF 방어(IcsUrlSecurity)** 경유. i18n 키(admin.calendar.*).

## C. 소비 이벤트 수집 seam (정답 데이터 · 제네릭)

결제(생산자, 미구현)와 예측(소비자, 미구현)을 분리하는 **append-only 소비 이벤트 로그**. 생산자 무관(payment·POS·manual import 모두 이 API로 이벤트 적재), 예측은 집계로만 읽음. 프라이버시: 개인 라벨은 선택, 집계 읽기만 노출.

**V17__consumption_events.sql**
```sql
CREATE TABLE consumption_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source VARCHAR(24) NOT NULL,               -- payment | pos | manual | import
    external_id VARCHAR(128) NOT NULL,         -- 생산자 멱등키
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_sub UUID,                             -- 개인 라벨(선택, 집계용 · 조회에 개별 노출 금지)
    merchant_id UUID,                          -- 식당(결제 도메인 생기면 FK)
    meal_window VARCHAR(16) NOT NULL,          -- BREAKFAST | LUNCH | DINNER
    menu_ref VARCHAR(128),                     -- 메뉴 식별(선택)
    quantity INT NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED', -- CONFIRMED | VOIDED
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (source, external_id)
);
CREATE INDEX idx_consumption_org_window_time ON consumption_events(org_id, meal_window, occurred_at);
```

- `domain/consumption/`: `ConsumptionEvent`, enums(MealWindow, EventStatus, ...), repository(집계 쿼리·윈도우 조회).
- `consumption/ConsumptionEventService`: 적재(멱등 upsert by (source,external_id), 배치), VOIDED 반영, 집계(org×date×meal_window[×menu] count).
- **적재 API**: `POST /api/orgs/{orgId}/consumption-events`(단건/배치) — M2M `meal.consumption.write`. 멱등(중복 external_id는 no-op/갱신). org 결속 검증.
- **집계 조회 API**: `GET /api/orgs/{orgId}/consumption-events/aggregate?from&to&groupBy=date,meal_window[,menu]` — `meal.consumption.read`. **집계 카운트만 반환(개별 이벤트/user_sub 미노출)**. 예측 피처 파이프라인의 정답 소스.
- **테넌시/보안**(캘린더와 동일 규칙, `/api/orgs/**` 리소스 서버 체인 재사용): 서비스 전조회=전용 스코프(`meal.consumption.read.all`) 또는 org 결속, 일반은 소속 org만. write는 org 결속 M2M. 개별 원시 이벤트 조회 API는 만들지 않음(집계만).
- application.yml allowed-scopes에 `meal.consumption.write`(있으면 유지)·`meal.consumption.read.all` 추가.

## 테스트
- 조직/캘린더 콘솔: 권한(비ADMIN 403), 페이지 렌더, i18n parity.
- 캘린더 누락 API: 피드 삭제/비활성, CASCADE.
- 소비 이벤트: 멱등 적재(중복 external_id no-op), 배치, VOIDED 제외 집계, 집계 정확성, **org 격리**(타 org 적재/조회 거부), 서비스 전조회 vs org 결속 스코프, **집계만 노출(개별 미노출)**, write scope 미충족 403.
- 기존 218·e2e 무손상.

## 제약
- 구현 → 3방향 적대 리뷰 → 확정 결함 수정 → `./gradlew build` 포그라운드 통과.
- 결제·예측 도메인 로직·PG·모델은 범위 밖(제품·규제 결정 대기).
