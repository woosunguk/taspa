# 관리자 콘솔 + 리스크 기반 인증 + 클라이언트 연동 확충 구현 스펙

3단계: **Stage A(관리자 콘솔)** → **Stage B(리스크 기반 인증)** → **Stage C(데모 클라이언트 + 연동 레시피)**.

## 공통

- 빌드는 **포그라운드 실행 후 완료 확인** 뒤 보고 (백그라운드 걸고 종료 금지).
- 완료 기준: `cd /Users/woosunguk/workspace/taspa && ./gradlew build` 전체 통과 (Docker 켜져 있음, 기존 64개 테스트 무손상).
- 게이트 불변식 유지: pending은 SecurityContext 밖(세션 속성). auth-playground 수정·git init 금지.
- 기존 패턴 재사용: 페이지는 Thymeleaf + JS fetch(계정 페이지 패턴), CSRF는 meta 태그 + X-CSRF-TOKEN 헤더, 파괴 작업은 @RequireRecentAuth, 감사는 AuditEventService.record.

---

# Stage A — 관리자 콘솔

## A-1. 권한 모델

- **V10__admin_role.sql**: `ALTER TABLE users ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER';`
- `User.role`(USER/ADMIN enum 상수), `LoginUserDetailsService`: 항상 ROLE_USER + ADMIN이면 ROLE_ADMIN 추가.
- 부트스트랩: `taspa.admin.emails`(리스트, 기본 빈) — ApplicationRunner가 해당 이메일 계정을 ADMIN으로 승격(존재할 때만, 감사 기록). 첫 관리자 지정 SQL도 docs에 명시.
- SecurityConfig: `/admin/**`, `/api/admin/**` → `hasRole("ADMIN")`. **CSRF**: `/api/admin/**`는 CSRF 면제(`/api/**`)에서 제외(기존 `/api/sessions/**` 제외 패턴과 동일하게 AndRequestMatcher 확장) — 페이지 JS는 meta 태그 헤더로 전송.
- 모든 관리자 변경 작업: `@RequireRecentAuth` + 감사(`ADMIN_*` 타입, 대상 식별 포함).

## A-2. 화면/API (templates/admin/*.html + AdminController(페이지) + /api/admin/* JSON)

공통 레이아웃: auth.css 재사용 + `admin-card`(max-width 960px) 클래스 추가. 상단에 taspa 워드마크 + "관리 콘솔" + 계정/로그아웃.

1. **GET /admin** 대시보드: 사용자 수, 클라이언트 수, 활성 세션 수(SPRING_SESSION count), 최근 감사 이벤트 10건.
2. **클라이언트 관리** (`/admin/clients`):
   - 목록: `JdbcRegisteredClientRepository`에는 findAll이 없음 — `JdbcTemplate`로 `SELECT id FROM oauth2_registered_client` 후 `registeredClientRepository.findById(id)`로 복원(SAS 역직렬화 재사용).
   - 등록 폼: client_id, client_name, redirect URIs(여러 줄), post-logout redirect URIs, scopes 체크(openid/profile/email), grant types 체크(authorization_code/refresh_token/client_credentials), **클라이언트 유형**: 기밀(secret 발급) / **공개(public — SPA·모바일용, token_endpoint_auth_method=none + PKCE 강제)**. 기밀 secret은 SecureTokenGenerator 32B → **등록 응답에서 1회만 표시**("다시 볼 수 없습니다"), 저장은 PasswordEncoder.encode. 토큰 TTL(access 15분/refresh 30일, reuse=false), requireAuthorizationConsent(true), PKCE 항상 true.
   - 수정: client_name/redirect URIs/scopes만 (client_id·유형 불변). `save()`는 동일 id UPDATE.
   - 삭제: confirm → 관련 `oauth2_authorization`, `oauth2_authorization_consent` 행 함께 삭제(JdbcTemplate) 후 클라이언트 행 삭제. 감사 ADMIN_CLIENT_DELETED.
   - **secret 재발급** 버튼(기밀만): 새 secret 1회 표시.
