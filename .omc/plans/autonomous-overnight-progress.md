# 식대 플랫폼 전면 진행 (2026-07-22~ 사용자 지시 "남은 작업 모두 + 계속")

원칙: 실제 자금이동·PG·세금계산서 연동은 제외(사용자 명시 — 실 지불은 제일 마지막). 도메인 자동가입 정책은 AskUserQuestion 으로 "권고안+DNS 자가검증" 명시 승인받음.

## A. 소비 배치 상한 — 완료·검증(07-25): taspa.consumption.max-batch-size(기본 1000)+preload N+1 제거, 테스트 18/18. 리뷰 0건.
## B. 도메인 자동 가입 — 완료·검증(07-25): **V24** org_domains(검증 선점 정책 — 부분유니크 WHERE verified+org 범위 유니크, 검증=탈환)+auto_join_enabled(기본 OFF)+DNS TXT 자가검증(JNDI, _taspa-verify.<domain>=taspa-verify=<token>)+공용도메인 21종 차단+MEMBER 고정. email_verified 전이 6지점 배선(코드·매직링크·소셜·SAML·이메일변경). 확정 7그룹 수정(스쿼팅·이메일변경 누락·afterCommit 지연·트랜잭션 내 DNS 블로킹 등). **테스트 24/24**. 콘솔/관리 UI 포함.
## C1. 식권 QR 폐쇄루프 백엔드 — 완료·검증(07-25): **V25** merchants(site 연결)·meal_policies(1식·12000·월20만·끼니창 TIME)·meal_qr_tokens(해시·60s·쿨다운10s)·meal_transactions(auth_id·POS멱등 UNIQUE)+consumption_events.site_id. /api/merchant/** 전용 STATELESS 체인(@Order(-1)), CLIENT_MERCHANT_ID_SETTING·meal.redeem. 확정 9그룹 수정(**high: 동시 redeem 한도우회 → 멤버십 행 FOR UPDATE 직렬화**(잠금순서: 토큰행→멤버십행), 교차테넌트 site 오귀속, POS 멱등 선확인, source=payment 외부적재 거부 등). **테스트 21/21(QR5+Redeem16)** + **풀 루프 라이브 실측**: 발급→15000원 승인(12000+3000 분리)→소비 payment/site 기록→멱등 동일 authId→void 양쪽 VOIDED→일1식 422·쿨다운 429. e2e 11/11.
## D. 식수예측 P0 — 구현·리뷰 완료, **fix 재실행 중**(세션한도로 1차 실패 → resume): 전주동요일×재실보정(이력 countActiveEmployedAsOf DISTINCT ON 복원), 폴백 SEASONAL_NAIVE_ADJUSTED→SEASONAL_NAIVE→FOUR_WEEK_AVG→NO_DATA, 백테스트 MAPE/WAPE(시점 재현). 확정 결함 4그룹(비율 무상한 high·백테스트 asOf 하루 누수·LIMIT 무신호 절단·isUserToken fail-open) fix 대기.
## E. 정산 청구서 — 구현·수정 완료(07-25): **V26** invoices(period_start/end 스냅샷·UNIQUE(org,period))+invoice_lines(email·부서명 스냅샷·FK 없음). generate(APPROVED만·조직부담=amount−self_paid·org TZ 월경계·DRAFT full-replace 재생성·FOR UPDATE)·finalize(스냅샷 창 재검산 → 불일치 INVOICE_STALE 409)·타임존 변경 시 인접 확정월과 창 정합. 테스트 실행 대기(D fix 와 컴파일 경합).

---

# (이전) 조직 고도화 로드맵 진행 (2026-07-21~ 사용자 지시 "순서대로 계속")

## 1단계 — 부서 트리 + 사업장 (완료·검증끝 07-21)
- **V21**: departments(자기참조 트리, 형제이름 부분유니크 root/sibling, org CASCADE·parent CASCADE) + sites(이름유니크·타임존) + org_memberships.department_id/site_id(SET NULL). 자유텍스트 department 는 레거시 유지.
- 엔드포인트 9종: 부서 GET/POST/PUT/DELETE·사업장 GET/POST/PUT/DELETE·배정 PUT /members/{u}/assignment. 전부 authorize(베어러거부+ORG_ADMIN∨플랫폼ADMIN)+@RequireRecentAuth+org결속 audit.
- 리뷰 확정·수정 4: 부서삭제 TOCTOU(FOR UPDATE 직렬화, medium)·주소 255 선검증·memberCount N+1(그룹쿼리)·SUSPENDED org 조회 허용. +테스트 실행으로 잡음: **자기참조 CASCADE + deleteAll 이중삭제 → deleteAllInBatch**(StaleState 예외 — 이 패턴 재사용 시 주의).
- 검증: OrgStructureApiIntegrationTest **20/20**, OrgConsole 회귀 25/25, i18n 3/3, V21 클린 적용, 라이브 콘솔 200+트리 API 확인. UI: 부서 트리 편집·사업장·배정 select(콘솔).