3. **사용자 관리** (`/admin/users`):
   - 검색(이메일 부분일치, 최대 50) + 목록(이메일/이름/status/MFA/가입일).
   - 상세: 패스키 수, 연결 소셜, 활성 세션 수, 최근 감사 20건. 작업: **정지/해제**(정지 시 해당 사용자 전 세션+신뢰 기기 즉시 폐기 — SessionManagementService.revokeAll + TrustedDeviceService 재사용), **모든 세션 종료**, **역할 변경**(USER↔ADMIN).
   - **자기 보호 가드**: 자신을 정지하거나 자신의 ADMIN을 해제할 수 없음(409 + 메시지) — 관리자 잠금 방지.
4. **감사 로그** (`/admin/audit`): 최근 이벤트, type/이메일(→userId 해석) 필터, limit/offset 페이징(기본 50).

## A-3. 테스트

- 권한: 일반 사용자 /admin·/api/admin → 403(페이지는 접근 거부 처리), ADMIN → 200.
- 클라이언트 CRUD: 등록(secret 1회 노출·bcrypt 저장)→findByClientId 복원 일치, public 클라이언트(method none) 등록, 수정, 삭제 시 authorization 행 정리.
- 사용자 정지 → 그 사용자의 기존 세션 쿠키 즉시 무효 + 로그인 차단(기존 SUSPENDED 경로), 자기 정지/자기 강등 409.
- CSRF: 토큰 없는 /api/admin POST → 403.

---

# Stage B — 리스크 기반 인증

## B-1. 정책 (비밀번호 로그인 경로에만 적용 — 패스키는 피싱 내성으로 면제, 소셜은 provider 보증으로 면제. 근거 문서화)

`RiskEvaluationService.evaluate(request, user): RiskLevel(LOW/MEDIUM/HIGH)` — 신호(login_events + users 필드 재사용):
- `unseenDevice`: 최근 90일 login_events에 같은 (ip, ua_label) 없음 && 유효한 신뢰 기기 쿠키 없음
- `recentFailures`: 이 로그인 직전 user.failedLoginAttempts ≥ 3 (성공 기록으로 리셋되기 전 값 — 성공 핸들러 호출 순서상 리셋 전에 평가할 것, 순서 주석 필수)
- `rapidIpChange`: 직전 성공 login_event가 30분 이내 && 다른 IP

판정: HIGH = unseenDevice && (recentFailures || rapidIpChange), MEDIUM = unseenDevice 단독 또는 recentFailures 단독, LOW = 그 외.

적용(`LoginFlowSupport.requiredGate` 확장):
- **MFA 등록 사용자**: MEDIUM 이상이면 신뢰 기기 스킵을 무시하고 MFA 게이트 강제.
- **MFA 미등록 사용자**: MEDIUM 이상이면 **이메일 코드 챌린지** — `PendingAuthStage.RISK_CHALLENGE` 신설, EmailVerificationService 코드 재사용, 페이지 문구 "새로운 환경에서의 로그인이 감지되었습니다. 본인 확인을 위해 인증 코드를 보냈습니다". 단 EMAIL_VERIFICATION 게이트가 이미 발동하는 경우(미인증 계정)는 그 게이트가 이메일 소유를 증명하므로 중복 챌린지 금지.
- **HIGH**: 추가로 보안 경고 메일(기존 새 로그인 알림과 별도 문구 — "차단하려면 비밀번호를 변경하세요").
- 설정: `taspa.risk.enabled`(기본 true), `taspa.risk.unseen-window-days`(90). 비활성화 시 기존 동작 그대로.

## B-2. 테스트