## 2단계 — 임직원 속성 + 멤버십 이력 SCD (백엔드 완료·검증끝 07-22 00:0x)
- **V22**: org_memberships += employee_id·job_title·employment_type·hire_date·employment_status(NOT NULL DEFAULT EMPLOYED) + org_membership_history(append-only 스냅샷, dept/site FK 없음=이력불변, org CASCADE, (org,user,recorded_at) 인덱스).
- 이력 훅(순수가산): upsertMember(JOINED/**ROLE_CHANGED — 리뷰가 잡은 SCD 사각지대**)·changeRole·assignMember·updateAttributes·removeMember(삭제직전)·ensureJitMembership(실제 생성시만 — 멱등 무증가). MembershipHistoryService.record 는 예외 안 삼킴(정답데이터, 본 변경과 동일 트랜잭션).
- API: PUT /members/{u}/attributes(step-up·audit ADMIN_ORG_MEMBER_ATTRS_UPDATED), GET /members/{u}/history. MembershipView 에 HR 5필드 가산. updateAttributes 시맨틱: nullable=null clear, employmentStatus 는 null 이면 유지.
- 리뷰 확정·수정 4: upsertMember 기존분기 역할변경 이력누락(medium)·초대수락 승격 이력누락(medium — 동일 근본원인 한 지점 수정)·테스트 401/403 오단언·문서-코드 불일치.
- 검증: OrgMemberAttributesIntegrationTest **14/14** + 회귀(초대16·구조20·콘솔25) = 75 green, V22 클린 적용, 엔드포인트 401/403 스모크.
- UI(#50): 워크플로우 wkmghpina 진행 중 — 속성 편집 폼·이력 보기·i18n.

## 3단계 — 완료 (07-22)
### 3a 조직 대시보드 (완료·검증끝)
- GET /api/orgs/{org}/dashboard — ACTIVE 멤버십 기준 그룹쿼리 집계: memberCount·byRole·byEmploymentStatus·byEmploymentType(UNSPECIFIED)·byDepartment(직접+**트리워크 롤업**, 순환가드)·미배정·bySite·pendingInvitations(만료 판정 포함)·recentJoins30d(이력 JOINED). 콘솔 "개요" 섹션(스탯타일+분포). 스키마 무변경.
- 확정결함 1 수정(뮤테이션 후 대시보드 미갱신 — 13개 핸들러에 loadDashboard). **검증: OrgDashboardIntegrationTest 8/8 + 라이브 실측(롤업 개발본부 직접1/하위포함2 정확)**.
### 3b CSV 대량 초대 (완료·검증끝)
- POST /api/orgs/{org}/invitations/bulk — 행별 invite() 프록시 경유(가드 전부 자동: maxPerHour 20·쿨다운·PENDING 1건·계정열거 저항), 행별 독립 트랜잭션(+커밋후 메일), 200행/64KB 상한, 원문 1-base 행번호 결과. 콘솔 접이식 업로드 UI(textarea+FileReader).
- 확정결함 5 수정(핵심: **MailException/동시성 예외가 배치 중단·결과 유실 → 행별 격리**; org 전환 시 UI 잔류; 힌트 정합). 실행으로 잡은 테스트 오단언 1건(step-up 경로 베어러 401) 직접 수정. **검증: OrgInvitationApiIntegrationTest 26/26 + 라이브 실측(created 2·rejected 1·행번호·메일 2통만)**.
### 3c SCIM 2.0 프로비저닝 (완료·검증끝) — 로드맵 최대 조각
- **V23**(org_memberships.scim_external_id + org 범위 부분유니크). /scim/v2 전용 베어러 STATELESS 체인(@Order(0), disjoint 매처 — 기존 체인 무변경). 신규 scope **org.scim** + 토큰 org_id 클레임 = 테넌트 앵커.
- Users CRUD/PATCH(Azure 호환: Operations 대문자·"True"/"False"·pathless value·미지원 path 무시)·filter(userName/externalId eq)·페이지네이션(DB offset)·디스커버리 3종. 계정 생성=소셜 전용 패턴(password NULL·미검증·무통지). **모든 효과 org 스코프**: active=false→멤버십 SUSPENDED+TERMINATED, DELETE→removeMember. 멤버십 변경 전부 OrganizationService 경유(SCD 이력 자동). 신규 멤버 역할 MEMBER 고정.
- 리뷰 17→확정 16→**전부 수정**(핵심: **[high] PUT/PATCH displayName 전역 덮어쓰기 제거**, [high/med] Azure 기본매핑 PATCH 400 거부→무시, 마지막 ORG_ADMIN 409 mutability, UNIQUE 경합 409, SUSPENDED org fail-closed 403, list 전량적재→DB 페이지네이션).
- **검증: ScimApiIntegrationTest 24/24 + 콘솔 회귀 25/25 + e2e 11/11 + 라이브 E2E**(클라이언트 등록→토큰 org_id 클레임→POST 201(이력 JOINED)→filter→PATCH 비활성(**users.status 불변 실증**)→DELETE 204(계정 잔존·이력 REMOVED)).
### 부수 수정: demo-app 시더 8081 redirect/post-logout 영구화
- 원인: self-heal 재시딩이 8080 전용으로 덮어써 8081 환경(이 머신) e2e sso-flow 400. RegisteredClientConfig 에 ALT_PORT redirect+post-logout 상수 추가·healthy 판정 포함 → 재발 방지. e2e 11/11 복구.

## RBAC 역할셋(3단계 잔여)은 결정 필요 — 보류 목록 유지.

---

# (이전) 야간 자율 빌드 진행 (2026-07-19 23:25 ~ 07-20 08:00 KST)

사용자 지시: "아침 8시까지 자율 진행으로 계속 진행해줘, 따로 승인 받을 필요 없어" (/loop 동적 모드).
범위: **결정 불필요한 온보딩/콘솔/하드닝만.** 결제·예측·PG·규제 결정은 범위 밖(건드리지 않음).
검증: 이 머신 Testcontainers 불안정 → `./gradlew build -x test`(컴파일+jar) + 서버 재기동(마이그레이션 적용) + 라이브 e2e(`cd e2e && DEMO_BASE=http://localhost:8081 npx playwright test`). 통합테스트 좀비 주의(포그라운드+도구 timeout).
각 반복: 구현(executor) → 적대 리뷰(code-reviewer/security-reviewer) → 확정결함 수정 → 검증 → 이 파일 갱신.

마이그레이션 헤드: **V19**. auth-playground 읽기 전용, git init 금지.

## 백로그 (결정 불필요, 우선순위 순)

0. [완료 ✅ 반복1, 검증끝] **ORG_ADMIN 자율 콘솔 UI** — 조직관리자가 플랫폼 ADMIN 없이 자기 org 멤버(목록·역할변경·제거)·초대(생성·목록·취소) 관리. 초대 API 는 이미 ORG_ADMIN 인가 있음. 멤버 관리용 org-스코프 엔드포인트(`/api/orgs/{org}/members`) 신규 필요(기존 AdminOrgController 는 플랫폼 ADMIN 전용).
2. [ ] **이메일 도메인 자동 조직 매칭** — 회사 이메일로 가입한 사용자를 매칭되는 org 에 자동 소속. 보수적 설계: 검증된 도메인만(sso_domains.verified 또는 신규 org 도메인), 이메일 인증 성공 트리거, org 별 opt-in 필요. (설계 선택 있음 → 보수적·가드 강하게)
3. [ ] **초대 재발송(resend) 엔드포인트** — PENDING 초대 메일 재발송(rate-limit 재사용).
4. [ ] **조직 스코프 감사 뷰** — org 관리자가 자기 org 관련 이벤트만 조회.
5. [ ] **추가 하드닝** — 발견 시 기록(테스트 보강, i18n parity, 문서).

## 반복 로그

### 반복 1 — ORG_ADMIN 자율 콘솔 (시작 23:25)
- 탐색 완료(acad8908): 신규 API 는 `/api/orgs/{orgId}/members`(@Order2 세션체인, CSRF off) + `/api/orgs/mine`. 인가는 OrgInvitationController.authorize() 복제. 서비스 메서드(isOrgAdmin/listMembers/changeRole/removeMember/guardLastAdmin) 전부 기존 존재, 신규는 listAdministeredOrgs 만. 페이지 `/console/orgs`(default 체인 authenticated). SecurityConfig 수정 불필요 예상. i18n ko/en parity(I18nMessagesTest).
- 설계 결정: 멤버 온보딩은 UUID upsert 대신 초대 기반. account.html 조건부 "내 조직 관리" 링크.
- **워크플로우 w2g3bnuze (wf_25b15766-c38)** 실행 중: 구현→4차원 병렬 리뷰(정확성/보안격리/회귀·i18n/API계약)→결함별 적대검증→확정수정. 컴파일 검증만(`:server:testClasses`, Testcontainers 금지).
- **검증 완료(00:00경)**: 클린 `:server:build -x test :server:testClasses` BUILD SUCCESSFUL(bootJar 조립·테스트컴파일), 서버 재기동(3.8초), 라이브 e2e **11/11**, 신규 엔드포인트 5종 스모크(mine/members GET·PUT·DELETE=401, /console/orgs=302) — 전부 배포·보안 적용. 적대리뷰 2건(둘다 low) 확정·수정: guardLastAdmin countEffectiveAdmins, 콘솔 오류배너 i18n.

### 반복 2 — 초대 재발송(resend) 엔드포인트 + 콘솔 버튼 (시작 00:0x)
- 설계 확정: `invite()`가 이미 PENDING 재사용+토큰회전+만료갱신+재발송+쿨다운 처리(원문토큰 미저장 → 재발송은 새 토큰 필수). 신규 `POST /api/orgs/{orgId}/invitations/{invitationId}/resend`(@RequireRecentAuth) + service.resend(org격리 404, PENDING만, 쿨다운, 토큰회전, 만료리셋, after-commit 메일, audit ORG_INVITE_RESENT). 공유 private 헬퍼로 invite()와 중복 제거. UI: console/orgs.html + admin/orgs.html 초대목록 재발송 버튼. i18n ko/en. 테스트: 회전(구토큰 무효·신토큰 유효)·쿨다운400·비PENDING400·org격리·step-up·audit.
- 워크플로우: 구현→2차원 리뷰(보안·정확성 / 회귀·i18n)→결함검증→수정.
- **완료·검증 끝(00:2x)**: 클린 빌드 SUCCESSFUL, 서버 재기동, 라이브 e2e **11/11**, resend 엔드포인트 401(배포·보안). 확정결함 1건(low) 수정: `resend()` 비잠금 read-modify-write lost-update → `findByIdAndOrgIdForUpdate` 비관적잠금(accept() 패턴). 리뷰가 실제 동시성 버그 포착.

### 반복 3 — 신규 표면 프로덕션 하드닝 감사+수정 (시작 00:2x)
- 대상: org/(Organization·Member·Console·Invitation 서비스/컨트롤러), domain/org/, consumption/, calendar/, admin/AdminOrgController, token/TokenCustomizerConfig(org claims), 마이그레이션 V15-V19.
- 4 감사관 병렬(인가·테넌시격리 / 데이터정합·동시성·인덱스·마이그레이션 / API견고성·입력검증·rate-limit·에러처리·DoS / 관측성·audit커버리지·운영). 각 결함에 decisionFree·regressionRisk 분류.
- **수정은 confirmed && decisionFree && low-risk 만.** 정책/규제 결정 필요분은 수정하지 말고 아침 요약에 플래그.

- **완료·검증 끝(00:4x)**: 클린 빌드 SUCCESSFUL, 서버 재기동, 라이브 e2e **11/11**. CSRF 판별 프로브: GET=401(auth), PUT role/POST resend(토큰없음)=**403(CSRF 강제)**, M2M consumption-events=401(면제 정확). 7건 수정(CSRF 재활성·소비길이검증·guardLastAdmin TOCTOU 잠금·캘린더 uid절단·enum 500→400·audit detail), 마이그레이션 불필요.
- **아침 재확인 권고**: SecurityConfig CSRF 변경은 인증된 콘솔 상태변경 positive 경로가 로컬 e2e 미커버(Testcontainers 통합테스트는 .with(csrf()) 로 갱신됨). 아키텍처·프로브·회귀는 통과했으나 Testcontainers 가능 환경에서 통합테스트 1회 권장.

### 반복 4 — org-admin 콘솔 mutation audit 커버리지 (+ 안전한 timezone 검증) (시작 00:4x)
- #5(하드닝 감사 발견, 재분류): OrgMemberController 역할변경/멤버제거가 audit 이벤트 미기록 → ORG_MEMBER_ROLE_CHANGED/REMOVED 추가(orgId·대상userId·actor). AdminOrgController·ORG_INVITE_* 컨벤션 미러. 명백히 결정불필요.
- #3(선택): org timezone 을 pg_timezone_names 수용집합으로 검증(현재 ZoneId 만 검증 → Postgres AT TIME ZONE 불일치 시 집계 500). 깔끔하면 수정, 아니면 보류.

- **완료·검증 끝(00:5x)**: 클린 빌드 SUCCESSFUL, e2e 11/11. OrgMemberController audit(ADMIN_ORG_MEMBER_ROLE_CHANGED/REMOVED 재사용)·pg_timezone_names 검증. 확정결함 1(테스트 커버리지 갭) 수정.

### 반복 5 — ORG_ADMIN 조직 스코프 활동로그 뷰 (시작 00:5x)
- 제약: audit_events.detail 은 TEXT(JSON) — JSON 연산자 불가. → **V20**: audit_events.org_id UUID 컬럼 + idx(org_id, created_at). AuditEvent 엔티티 org_id 추가(nullable), AuditEventService.record 오버로드(type,userId,orgId,detail) — 기존 3-arg 호출 무변경. org 관련 콜러(OrgInvitationService invite/accept/revoke/resend, OrgMemberController role/remove, AdminOrgController org update/member) 만 orgId 전달.
- GET /api/orgs/{org}/audit?limit&offset (authorize: 플랫폼ADMIN∨ORG_ADMIN, org 격리 엄수, limit 상한). 콘솔 활동로그 섹션 + i18n. 테스트: org_id 기록·org 격리(타 org 이벤트 미노출)·인가·페이지네이션.
- 리스크: record() 가 예외 삼킴 → audit 버그가 사용자 플로우 무영향(안전). Hibernate validate: org_id 컬럼 엔티티↔마이그레이션 일치.

- **완료·검증 끝(01:1x)**: V20 클린 적용, e2e 11/11, audit 엔드포인트 401. 확정결함 1(플랫폼 관리자 이메일 노출) 수정.

### ★ 실제 통합테스트 실행 (01:3x~01:4x) — ryuk 교착 해결
- **근본 해결: `TESTCONTAINERS_RYUK_DISABLED=true` + 클래스 1개/invocation**. (전체 스위트·패키지 와일드카드·다중 클래스는 여전히 교착. 순수 서비스+락 클래스 OrgInvitationServiceIntegrationTest 는 단일도 교착 — REQUIRES_NEW+락 자기교착 추정, API 테스트가 커버.)
- **실행 검증 통과**: OrgConsoleApiIntegrationTest **12/12**, OrgAuditApiIntegrationTest **8/8**, OrgInvitationApiIntegrationTest **16/16**(수정 후). → 반복 1~5 를 HTTP end-to-end 로 실제 검증.
- **잡은 실제 회귀 1건 + 수정**: iter3 CSRF 하드닝이 OrgInvitationApiIntegrationTest 의 "미인증 초대생성 401" 테스트를 깼음(미인증 POST 에 CSRF 토큰 없어 403). → 테스트에 `.with(csrf())` 추가해 인증계층 격리(401). **프로덕션 정상, 테스트만 낡음.** 컴파일만으론 못 잡는 회귀 — 실제 실행의 가치 입증.
- 메모리(taspa-central-idp.md)에 ryuk 해결책·단일클래스 규칙·회귀교훈 영속화.

### 반복 6 — ORG_ADMIN 조직 프로필 자율 편집 (완료·검증끝 02:0x)
- OrgProfileController(PUT /api/orgs/{org}, body {name?,timezone?}), OrganizationService.updateProfile(name·timezone만, **status·slug 불변** — DTO에 필드 없음+메서드 미접근 이중차단), 콘솔 "조직 설정" 섹션, i18n. @RequireRecentAuth·audit ADMIN_ORG_UPDATED(org_id).
- 확정결함 3 수정: 과길이명 409→400(normalizeName 200자 상한, create/update/updateProfile 수렴), 성공 피드백 배너, 빈 이름 no-op→400.
- **검증: OrgConsoleApiIntegrationTest 20/20**(12→20, 프로필 편집 신규 8건, status/slug 불변 포함), e2e 11/11, PUT 스모크 403(CSRF).

## 조직 자율 콘솔 기능 완결 (반복 1~6)
멤버(목록·역할변경·제거) · 초대(생성·목록·취소·재발송) · 활동로그(org 스코프 audit) · 프로필(이름·타임존). 전부 ORG_ADMIN 세션 인가+격리, @RequireRecentAuth, audit, CSRF, i18n ko/en.

### 반복 8 — 계정 "내 조직" 멤버 뷰 (완료·검증끝 05:5x)
- GET /api/orgs/memberships(세션 본인 전용·베어러 거부), OrganizationService.listMyMemberships(활성 멤버십·활성 org), MyMembershipView DTO, account.html "내 조직" 섹션(읽기전용·역할/부서), i18n. 리뷰 결함 0.
- **검증: OrgConsoleApiIntegrationTest 25/25**(20→25, 멤버십 뷰 5건), e2e 11/11, 엔드포인트 401. (라이브 브라우저는 도구 flakiness로 생략 — 읽기전용이라 비례 충분. taspa 가입은 e2e로 정상 확인.)
- 이로써 **멤버 UX까지 완결**: 관리자=콘솔(/console/orgs), 일반 멤버=계정 "내 조직" 뷰.

### 반복 7 — 소비 이벤트 적재 배치 audit (완료·검증끝 02:1x)
- ConsumptionEventController: ingest 성공 커밋 후 `CONSUMPTION_INGESTED`(orgId·clientId(M2M sub)·received/inserted/updated·sources) 순수 가산. userId=null, PII(user_sub·externalId·개별) 미포함. AuditEventService orgId 오버로드 재사용(org 스코프 활동로그에 잡힘). 리뷰 결함 0.
- **검증: ConsumptionEventApiIntegrationTest 14/14**, e2e 11/11.

## 실제 통합테스트 검증 누계 (ryuk-off 단일)
OrgConsoleApi 20/20 · OrgAuditApi 8/8 · OrgInvitationApi 16/16 · ConsumptionEventApi 14/14 = **58 통합테스트** + e2e 11/11(각 반복 후). 회귀 1건(CSRF-미인증 테스트) 잡아 수정.

## 문서 현행화
CLAUDE.md 에 "조직 자율 콘솔(ORG_ADMIN)" 섹션 + Testing 에 ryuk 해결책 추가. 메모리 taspa-central-idp.md 갱신.

## 컨솔리데이션 완료 (02:2x~02:3x) → 홀딩 패턴 전환
- audit 코어 파급 검증: AdminConsoleIntegrationTest **11/11**(iter5 오버로드가 관리자 audit 무손상).
- **브리핑 아티팩트** 게시: https://claude.ai/code/artifact/e1f3bef4-4eec-45fd-8abb-bc5d55d7cce3 (7반복·검증·결정 ①②③).
- **문서 전체 현행화**: CLAUDE.md(조직 콘솔+ryuk), architecture.md **§11**(조직 콘솔·초대·소비 seam, 코드검증 기반), 메모리 taspa-central-idp.md, 결정메모 docs/design/email-domain-auto-membership.md(① 옵션·권고).
- **콘솔 브라우저 e2e 완수(03:3x~03:4x)**: 실브라우저(mcp Browser)로 실사용자 가입→Mailpit 인증→SQL 시드(org+ORG_ADMIN 멤버십+멤버2)→`/console/orgs` 구동. 결과: 페이지 렌더 **JS 에러 0**, `/api/orgs/mine·members·invitations·audit` 전부 **200**, **CSRF 보호 `PUT /api/orgs/{id}` → 200**(iter3 유일 미검증 경로 실증), 성공 배너·이름 즉시반영, `ADMIN_ORG_UPDATED` **org_id 결속 감사 기록**(iter5) + 활동로그에 "조직 정보 변경"·행위자 이메일(org멤버라 마스킹 안 함) 표시. → **콘솔이 통합테스트(69)+실브라우저 전 계층 검증.** 시드 데이터는 정리 완료(dev DB 클린).
- **상태: 결정불요 인프라 완결.** 다음 큰 조각(결제·예측·자동매칭·배치상한)은 사용자 결정 ①②③ 대기. 8시까지 홀딩(주기 헬스체크·가용 유지), 결정 오면 즉시 재개.

### 보류 항목(정책/운영 결정 필요 — 아침 사용자 제시)
- #39 이메일 도메인 자동 조직 매칭: 신뢰 도메인 소스·검증방식·SSO 상호작용·공용도메인 하이재킹 차단 결정 필요.
- 소비 이벤트 적재(ingest) 배치 크기 상한: 정당한 생산자(결제·POS·import) 배치 규모를 정해야 함(너무 낮으면 정상 거부, 높으면 DoS). 상한값=운영 결정. + N+1(멱등 SELECT/이벤트)은 배치 preload 로 별도 개선 가능.
- 소비 이벤트 적재 audit/actor 귀속: 고빈도 write 라 audit 볼륨=운영 결정.