1. MFA 미등록 + 신규 기기 → RISK_CHALLENGE 게이트(코드 통과 후 완전 인증), 알려진 기기 → 게이트 없음
2. MFA 등록 + 신뢰 기기 쿠키 + MEDIUM 신호 → MFA 강제(스킵 무시)
3. HIGH 신호 → 경고 메일 발송(mock 캡처)
4. 미인증 계정은 RISK_CHALLENGE 중복 발동 없음(EMAIL_VERIFICATION만)
5. risk.enabled=false → 전부 기존 동작
6. **게이트 보안 회귀**: RISK_CHALLENGE pending 상태에서 /oauth2/authorize가 code 미발급(기존 MFA 게이트 테스트 패턴)

---

# Stage C — 데모 클라이언트 + 연동 레시피

## C-1. examples/demo-client (신규 Gradle 모듈)

- settings.gradle.kts에 `include("examples:demo-client")`. Spring Boot web + thymeleaf + oauth2-client (+ 루트 공통 설정 상속).
- **provider 설정은 issuer-uri를 쓰지 말 것** — 기동 시 디스커버리 HTTP 호출로 taspa 미기동 상태에서 부팅·빌드가 깨진다. authorization-uri/token-uri/jwk-set-uri/user-info-uri(`http://localhost:9100/...`)를 명시(주석으로 이유 설명). registration: demo-app/demo-secret, redirect `{baseUrl}/login/oauth2/code/taspa`, scope openid profile email — dev 시딩된 demo-app과 정확히 일치.
- 페이지 2개: `/`(비로그인: "taspa로 로그인" 버튼 = /oauth2/authorization/taspa 링크), `/me`(인증 필수: OidcUser의 name/email/email_verified/sub + id_token 클레임 표 + 로그아웃 버튼 — 로그아웃은 로컬 세션만이며 taspa SSO 세션은 유지됨을 화면에 명시).
- 포트 8080. 테스트는 두지 말 것(컨텍스트 로드가 taspa 없이도 되지만 가치 낮음 — 빌드 대상 포함만 확인). bootJar 이름 demo-client.jar.
- README: examples/demo-client/README.md — 실행법(taspa 기동 → `./gradlew :examples:demo-client:bootRun`), 이 앱이 검증하는 것(OIDC code+PKCE, 동의, 클레임).

## C-2. e2e — sso-flow.spec.ts

전제(파일 주석): taspa(9100) + demo-client(8080) + postgres/mailpit 기동. 시나리오: 고유 계정 가입·이메일 인증(기존 헬퍼 재사용) → localhost:8080 접속 → "taspa로 로그인" → taspa 로그인(이미 세션 있으면 스킵될 수 있으므로 **새 컨텍스트에서 시작**) → 이메일/비밀번호 → 동의 화면 "허용" → localhost:8080/me 에 이메일 표시 확인. 두 번째 시나리오: 같은 컨텍스트에서 demo-client 로컬 로그아웃 → 다시 로그인 버튼 → **taspa 재로그인 없이**(SSO 세션) 즉시 /me 복귀.

## C-3. 연동 레시피 (docs/integration-guide.md 확충)

- Node.js/Express: openid-client v6 코드 조각(issuer discovery, code+PKCE).
- Next.js: Auth.js(next-auth) custom provider 설정 조각.
- SPA(공개 클라이언트): 관리 콘솔에서 public 클라이언트 등록 → oidc-client-ts 설정 조각, refresh rotation 주의.
- 각 조각은 taspa의 실제 엔드포인트/클레임(sub=이메일, name, email_verified)에 맞출 것. 과장 금지 — 검증 안 된 조각은 "예시" 명시.

## 문서 (일괄)

README(관리 콘솔·리스크 인증·examples 반영, 첫 관리자 지정법), architecture.md(§ 리스크 신호·판정표, 관리자 권한 모델, admin CSRF), CLAUDE.md(모듈 추가: admin/, risk/, examples/), docs/social-login-setup.md는 손대지 말 것.
