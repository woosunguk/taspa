# taspa

모든 프로젝트가 공용으로 사용하는 중앙 인증 시스템(IdP). 표준 OAuth2/OIDC Provider.
auth-playground의 코드 컨벤션과 보안 패턴을 계승한다.

## Tech Stack

- Kotlin 2.1.10 + Java 21 (toolchain)
- Spring Boot 3.4.4 + Spring Security 6.x (Kotlin DSL)
- Spring Authorization Server 1.4.x (`spring-boot-starter-oauth2-authorization-server`, Boot 관리 버전)
- 서버 렌더링 로그인 UI: Thymeleaf (`spring-boot-starter-thymeleaf`), 공통 `static/css/auth.css`
- 메일: `spring-boot-starter-mail` (dev/test는 Mailpit `localhost:1025`)
- MFA: `dev.samstevens.totp:totp:1.7.1` (TOTP + QR PNG → base64 data URI)
- Passkey: Spring Security 6.4 네이티브 `http.webAuthn { }` + `com.webauthn4j:webauthn4j-core:0.28.6.RELEASE`
  (Boot BOM 미관리 — 명시 필수), 벤더링 JS `static/js/webauthn.js` (spring-security-web 6.4.4, Apache-2.0)
- 소셜 로그인: `spring-boot-starter-oauth2-client` (구글=OIDC 내장 provider, 카카오/네이버=순수 OAuth2,
  프로그래매틱 조건부 등록 — `federation/SocialClientRegistrations`)
- PostgreSQL 16 + Spring Data JPA + Flyway
- API Docs: SpringDoc OpenAPI (Swagger UI) 2.8.5
- Test: Testcontainers, MockK 1.13.16, springmockk 4.0.2

## Commands

- 전체 빌드: `./gradlew build`
  (★JDK 는 `gradle/gradle-daemon-jvm.properties`(toolchainVersion=21)가 고정한다 — JAVA_HOME 무관.
  이 파일이 없으면 기본 JDK 가 25 인 환경에서 **새 데몬이 뜰 때** `What went wrong: 25.0.2` 로 깨진다.
  기존 데몬이 살아 있는 동안에는 멀쩡해서 원인 파악이 늦다.)
- 컴파일만(Docker 없이): `./gradlew build -x test`
- 서버 실행(dev, 데모 클라이언트 시딩): `./gradlew :server:bootRun --args='--spring.profiles.active=dev'`
- 서버 통합 테스트: `./gradlew :server:test` (Testcontainers → Docker 필요)
- 인프라: `docker compose up -d postgres mailpit` (Mailpit UI: http://localhost:8025 로 발송 메일 확인)

## Module Layout (Gradle 멀티모듈)

- `:server` — 중앙 인증 서버 (Spring Boot app, bootJar)
- `:client:spring-boot-starter` — 클라이언트용 Resource Server 자동설정 스타터 (java-library, bootJar 없음)
- `:examples:demo-client` — taspa 를 IdP 로 쓰는 OIDC 로그인 데모 앱 (Spring Boot app, 8080,
  bootJar `demo-client.jar`, 테스트 없음 — 동작 검증은 e2e `sso-flow.spec.ts`)
- `e2e` — Playwright e2e (baseURL http://localhost:9100; `sso-flow.spec.ts` 는 demo-client(8080) 기동도 전제)

## Code Conventions

- Package-by-feature: `account/`, `credential/`, `login/`, `mfa/`, `passkey/`, `federation/`, `enterprise/`(기업 SSO — SAML·조직 OIDC), `device/`(신뢰 기기), `stepup/`(재인증), `session/`(원격 세션 관리), `admin/`(관리 콘솔), `risk/`(리스크 기반 인증), `maintenance/`(스케줄 잡), `verification/`, `mail/`, `oidc/`, `token/`, `audit/`, `common/`, `config/`, `domain/`
- 엔티티는 `domain/{feature}/` (예: `domain/user/User.kt`, `domain/mfa/BackupCode.kt`, `domain/verification/EmailVerificationCode.kt`, `domain/credential/PasswordResetToken.kt`·`MagicLinkToken.kt`, `domain/passkey/PasskeyCredential.kt`, `domain/federation/FederatedIdentity.kt`(+`connection_id`), `domain/sso/SsoConnection.kt`·`SsoDomain.kt`, `domain/device/TrustedDevice.kt`, `domain/login/LoginEvent.kt`)
- DTO는 Kotlin data class, `{feature}/dto/`
- 생성자 주입(constructor injection)만 사용
- 설정은 `@ConfigurationProperties` (예: `taspa.password-policy`) 또는 `@Value`
- 예외는 `AuthException(ErrorCode)` → `GlobalExceptionHandler`에서 HTTP 상태 매핑.
  `ErrorCode` 추가 시 **두 곳이 함께** 바뀌어야 한다: (1) `handleAuthException` 의 `when` 이 exhaustive 라
  컴파일이 깨지고(의도된 강제 — 상태 결정을 빠뜨릴 수 없다), (2) `messages_ko/en.properties` 의
  `error.{NAME}` 키(`config/I18nMessagesTest` 가 대응을 강제).
- **Spring MVC 표준 예외는 전용 핸들러로 잡는다** — 캐치올(`Exception::class`)이 삼키면 클라이언트
  입력 오류가 500 + `log.error` 스택트레이스가 되어 **가짜 장애 알람**이 되고 진짜 500 을 가린다.
  타입 불일치·필수 파라미터/헤더 누락·`HandlerMethodValidationException`→400, 메서드 미지원→405(+`Allow`),
  Content-Type 미지원→415, `DateTimeException`→400. 로그는 `log.debug` —
  단 `DateTimeException` 만 `log.warn`(같은 타입으로 **DB/스키마 이상**도 오는데, 응답이 "입력값 오류"
  400 으로 위장되므로 흔적이 필요하다). 회귀: `common/exception/HttpContractIntegrationTest`.
- 과한 주석 금지. `TODO(Phase N)`은 단계 경계 표시에만 사용

## Security Filter Chains (`config/SecurityConfig.kt`)

- `@Order(1)` Authorization Server 체인: `OAuth2AuthorizationServerConfigurer.authorizationServer()` +
  `endpointsMatcher`로 OIDC/OAuth2 엔드포인트만 매칭, OIDC 활성화, `authorizationEndpoint.consentPage("/oauth2/consent")`,
  HTML 요청은 `/login`으로 리다이렉트.
- `@Order(2)` 기본 체인: 게이트/공개 페이지(`/login`, `/login/identifier|password|passkey|mfa|verify-email[/resend]|risk-challenge[/resend]`,
  `/login/webauthn`, `/webauthn/authenticate/options`, `/signup`, `/password-reset/**`, `/css/**`, `/js/**`,
  `/api/accounts/signup`, actuator/health, Swagger, `/error`) permitAll,
  `/account`·`/api/mfa/**` authenticated, 커스텀 `formLogin`(`loginProcessingUrl("/login/password")`,
  `MfaAwareAuthenticationSuccessHandler`/`LoginFailureHandler`),
  `webAuthn`(rpId/rpName/allowedOrigins = `taspa.webauthn.*`, `disableDefaultRegistrationPage(true)`,
  커스텀 저장소는 `passkey/Jpa*Repository` @Component 를 configurer 가 자동 사용). **STATELESS 아님**.
  `oauth2Login` 은 `ObjectProvider<ClientRegistrationRepository>` 가 있을 때만 조건부 적용
  (환경변수 `{GOOGLE|KAKAO|NAVER}_CLIENT_ID/SECRET` 쌍이 하나라도 있어야 빈 생성 —
  `federation/SocialClientRegistrations`). successHandler=`FederatedLoginSuccessHandler`,
  failureHandler → `/login?error=social`. permitAll 추가: `/oauth2/authorization/**`,
  `/login/oauth2/code/**`, `/login/link-confirm[/resend]`, `/login/social-email[/verify|/resend]`.
- `securityContext { securityContextRepository(...) }`로 `HttpSessionSecurityContextRepository`를 명시 고정하고,
  `RequestCache`(HttpSession) 빈을 등록 — pending↔완전 인증 전환의 대칭 저장/로딩을 보장.
- `csrf`는 `/api/**`에 대해 ignore하되 **`/api/sessions/**`·`/api/admin/**` 는 제외**(보호 유지 —
  페이지 JS 가 meta 태그 토큰을 X-CSRF-TOKEN 헤더로 전송). Thymeleaf 폼은 hidden `_csrf` 토큰을 직접 포함.
- `/admin/**`·`/api/admin/**` 는 `hasRole("ADMIN")` — 일반 사용자는 403.
- **미인증 응답은 요청 종류로 갈린다**(`exceptionHandling`, 기본 체인): API 경로는
  `ApiAuthenticationEntryPoint` 로 **401 JSON**(`{errorCode:"UNAUTHENTICATED", message}`), 그 외는
  기존 `/login` 302. 이 체인엔 리소스 서버가 없어 진입점이 formLogin 의 302 하나뿐이었고, 그 결과
  세션이 만료된 SPA 의 `fetch` 가 302 를 **투명하게 따라가 로그인 HTML 을 200 으로** 받았다 —
  `web/lib/api.ts` 의 401 처리(로그인 이동)와 "본문이 HTML 이면 미인증" 가드가 **둘 다 도달 불가**였고,
  사용자는 재로그인 안내 대신 영문 JSON 파서 오류를 봤다(admin 템플릿의 `api()` 헬퍼는 같은 이유로
  실패한 변경 작업을 성공으로 보고했다).
  ★**매핑 순서가 곧 의미다.** `DelegatingAuthenticationEntryPoint` 는 **첫 매핑을 폴백**으로 삼으므로
  API 매핑만 등록하면 그것이 폴백이 되어 화면 경로(`/account`·`/admin`)까지 401 이 된다 — MockMvc 는
  Accept 헤더를 보내지 않아 **통합테스트 24건이 한꺼번에 깨진다**. `AnyRequestMatcher` 매핑을
  두 번째로 반드시 함께 등록할 것. 회귀: `common/exception/HttpContractIntegrationTest`(401 7경로 +
  **302 대조군** — 둘이 짝을 이뤄야 의미가 있다).

## 패스키(WebAuthn)

- 로그인 통합: `POST /login/identifier` 에서 패스키 보유 시 `/login/passkey`("본인임을 확인") 우선,
  `/login` 하단에 usernameless "패스키로 로그인" 버튼, `/login/password` 에 "패스키 사용" 링크.
- 패스키 로그인은 MFA/이메일 게이트를 생략한다. 안전한 근거(불변식): 패스키 등록은 완전 인증
  세션에서만 가능하고 완전 인증은 이메일 인증을 전제하므로, 미인증 이메일 계정에 패스키가 존재할
  수 없다. (`docs/architecture.md` §8.3)
- 관리 API: `GET/PATCH/DELETE /api/passkeys[/{credentialId}]` — 소유권 검사, 타인 credential 은 404.
  어댑터 `JpaUserCredentialRepository.delete` 에도 소유권 가드(비소유 요청 무시 + WARN).
- 주의(6.4.4 실측): 익명 `POST /webauthn/authenticate/options` 에서 SS 가 "anonymousUser" 이름으로
  user entity `save()` 를 호출한다 — 어댑터는 users 행이 없는 이름을 영속화하지 않고 무시한다.
- rpId/allowedOrigins(`taspa.webauthn.*`) 불일치 시 조용히 실패 — 배포 시 반드시 재설정.

## 로그인 플로우 보안 불변식 (가장 중요)

- 부분 인증(MFA/이메일 인증 대기)은 **절대 `SecurityContext`에 넣지 않는다.** 넣으면 `/oauth2/authorize`가
  MFA 없이 code를 발급하는 취약점이 생긴다.
- `login/PendingAuth`(userId·stage·만료 10분 — 이메일 코드 게이트에 5분은 짧아 늘렸다)를 세션 속성으로만 보관. 성공 핸들러가 게이트 필요 시
  세션의 `SPRING_SECURITY_CONTEXT`를 제거하고 pending을 심는다. 게이트 통과 시에만
  `LoginFlowSupport.completeAuthentication`이 `changeSessionId()` 후 완전 인증을 세운다.
- 회귀 테스트: `login/GoogleLoginFlowIntegrationTest`의 MFA 게이트 케이스(이 설계의 존재 이유).
  자세한 시퀀스는 `docs/architecture.md` §7.

## 소셜 로그인 (구글·카카오·네이버)

- **소셜도 로컬 MFA 게이트 적용** (Auth0/Okta 모델 — 패스키만 게이트 생략). 게이트 판정은
  `LoginFlowSupport.requiredGate` 공용 함수로 수렴하고, 신뢰 기기 확인은 그 안의
  `TrustedDeviceService.validateAndRotate` 호출이다.
- **principal 통일**: `FederatedLoginSuccessHandler` 가 필터가 세션에 저장한 `OAuth2AuthenticationToken` 을
  반드시 걷어낸다 — 게이트 분기는 `startPending`, 완전 인증은 `completeAuthentication`(로컬 UserDetails).
  세션에는 항상 `UsernamePasswordAuthenticationToken`(name=이메일)만 남는다.
- **자동 연결은 이중 검증**: 공급자 이메일 검증 AND 로컬 이메일 검증일 때만(계정 선점 탈취 방지).
  그 외 → `/login/link-confirm`(SOCIAL_LINK, 이메일 코드로 소유 확인). 이메일 미제공(카카오 미동의) →
  `/login/social-email`(SOCIAL_EMAIL, `PendingAuth.userId=null` 허용 구간). 공급자 신원은
  `PendingSocialLink` 세션 속성.
- 네이버는 이메일 검증 플래그가 없어 항상 미검증 취급. 카카오는 `is_email_valid && is_email_verified`.
- **소셜 전용 계정**: `password_hash` NULL → `LoginUserDetailsService` 가 더미 bcrypt 해시로 폼 로그인을
  항상 실패시킨다(타이밍/메시지 비노출). 연결 해제는 잔여 수단(비밀번호/패스키/다른 소셜) ≥ 1 필요(409).
- 로그인된 세션의 연결 추가는 `/account/federations/link/{provider}` 가 `SocialLinkIntent` 세션 마커를
  심는다(성공 핸들러가 SecurityContext 로는 기존 로그인 여부를 알 수 없음 — 필터가 교체한 뒤라서).
- 콘솔 등록·환경변수: `docs/social-login-setup.md`. 정책 근거: `docs/architecture.md` §8.5.
- 회귀 테스트: `federation/SocialLoginFlowIntegrationTest` (WireMock 으로 공급자 스텁, 플로우 전체).

## 신뢰 기기 · 로그인 알림 · Step-up · 매직 링크 (Stage B)

- **신뢰 기기**(`device/TrustedDeviceService`, 쿠키 `taspa_td`): MFA 화면의 "30일 동안 묻지 않음"
  체크 시 발급(256-bit 토큰 → SHA-256 해시 저장). 검증 성공마다 **회전**(같은 행 해시 갱신), 만료는
  발급 기준 30일 **고정**(sliding 금지). 게이트 판정은 `LoginFlowSupport.requiredGate` 안의
  `validateAndRotate` 호출로 수렴(폼·소셜·이메일 인증 후·매직 링크 공통). 무효화 트리거:
  비밀번호 재설정(`PasswordResetService`)·MFA 해제/재등록(`MfaService`) → `revokeAll`.
  계정 페이지 "신뢰하는 기기" 섹션 + `/api/trusted-devices` (해제는 step-up 대상).
- **로그인 알림**(`login/LoginEventService`): 모든 완전 인증이 `login_events` 기록
  (method: password/mfa/passkey/social:{provider}/magic). 수렴점 3곳 —
  `completeAuthentication` + `MfaAwareAuthenticationSuccessHandler`(무게이트 비밀번호) +
  `PasskeyAuthenticationSuccessHandler`. 최근 30일 내 같은 (ip, ua 라벨) 이력이 없고 신뢰 기기도
  아니면 "새 로그인" 메일(이벤트 선기록으로 재발송 자연 억제, 발송 실패는 로그인에 비전파).
  ua 라벨은 `common/http/RequestClientInfo`(라이브러리 없는 요약 파서).
- **Step-up 재인증**(`stepup/`): 세션 속성 `TASPA_AUTH_TIME` — 모든 완전 인증 + `/reauth` 성공이 갱신.
  `@RequireRecentAuth`(클래스/메서드) + `RecentAuthInterceptor` — `taspa.step-up.max-age`(기본 10m,
  Duration) 초과 시 API 는 401 `REAUTH_REQUIRED`, HTML 은 `/reauth` 리다이렉트. 적용:
  `/api/mfa/**` 전체(클래스), `/api/passkeys` PATCH/DELETE, `DELETE /api/federations/{provider}`,
  신뢰 기기 해제. 필터 기반 `/webauthn/register[/options]` 는 `StepUpEnforcementFilter` 로 강제 —
  **ExceptionTranslationFilter 직후 배치**(WebAuthnRegistrationFilter 는 order 앵커가 아니라 직접 지정
  불가, 옵션 필터는 AuthorizationFilter 직전·등록 필터는 직후라 그 앞이 안전). 계정 페이지 JS 는
  `GET /api/reauth/check` 사전 점검 + 401 REAUTH_REQUIRED 시 `/reauth?continue=/account` 이동.
  `/reauth` 는 비밀번호 또는 패스키(기존 어서션 엔드포인트 재사용 — 성공 핸들러가 auth_time 갱신).
  continue 는 로컬 경로만 허용(open redirect 방지). 신뢰 기기 쿠키는 step-up 을 면제하지 않는다.
- **매직 링크**(`login/MagicLinkService`·`MagicLinkController`): password 페이지 → POST
  `/login/magic/request`(LOGIN_HINT 대상, 60초 제한, 응답은 계정 존재와 무관하게 동일).
  **GET `/login/magic?token=` 은 소비하지 않고** 확인 페이지만(스캐너 선클릭 방지) → POST 가
  소비(단일 사용 used_at, 15분 만료)·이메일 검증 마킹 후 **MFA 게이트 유지**(`requiredGate`) →
  `completeAuthentication`. 설정: `taspa.magic-link.*`.
- 회귀 테스트: `device/TrustedDeviceFlowIntegrationTest`, `stepup/StepUpIntegrationTest`,
  `login/MagicLinkIntegrationTest`, `login/LoginNotificationIntegrationTest`.

## 리스크 기반 인증 (`risk/`)

- **비밀번호 경로만** 적용 — 패스키(피싱 내성)·소셜(공급자 보증)·매직 링크(이메일 소유 증명)는 면제.
  근거·신호·판정표: `docs/architecture.md` §8.10.
- `RiskEvaluationService.evaluate` 신호(login_events·users 재사용): `unseenDevice`(90일 내 같은
  (ip, ua 라벨) 이력 없음 && 신뢰 기기 아님), `recentFailures`(리셋 전 failedLoginAttempts ≥ 3),
  `rapidIpChange`(30분 내 직전 성공 로그인이 다른 IP). HIGH = unseen && (failures || ipChange),
  MEDIUM = unseen 또는 failures 단독.
- **평가 순서 불변식**: `MfaAwareAuthenticationSuccessHandler` 에서 평가가
  `recordSuccessfulLogin`(실패 카운터 리셋)보다 **먼저** — recentFailures 는 리셋 전 값.
- 적용: `requiredGate(user, risk)` — MEDIUM 이상이면 MFA 사용자는 신뢰 기기 스킵 무시,
  MFA 미등록은 `RISK_CHALLENGE` 게이트(`/login/risk-challenge`, EmailVerificationService 코드 재사용).
  미인증 계정은 EMAIL_VERIFICATION 게이트가 이메일 소유를 증명하므로 **중복 챌린지 금지**
  (when 순서로 보장). HIGH 는 보안 경고 메일(`sendHighRiskLoginAlert`) 추가. 감사 `RISK_DETECTED`.
- 설정: `taspa.risk.enabled`(기본 true), `taspa.risk.unseen-window-days`(90).
  **테스트 기본은 비활성**(application-test.yml — unseenDevice 가 모든 첫 로그인에 발동하므로);
  리스크 테스트만 `@TestPropertySource` 로 켠다.
- 회귀 테스트: `risk/RiskBasedAuthIntegrationTest`(RISK_CHALLENGE pending 의 /oauth2/authorize
  미발급 포함), `risk/RiskDisabledIntegrationTest`.

## 관리자 콘솔 (`admin/`)

- **역할 모델**: `users.role`(V10, USER|ADMIN — `domain/user/UserRole`). `LoginUserDetailsService` 가
  항상 ROLE_USER + ADMIN 이면 ROLE_ADMIN 을 부여. 역할은 **로그인 시점에 세션에 굳는다** —
  승격은 재로그인부터 유효, 강등은 `AdminUserService.changeRole` 이 대상의 전 세션을 즉시 폐기.
- **부트스트랩**: `taspa.admin.emails`(기본 빈) — `AdminBootstrapConfig` ApplicationRunner 가 존재하는
  계정만 승격(감사 ADMIN_ROLE_GRANTED). 첫 관리자 지정 SQL 은 README 참고.
- **페이지**: `/admin`(대시보드), `/admin/clients`, `/admin/users`, `/admin/audit`
  (`templates/admin/*.html`, auth.css 의 `admin-card` 레이아웃, 계정 페이지와 같은 JS fetch 패턴).
- **API**: `/api/admin/clients`(CRUD + `POST {id}/secret` 재발급), `/api/admin/users`(검색/상세/정지/
  해제/세션 종료/역할), `/api/admin/audit`(type·email 필터 + limit/offset). 변경 작업은 전부
  `@RequireRecentAuth` + CSRF 헤더 + 감사(`ADMIN_*`, 대상 식별 포함).
- **클라이언트 등록**: 기밀=secret 발급(SecureTokenGenerator 32B, 응답 1회 노출, 저장은
  `{bcrypt}`+bcrypt — SAS 토큰 엔드포인트 기본 DelegatingPasswordEncoder 가 접두사를 요구),
  공개=method none + secret 없음. PKCE·동의 항상 강제, TTL access 15m/refresh 30d(reuse=false).
  목록은 `SELECT id` 후 `findById` 복원(JdbcRegisteredClientRepository 에 findAll 없음).
  삭제는 `oauth2_authorization`·`oauth2_authorization_consent` 행 동반 정리.
- **자기 보호 가드**: 자신 정지·자신의 ADMIN 해제는 409(`ADMIN_SELF_ACTION`) — 관리자 잠금 방지.
- 회귀 테스트: `admin/AdminConsoleIntegrationTest`.

## 데모 클라이언트 (`examples/demo-client/`)

- dev 시딩 `demo-app` 과 정확히 일치하는 최소 OIDC RP(페이지 `/`·`/me`). 실행:
  taspa(dev) 기동 → `./gradlew :examples:demo-client:bootRun`.
- **provider 는 issuer-uri 금지(명시 엔드포인트 4개)** — issuer-uri 는 기동 시 디스커버리 HTTP
  호출을 강제해 taspa 미기동이면 부팅이 깨진다(application.yml 주석). "정리" 명목으로
  issuer-uri 로 되돌리지 말 것.
- demo-app 은 `requireProofKey(true)` 시딩 — 기밀 클라이언트라 Spring Security 기본으론 PKCE 가
  안 붙으므로 `SecurityConfig` 가 `OAuth2AuthorizationRequestCustomizers.withPkce()` 를 명시.
- 로그아웃은 로컬 세션만(taspa SSO 세션 유지 — 화면에도 명시). 회귀: e2e `sso-flow.spec.ts`
  (첫 로그인·동의 + 로컬 로그아웃 후 무재인증 /me 복귀).

## 세션 (Spring Session JDBC) · 원격 세션 관리

- 세션은 DB 영속화(`spring-session-jdbc`, `V9__spring_session.sql` — `initialize-schema: never`).
  재시작에도 로그인 유지. 세션 쿠키명은 **`SESSION`**(JSESSIONID 아님 — logout `deleteCookies` 포함).
- **직렬화 불변식**: 세션 속성은 전부 Serializable 이어야 한다(JDK 직렬화 BYTEA). 유일한 블로커였던
  패스키 등록 옵션(`PublicKeyCredentialCreationOptions`)은 `passkey/CachedCreationOptionsRepository`
  (세션엔 랜덤 키 문자열만, 옵션은 인메모리 TTL 5분 — **단일 인스턴스 전제**)로 우회. 6.4 DSL 에
  주입 지점이 없어 `SecurityConfig.replaceCreationOptionsRepository` 가 빌드된 체인에서 옵션 필터를
  `PasskeyCreationOptionsFilter` 로 교체하고 등록 필터에 setter 로 주입한다. 새 세션 속성을 추가할 때
  반드시 Serializable 여부를 확인할 것.
- **원격 세션 관리**(`session/`): `PRINCIPAL_NAME`(=authentication.name, 이메일) 인덱스로
  `findByPrincipalName` 조회. pending 세션은 SecurityContext 가 없어 목록에 안 잡힘(불변식 유지).
  API: `GET /api/sessions`, `DELETE /api/sessions/{publicId}`, `POST /api/sessions/revoke-others`
  (폐기 계열은 step-up). **세션 ID 원문은 절대 노출 금지** — publicId(SHA-256 hex 앞 16자)만.
  세션 메타(IP/브라우저)는 `session/SessionMetadata` 가 완전 인증 수립 시점에 기록
  (`LoginFlowSupport.establishSecurityContext` + `PasskeyAuthenticationSuccessHandler`).
- 비밀번호 재설정 → 신뢰 기기 + **모든 세션** 폐기(`SessionManagementService.revokeAll`).
- **테스트 주의**: springSessionRepositoryFilter 때문에 MockMvc 의 `.session(MockHttpSession)` 공유
  패턴은 무력화된다(요청마다 새 세션). 통합 테스트는 `support/WebSession`(SESSION 쿠키 캡처·재전송,
  `IntegrationTestBase.webSession()`)을 쓸 것. 세션 속성 직접 조작은 `WebSession.setAttribute`
  (저장소 직접 쓰기), 속성만 심은 새 세션은 `WebSession.prime(...)`.
- 회귀 테스트: `session/SessionManagementIntegrationTest`, `passkey/PasskeyRegistrationSessionIntegrationTest`.

## Testing

- 통합 테스트는 `support/IntegrationTestBase`(`@SpringBootTest` + Testcontainers `postgres:16-alpine`) 상속
- `@ActiveProfiles("test")`, 데이터소스는 `@DynamicPropertySource`로 컨테이너 주입
- 단위 테스트는 MockK 사용. `JavaMailSender`는 `@MockkBean(relaxed = true)` + mockk 캡처로 발송 본문(코드/토큰) 추출
- `src/test/resources/application-test.yml`: `management.health.mail.enabled=false`
  (JavaMailSender를 mock으로 대체하면 actuator mail 헬스가 "Beans must not be empty"로 컨텍스트 로딩 실패)
- Testcontainers + 최신 Docker(colima 등, daemon API ≥ 1.44) 대응: `server/build.gradle.kts`의 test 태스크가
  `api.version`(docker-java) 하한과 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`를 설정. colima 소켓은
  `~/.testcontainers.properties`의 `docker.host`로 지정. → 특별한 환경변수 없이 `./gradlew build` 통과.
- **일부 로컬 colima 환경에서 `:server:test` 무한교착 시(테스트 워커 CPU 0% 좀비)**: 원인은 ryuk(리소스 리퍼)
  교착. **`TESTCONTAINERS_RYUK_DISABLED=true` 를 붙이면 해결**. 단 한 gradle invocation 에 **테스트 클래스 1개만**
  (`--tests "FQCN"`) 돌릴 것 — 여러 @SpringBootTest 를 한 JVM(전체 스위트·패키지 와일드카드)에서 돌리면 2번째
  컨텍스트에서 재교착. Docker 정상인데 멈추면 이 방법으로 클래스별 실행:
  `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :server:test --tests "com.taspa.server.org.OrgConsoleApiIntegrationTest"`.
  포그라운드 검증 대안: `./gradlew :server:testClasses`(컴파일만) + 서버 재기동 + 라이브 e2e.
- **~~`org/OrgInvitationServiceIntegrationTest` 는 재현성 있게 교착한다~~ → 해소됐다(과제 #76).**
  원인은 Testcontainers 환경이 아니라 **`OrgInvitationService.accept()` 의 자기 교착**이었다(FOR UPDATE 를
  쥔 채 REQUIRES_NEW 로 같은 행 UPDATE — 위 "비관적 잠금 불변식" 참고). 만료 판정을 잠금보다 앞으로 옮겨
  고쳤고 지금은 **26 tests 전부 통과, 클래스 전체 14초**다. 스위프 스크립트에서 **제외할 이유가 없다**.
  이 클래스에만 있는 커버리지(계정 열거 금지·재초대 멱등·주소별 쿨다운·만료 전이·역할 승격/비강등·
  expireOverdue·잠금 경합 3종)는 다른 클래스가 대신하지 못하므로 반드시 돌릴 것.
- ★**스위트가 도는 동안 server 소스를 고치지 마라.** 매 클래스마다 gradle 이 다시 컴파일하므로, 편집
  중이던 순간에 걸린 클래스가 **컴파일 실패로 FAIL 집계**된다(실제로 11건이 그렇게 잡혀 진짜 회귀와
  구별이 안 됐다 — 실패 목록이 알파벳 순으로 편집 시작 지점 이후 전부인 것이 유일한 단서였다).
  web·docs·e2e 는 별도 빌드라 그 시간에 손봐도 안전하다.
  ★**같은 이유로 스위프 중에는 `bootRun` 서버로 라이브 검증을 하지 마라.** bootRun 은
  `server/build/classes` 에서 직접 돌고 스위프가 그 디렉터리를 계속 다시 쓴다 — 클래스가 발밑에서
  갈아끼워져 **멀쩡한 엔드포인트가 무작위로 500** 을 낸다(실제로 `/api/admin/clients` 500 을 제품 결함으로
  30분 추적했다. 서버를 재기동하니 같은 DB·같은 요청이 200 이었다). 라이브 확인이 필요하면 스위프를
  끝내고 서버를 다시 띄울 것.
- **전체 스위트를 스크립트로 돌릴 때**: 클래스마다 데드라인을 두어라(하나가 교착하면 전체가 선다 — 실제로
  3시간을 잃었다). ★그 스크립트 안에서 **`./gradlew --stop` 을 쓰지 마라** — 모든 데몬을 멈추므로 `bootRun` 으로
  띄워 둔 개발 서버·demo-client 까지 함께 죽는다(교착 워커만 `pkill -f GradleWorkerMain` 으로 정리할 것).
  macOS 기본 환경에는 coreutils `timeout` 이 없다.

## Database

- Flyway SQL 마이그레이션: `server/src/main/resources/db/migration/`
  - `V1__create_users.sql` — users 테이블 (email_verified, status 포함)
  - `V2__create_oauth2_authorization_server_tables.sql` — SAS 표준 3 테이블 (PostgreSQL: blob→text)
  - `V3__google_style_auth.sql` — users ALTER(display_name/mfa_enabled/mfa_secret_encrypted) +
    backup_codes(`used_at`) + email_verification_codes(`code_hash` SHA-256 hex) + password_reset_tokens
  - `V4__passkeys.sql` — webauthn_user_entities(사용자 핸들, `external_id` base64url) +
    webauthn_credentials(CredentialRecord 14필드, 공개키/attestation BYTEA)
  - `V5__social_login.sql` — `users.password_hash` NULL 허용(소셜 전용 계정) +
    federated_identities(UNIQUE(provider, provider_user_id), `ON DELETE CASCADE`)
  - `V6__login_hardening.sql` — trusted_devices(`token_hash` UNIQUE) + login_events(user/시각 인덱스) +
    magic_link_tokens(`token_hash` UNIQUE, `used_at`)
  - `V7__email_case_insensitive.sql` — 이메일 대소문자 비구분
  - `V8__jwk_and_audit.sql` — jwk_keys(암호화 키 JSON, ACTIVE|RETIRED) + audit_events(JSON detail)
  - `V9__spring_session.sql` — Spring Session JDBC 3.4.2 공식 PostgreSQL 스키마(세션 영속화)
  - `V10__admin_role.sql` — `users.role`(USER|ADMIN, 기본 USER) — 관리자 콘솔 권한 모델
  - `V14__enterprise_sso.sql` — 기업 SSO: sso_connections(프로토콜·엔드포인트·인증서·정책, `oidc_client_secret` AES-GCM 암호문) + sso_domains(도메인 매핑, `verified`) + `federated_identities.connection_id`(`ON DELETE SET NULL`)
  - `V15__org_tenancy.sql` — 테넌시: organizations + org_memberships(`UNIQUE(org_id, user_id)`, MEMBER|ORG_ADMIN) + `sso_connections.org_id`(JIT 멤버십 앵커)
  - `V16__calendar.sql` — iCalendar 연동: calendar_feeds(업로드/구독, `last_sync_status`) + calendar_events(org 스코프, 조회 윈도우)
  - `V17__consumption_events.sql` — 소비 이벤트 seam(예측 정답데이터). append-only 로그, **멱등키 `UNIQUE(org_id, source, external_id)`(org 범위 — 조직 간 external_id 충돌·하이재킹 원천 차단)**, 부분 인덱스 `(org_id, occurred_at) WHERE status='CONFIRMED'`
  - `V18__org_timezone.sql` — `organizations.timezone`(기본 UTC). 소비 집계 date 버킷을 org-로컬 달력으로 앵커링(`CAST((occurred_at AT TIME ZONE 'UTC') AT TIME ZONE tz AS date)`) — UTC 절단으로 KST 아침이 전날로 새는 오귀속 방지
  - `V19__org_invitations.sql` — org_invitations(토큰 SHA-256 해시만·단일사용·만료, `(org_id,email)` PENDING 부분 유니크)
  - `V20__audit_org_id.sql` — `audit_events.org_id` + `(org_id, created_at)` 인덱스(org 스코프 활동로그)
  - `V21__org_structure.sql` — departments(자기참조 트리, 형제이름 부분유니크 root/sibling, parent CASCADE — 수동 삭제는 서비스가 자식 존재 시 차단) + sites(org 내 이름유니크·타임존) + `org_memberships.department_id/site_id`(ON DELETE SET NULL)
  - `V22__member_attributes_history.sql` — org_memberships HR 속성 5컬럼(employee_id·job_title·employment_type·hire_date·employment_status NOT NULL DEFAULT EMPLOYED) + org_membership_history(**append-only SCD 스냅샷 — dept/site FK 없음=이력 불변**, org CASCADE)
  - `V23__scim_external_id.sql` — `org_memberships.scim_external_id` + org 범위 부분 유니크
  - `V24__org_domains.sql` — org_domains(**검증 선점 정책**: 부분 유니크 `WHERE verified` + org 범위 유니크 — 미검증 등록은 전역 선점 불가, 검증=탈환) + `organizations.auto_join_enabled`(기본 false)
  - `V25__meal_ticket.sql` — merchants(선택적 `site_id` 연결)·meal_policies(org별 1식/12000원/월20만·끼니창 TIME 6컬럼)·meal_qr_tokens(해시만·60s)·meal_transactions(`auth_id` UNIQUE·POS 멱등 `UNIQUE(merchant_id,pos_txn_id)`) + `consumption_events.site_id`(예측 site 축)
  - `V26__invoices.sql` — invoices(`UNIQUE(org_id,period)`·집계창 스냅샷 period_start/end) + invoice_lines(이메일·부서명 **스냅샷**, dept FK 없음=청구서 불변)
  - `V27__iam_policies.sql` — 정책 RBAC: iam_policies(managed, `org_id` NULL=플랫폼, `system_managed`) + iam_inline_policies + iam_policy_attachments + iam_principal_groups + iam_group_members
  - `V28__org_domain_reverify_day.sql` — `org_domains.last_reverify_failure_on` — 재검증 실패 카운터를 **날짜 멱등**으로(무잠금 증가로 임계가 하루에 소진되던 버그)
  - `V29__merchant_members.sql` — merchant_members(`UNIQUE(merchant_id,user_id)`, role MERCHANT_ADMIN 고정) + `merchants.timezone`(기본 UTC — 가맹 그레인 하루 경계 앵커)
  - `V30__merchant_grain_indexes.sql` — 가맹 그레인 조회 인덱스(merchant × 시각) — 거래 로그·소비 집계용
  - `V32__meal_policy_overrides.sql` — 부서·사업장 **필드 단위** 재정의(null=상위값 물려받음). 축마다 별도 nullable FK + CASCADE(**죽은 노드가 계속 돈을 쓰지 않게** — 이력 V31 이 FK 를 안 거는 것과 정반대 이유), `effective_from/to` 를 처음부터 포함(기간 한정), 부분 유니크 `WHERE effective_from IS NULL AND effective_to IS NULL`(상시만 노드당 1행), CHECK 7종(one_scope·창 쌍 원자성·창 순서·금액 하한·기간·not_empty)
  - `V37__ledger.sql` — 이중부기 원장(ledger_entries + ledger_postings, **부호 있는 금액** 차변+/대변−) + 기존 APPROVED 거래 백필. ★멱등 UNIQUE 는 `NULLS NOT DISTINCT` 필수 — PG 기본값은 UNIQUE 에서 NULL 을 서로 다른 값으로 봐서 `(REDEEM, tx, NULL)` 중복이 **전부 통과**한다(원장이 부풀면 조직 청구가 조용히 두 배가 된다)
  - `V36__meal_refunds.sql` — 부분 환불(meal_refunds 원장 + `meal_transactions.refunded_minor`). ★거래의 `amount_minor`/`self_paid_minor` 를 **환불 후 현재값**으로 갱신 — 그래야 청구·월한도·자격조회의 `amount - self_paid` 집계 8곳이 **쿼리 무변경**으로 맞는다(원금 보존 방식이었다면 전부 고쳐야 하고 하나만 빠뜨려도 환불된 돈을 계속 청구한다). 원금 = `amount_minor + refunded_minor`
  - `V35__webauthn_registration_options.sql` — 패스키 등록 옵션 영속화(불투명 토큰 → 행). 세션에는 토큰만, 옵션은 DB
  - `V34__department_delegations.sql` — 부서 서브트리 위임(org_department_delegations, `UNIQUE(org_id,user_id)` — 한 사람은 한 조직에서 서브트리 하나만). 정책 문서를 저장하지 않고 전용 테이블을 두는 이유: IAM 콘솔에서 편집 가능한 자원이면 위임 경계가 정책 편집으로 넓혀진다
  - `V33__invitation_department.sql` — `org_invitations.department_id`(ON DELETE SET NULL). 그전까지 초대는 자유 텍스트 라벨만 날라, **초대로 입사한 사람은 부서 재정의를 못 받았다**(개발팀 18,000원을 설정해도 그 신입만 12,000원)
  - `V31__meal_policy_revisions.sql` — 식대 정책 변경 이력(append-only 금액축 원장. V22 가 인원축 정답데이터인 것과 대칭). **scope_id 에 FK 없음**(삭제된 부서의 과거 정책도 남아야 그 시절 청구서를 재현한다) + 이름 스냅샷. 신규 빈 테이블이라 일반 `CREATE INDEX`(V21 선례 — CONCURRENTLY 규약은 기존 쓰기 경로 테이블용)
- **인덱스 마이그레이션 규약**(V30 이후): 쓰기 경로 테이블에는 `CREATE INDEX CONCURRENTLY IF NOT EXISTS`
  + 같은 이름의 사이드카 `.conf` 에 `executeInTransaction=false`. 일반 `CREATE INDEX` 는 배포 중 승인·장부
  적재를 블록한다(2천만 행 실측: 빌드 6.8초 동안 INSERT 5.7초 대기). ★`spring.flyway.postgresql.
  transactional-lock: false` 가 **없으면 신규 DB 부팅이 그 마이그레이션에서 무한 정지**한다(재현 확인) —
  제거 금지. 실패 시 INVALID 인덱스가 남으므로 재실행 전 DROP 이 필요하다(README "운영 주의" 참조).
- Hibernate DDL: `validate` (자동 생성 금지) — 엔티티 컬럼/nullable을 마이그레이션과 정확히 일치시킬 것
- `open-in-view: false`

## OIDC / Token

- Issuer: `taspa.issuer-uri` (기본 http://localhost:9100)
- 저장소: `JdbcRegisteredClientRepository` + `JdbcOAuth2AuthorizationService` + `JdbcOAuth2AuthorizationConsentService`
- JWK: DB 영속화 + 회전(`token/JwkStorageService`, `maintenance/JwkRotationJob`), DB 기반 동적
  `JWKSource`(`token/JwkConfig.kt`). 유예 구간 다중 키 공존 시 `TokenCustomizerConfig` 의
  `jwsHeader.keyId(activeKid)` 고정이 발급을 지탱한다(제거 금지 — `docs/architecture.md` §3.1)
- 데모 클라이언트(`demo-app`) 시딩: `taspa.registered-clients.seed-demo-client=true` (dev 프로파일),
  `requireAuthorizationConsent(true)` → 구글식 동의 화면(`oidc/ConsentController`, `/oauth2/consent`)
- 클레임 커스터마이저(`token/TokenCustomizerConfig`): scope에 `email` 포함 시 `email`/`email_verified`,
  `profile` 포함 시 `name`(displayName ?: 이메일 로컬파트)을 id_token/access_token에 추가.
  `org.read` scope + 활성 멤버십이면 `org_id`/`orgs` 조직 클레임 발급(사용자 토큰). **M2M(client_credentials)**
  은 등록 클라이언트 설정 `settings.client.org-id`(`CLIENT_ORG_ID_SETTING`)에 org UUID 가 있으면 `org_id`
  클레임을 발급 — 생산자(결제·POS)가 org 결속 write 에 도달하는 경로. 클라이언트 등록 시
  `ClientRegisterRequest.orgId`(존재하는 org 만) 로 결속한다.

## 조직·캘린더 콘솔 + 소비 이벤트 seam (Phase 0ب)

- **`/admin/orgs`**(플랫폼 ADMIN) — 조직 CRUD·상태(ACTIVE↔SUSPENDED)·멤버 역할/제거, `AdminOrgController`(`/api/admin/orgs`) 소비. `organizations.timezone` 설정 가능(소비 집계 앵커).
- **`/admin/calendar`**(플랫폼 ADMIN) — 피드 등록(업로드/구독 URL, SSRF 방어 `IcsUrlSecurity`)·동기화·삭제/비활성, 이벤트 미리보기.
- **소비 이벤트(`consumption/`, `domain/consumption/`)** — 결제(생산자)와 예측(소비자)을 분리하는 append-only 정답 로그.
  - 적재 `POST /api/orgs/{orgId}/consumption-events` — **M2M `meal.consumption.write` + org 결속만**(사용자 토큰·세션 쓰기 거부). 멱등키 org 범위, 동시 재전송 UNIQUE 위반은 409(`GlobalExceptionHandler`)로 매핑, 갱신은 full-replace.
  - 집계 `GET .../aggregate?from&to&groupBy=date,meal_window[,menu]` — `meal.consumption.read`(org 결속) 또는 `meal.consumption.read.all`(신뢰 플랫폼 전조회). **집계 카운트만 노출**(개별 이벤트·user_sub 미노출). 창 폭·행 수 상한(자원고갈 방지). date 버킷은 org 타임존 앵커.

## 조직 자율 콘솔 (ORG_ADMIN self-service, `org/`)

플랫폼 ADMIN(`/admin/orgs`) 과 별개로, **조직관리자(ORG_ADMIN)가 자기 조직을 자율 관리**하는 세션 콘솔 `/console/orgs`(`OrgConsolePageController`, default 체인 `authenticated`). 계정 페이지는 `manageableOrgs` 가 true(사용자가 ≥1 org 의 활성 ORG_ADMIN)일 때만 진입 링크를 조건부 노출.

- **API 는 전부 `/api/orgs/**` 세션 체인(@Order(2))** — `/api/admin/**`(hasRole ADMIN)이 아니라 여기에 둬야 ORG_ADMIN 이 접근 가능. 인가는 `OrgInvitationController.authorize()` 패턴 복제: **위임 베어러(JwtAuthenticationToken) 거부 + 플랫폼ADMIN∨`organizationService.isOrgAdmin(orgId, userId)`**, 타 org 403. 상태변경은 `@RequireRecentAuth`(step-up) + audit(`ADMIN_ORG_*`, org_id 결속) + CSRF.
- **엔드포인트**: `GET /api/orgs/mine`(내가 ORG_ADMIN 인 활성 org 목록, `AdministeredOrgView`), `GET/PUT role/DELETE /api/orgs/{org}/members[/{userId}]`(멤버 목록·역할변경·제거, `guardLastAdmin`+`countEffectiveAdmins` 로 마지막 관리자 락아웃 방지), 초대 `POST/GET/DELETE /api/orgs/{org}/invitations[/{id}]` + `POST .../{id}/resend`(토큰 회전·쿨다운·`findByIdAndOrgIdForUpdate` 잠금), `GET /api/orgs/{org}/audit`(org 스코프 활동로그 — 플랫폼 관리자 행위자는 이메일 마스킹), `PUT /api/orgs/{org}`(프로필 이름·타임존만; **status·slug 는 이 경로로 불변** — `updateProfile` 전용 메서드+DTO 필드 부재 이중차단).
- **CSRF 불변식**: `/api/orgs/**` 세션 상태변경은 CSRF 강제(콘솔 JS `api()` 헬퍼가 X-CSRF-TOKEN 자동 첨부). **M2M 베어러 전용 `POST /api/orgs/*/consumption-events` 만 CSRF 면제**(`ignoringRequestMatchers`). GET 은 CsrfFilter 기본 제외.
- **audit org 결속**: `audit_events.org_id`(V20) 컬럼 + `AuditEventService.record(type, userId, orgId, detail)` 오버로드(기존 3-arg 무변경). org 관련 콜러만 orgId 전달 → org 스코프 활동로그가 `org_id` 정확 일치로 조회(타 org·전역 이벤트 격리).
- **조직 구조(1·2단계)**: 계층형 부서 `departments`(트리 — 생성 시 parent 를 FOR UPDATE 잠금(TOCTOU 방어), 자식 있는 부서 삭제 거부, `guardLastAdmin` 은 `countEffectiveAdmins`(멤버십 ACTIVE ∧ 유저 ACTIVE)로 판정) + 사업장 `sites`(타임존은 `normalizeTimezone` pg 검증) + 멤버 배정 `PUT .../members/{u}/assignment`(dept/site 가 같은 org 소속인지 검증). HR 속성 `PUT .../members/{u}/attributes`(nullable=null clear, employment_status 는 null 이면 유지) + `GET .../members/{u}/history`. **멤버십 SCD**: 모든 멤버십 변경(생성·역할·배정·속성·제거·JIT 실제 생성)은 `MembershipHistoryService.record` 훅으로 이력 append — repository 직접 쓰기로 우회 금지. 대시보드 `GET .../dashboard`(ACTIVE 필터 그룹쿼리·부서 트리워크 롤업). CSV 대량 초대 `POST .../invitations/bulk`(행별 `invite()` 프록시 경유 — 가드 자동, 행별 독립 트랜잭션, 200행/64KB 상한).
- **SCIM 2.0(`scim/`, `/scim/v2/**`)**: HR 시스템 프로비저닝. **전용 STATELESS 베어러 체인 @Order(0)**(disjoint 매처 — 기존 체인 무변경), 인가 = client_credentials + scope `org.scim` + **토큰 org_id 클레임 = 테넌트 앵커**(URL 아님, SUSPENDED org fail-closed). **모든 효과는 그 org 멤버십에 한정** — `active=false`→SUSPENDED+TERMINATED, DELETE→removeMember(이력 REMOVED), **users 테이블 비파괴**(displayName 등 전역 속성은 POST 신규 생성 시에만 설정 — org 경계 이탈 금지). 계정 생성 = 소셜 전용 패턴(password NULL·email_verified=false·메일 무발송). 신규 멤버 역할 MEMBER 고정(SCIM 으로 ORG_ADMIN 부여 불가). Azure AD 호환 PATCH(Operations 대문자·"True"/"False" 문자열·pathless value·미지원 path 는 무시). SCIM 오류는 `ScimErrorHandler`(scim 패키지 한정 advice) — GlobalExceptionHandler 무영향. Groups 는 미구현(후속).
- **demo-app 시더 주의**: `RegisteredClientConfig` self-heal 이 redirect/post-logout 을 8080+**8081(ALT_PORT 상수)** 로 시딩하며 healthy 판정에 8081 포함 — 8081 환경(e2e DEMO_BASE)이 재시딩으로 깨지지 않게 유지. DB 행 수동 수정 금지(시더가 관리).
- **★비관적 잠금 불변식(초대 수락에서 실제로 터진 결함 — 되돌리지 말 것)**:
  ① **비관적 잠금(FOR UPDATE)을 쥔 채 `REQUIRES_NEW` 로 같은 행을 건드리지 마라.** 안쪽은 **다른 커넥션**
  이라 바깥이 쥔 행 잠금을 기다리고, 바깥은 안쪽 JDBC 호출의 반환을 기다린다. **PostgreSQL 교착 탐지기는
  발동하지 않는다** — DB 가 보기에 대기자는 안쪽 하나뿐이고 바깥은 DB 잠금이 아니라 애플리케이션 스레드를
  기다려서 잠금 그래프에 순환이 없다. 운영에서는 만료 초대 링크 클릭마다 톰캣 워커와 **커넥션 2개**가
  영구히 묶였다(풀 20 → 클릭 10번에 전멸, 워커 200 보다 20배 먼저 죽는다). 그래서
  **만료 판정·전이는 잠금 획득보다 먼저** 한다(`OrgInvitationService.accept`).
  ② 그 순서를 뒤집으면서 생긴 TOCTOU 창의 대가: **잠금 후 재확인은 "잠금이 그 트랜잭션의 첫 적재"일 때만
  권위가 있다.** 같은 트랜잭션에서 그 행을 **엔티티로** 먼저 읽으면 Hibernate 1차 캐시가 FOR UPDATE 결과로
  필드를 다시 채우지 않아(`@Version` 도 없어 감지 불가) 재확인이 **옛 값**으로 이뤄진다 — 소비/취소된 초대가
  통과했다(실측 확인). 그래서 사전 판정은 **인터페이스 프로젝션**(`findAcceptGateByTokenHash`)으로 한다.
  같은 형태("무잠금 읽기 → 같은 행 FOR UPDATE")가 `MealRedeemService`·`MealQrService`·
  `OrganizationService.guardLastAdmin` 에도 있는데, 그쪽은 잠금 후 판정이 전부 **집계 쿼리**(항상 DB 재조회)
  라서 안전하다 — 거기에 "엔티티 필드로 재확인"을 추가하는 순간 같은 결함이 된다.
  ③ 회귀 테스트는 **시간 제한**이 필수다(`assertTimeoutPreemptively`). 교착은 예외가 아니라 무한 대기라,
  제한이 없으면 CI 가 멈출 뿐 결함이 드러나지 않는다.
- 회귀 테스트: `org/OrgConsoleApiIntegrationTest`(멤버·프로필·멤버십뷰), `org/OrgInvitationApiIntegrationTest`(초대·재발송·bulk), `org/OrgAuditApiIntegrationTest`(활동로그 격리), `org/OrgStructureApiIntegrationTest`(부서·사업장·배정), `org/OrgMemberAttributesIntegrationTest`(속성·이력), `org/OrgDashboardIntegrationTest`(집계), `scim/ScimApiIntegrationTest`(SCIM 전체). **CSRF 를 새 상태변경 경로에 켤 때 그 경로의 "미인증→401" 테스트에는 `.with(csrf())` 를 추가**(없으면 CsrfFilter 가 먼저 403 → 실패). **@RequireRecentAuth 경로의 베어러 거부 테스트는 401(REAUTH_REQUIRED) 단언**(step-up 인터셉터가 authorize() 403 보다 먼저 돎). 테스트 setUp 에서 departments 는 `deleteAllInBatch()`(자기참조 CASCADE + 행단위 deleteAll = StaleState 예외).

## 식대 플랫폼 코어 (결제 폐쇄루프 · 예측 · 정산 — 실 자금이동 제외)

**절대 경계: 실제 자금이동·PG·세금계산서 발행 연동 없음**(장부·집계·청구서 확정까지만 — 실 지불은 별도 승인 후). 설계 근거 docs/design/meal-platform-system-design.md·meal-forecast-system-design.md.

- **도메인 자동 가입(`org/OrgDomain*`, V24)**: 회사 이메일 가입자를 조직에 자동 소속. 보안 앵커 4종(타협 불가): ①검증 도메인만(DNS TXT `_taspa-verify.<domain>`=`taspa-verify=<token>` 자가검증(JNDI, 트랜잭션 밖) 또는 플랫폼 ADMIN force-verify) ②공용 메일 도메인 21종 하드 차단 ③org별 `auto_join_enabled` opt-in 기본 OFF(전용 PUT /api/orgs/{org}/auto-join — updateProfile 에 혼입 금지) ④역할 MEMBER 고정. 트리거는 **email_verified=true 전이 6지점**(가입코드·매직링크·소셜·SAML·이메일변경) → `OrgAutoJoinService.evaluate`(afterCommit 지연·예외 비전파·ensureJitMembership 멱등). 검증 선점: 미검증 등록은 전역 선점 못 하고 검증 성공이 타 org 미검증 클레임을 삭제(탈환).
- **식권 QR 폐쇄루프(`meal/`, V25)**: 후불형 — 선불충전 없음, QR=**불투명 핸들**(SecureTokenGenerator, 해시만 저장, TTL 60s, 발급 쿨다운 10s→429, FOR UPDATE 단일사용). redeem 은 `/api/merchant/**` 전용 STATELESS 베어러 체인(@Order(-1)) — scope `meal.redeem` + **CLIENT_MERCHANT_ID_SETTING 클레임 결속**(org-id 패턴 복제, AdminClientService merchantId). 정책 평가(org 타임존): 끼니창(meal_policies TIME)·일 횟수(APPROVED만)·per-meal/월 cap 초과분은 거절이 아니라 **self_paid 분리 승인**(조직부담=amount−self_paid). **한도 판정은 org_memberships 행 FOR UPDATE 로 직렬화**(동시 redeem 우회 차단 — 잠금 순서: 토큰행→멤버십행, QR 발급 쿨다운도 동일 앵커). 거래+소비적재는 단일 트랜잭션: source=`payment`·external_id=`auth_id`·site=merchant.site(**거래 org 소속 site 만** — 교차 테넌트 오귀속 차단). void 는 자기 merchant 만(404)·멱등·같은 external_id VOIDED 재적재(full-replace, 원본 site 보존)로 집계 자동 제외. POS 멱등(merchant,posTxnId) 재전송은 기존 결과 재반환(토큰 검증보다 선확인). 외부 M2M 소비적재는 source=payment 사용 불가(장부 예약). UI: `/meal`(조직선택·QR 렌더 `static/js/qrcode.js` 벤더링(무의존 ISO 18004)·서버 Date 앵커 카운트다운·사용내역), 가맹 관리 `/admin/merchants`.
- **식수예측 P0(`forecast/`, 스키마 무변경)**: 그레인 (끼니×site×일), 온디맨드 계산. 방법: 전주 동요일 × 재실보정(`countActiveEmployedAsOf` — 이력 DISTINCT ON 복원, **비율 0.5~2.0 밖이면 보정 생략**·SEASONAL_NAIVE 강등), 폴백 SEASONAL_NAIVE_ADJUSTED→SEASONAL_NAIVE→FOUR_WEEK_AVG→**NO_DATA(null≠0)**. 백테스트는 전일 끝(D-1) 이력로 시점 재현(미래정보 누수 금지)·MAPE(0 실측 분모 제외+제외수 노출)/WAPE/bias·이력 preload 1회. 집계 상한 도달 시 fail-loud(VALIDATION_ERROR). 인가: 세션(플랫폼∨ORG_ADMIN) 또는 M2M `meal.forecast.read`+org 결속, 사용자 토큰은 **sub UUID 형태면 무조건 거부**(fail-closed). GET /api/orgs/{org}/forecast·/backtest.
  - **캘린더 휴일 인지(`forecast/HolidayCalendar`)**: 휴일 = `all_day=true` ∧ (피드 `type='HOLIDAY'` ∨ `category='HOLIDAY'`) — **요약 텍스트로 의미를 추측하지 않는다**(조직관리자의 명시 선언만 믿는다). ★all-day 는 instant 가 아니라 **달력 날짜**라 파서가 UTC 로 고정한 벽시계를 **UTC 그대로** 읽는다 — org 타임존으로 변환하면 UTC 서편 존에서 하루 밀린다(소비 실적의 org-타임존 앵커와 **의도적으로 다르다**). DTEND 배타(연휴 N일). 용도 2가지: ①타깃일 휴일이면 셀에 `holiday`/`holidayName` 노출하되 **predicted 를 0 으로 단정하지 않는다**(당직 식사) ②basis 후보(D-7·14·21·28)는 **휴일 여부가 타깃과 같을 때만** 채택(대칭 — 휴일→평일은 과소, 평일→휴일은 과대). 전부 걸리면 NO_DATA(제외 수는 `basis.excludedHolidayBasis`). **캘린더 없는 조직은 인덱스가 비어 도입 전과 동작이 정확히 동일**(회귀 방지의 핵심). 백테스트도 같은 판정을 쓰지만 `calendar_events` 가 sweep-recreate 되어 "그때 알고 있었나"를 재현 못 하므로 **"캘린더가 처음부터 있었다면"의 상한**이다.
  - **가맹 그레인(`MerchantForecastService`)에는 휴일을 적용하지 않는다(의도적)**: 한 매장이 여러 조직 손님을 받아 "어느 조직의 휴일인가"가 결정 불가고(타임존을 빌릴 수 없는 것과 같은 이유), 고객사 휴일의 영향은 이미 그 매장 실적에 들어 있어 겹치면 이중 보정이다. 미해결: 전 고객사가 쉬는 연휴가 basis 면 과소예측 — 매장 자체 휴무 캘린더가 더 정확한 신호다.
- **부분 환불(`MealRedeemService.refund`, V36)**: 전액 취소(void)만 있던 자리에 "식사는 했는데 금액이
  틀렸다"를 넣었다(void 후 재승인은 손님이 QR 을 다시 받아야 하고 — 토큰 단일 사용 — 장부에 거래가 둘로 남는다).
  - ★**분담 재계산이 전부**다. 환불 후 금액으로 조직/개인 분담을 **승인과 같은 식**으로 다시 계산하고
    차액을 각자에게 돌린다. 조직 12,000+개인 3,000 에서 3,000 환불 → 남은 12,000은 한도 안이라 조직이
    전액 부담하고 **개인이 3,000을 돌려받는다**. "개인부담 먼저" 같은 규칙을 따로 두면 승인 로직이
    바뀔 때 어긋난다. 월 한도는 **자기 거래를 뺀** 누계로 계산해야 "쓴 만큼 다시 쓸 수 있다"가 성립.
    환불이 조직 부담을 **늘리는** 일은 없게 `coerceAtMost(previousOrgPaid)`.
  - 전액 환불은 void 와 같은 상태로 수렴(VOIDED + 소비 이벤트 제외). 부분 환불은 소비 이벤트를
    건드리지 않는다 — 이벤트는 **인분 수**를 나르고 식사는 실제로 있었다.
  - 멱등키 `pos_refund_id` 는 **단말이 만들어 재시도에 재사용**(승인의 posTxnId 와 같은 이유). POS UI 도
    (거래, 금액) 쌍에 묶는다 — 금액을 고쳐 재시도할 때 같은 키면 옛 환불이 재반환된다.
  - ★**분담 재계산에는 "재계산" 만으로 부족하다**(넣었다가 잡은 결함). 승인과 환불 **사이에 정책이
    하향**되면(1식/월 한도 인하) 이상값이 승인 당시보다 작아지고 그 차이가 통째로 조직 환불로 잡혀
    **직원 부담이 늘어난다**(실측: 3,000→9,000, selfRefunded=−6,000 → DB CHECK 위반 500).
    그래서 이상값에서 곧장 분담을 정하지 않고 **환불 배분**을 먼저 정한다 —
    `orgRefunded = (previousOrgPaid − idealOrgShare).coerceIn(0, 환불액)` 한 줄이
    "두 주머니 모두 줄기만 한다 + 두 환불의 합 = 요청액" 두 불변식을 동시에 강제한다.
    개인 부담도 **차감으로** 구한다(재계산 아님 — 코드 모양에서 불변식이 읽히게).
  - IAM `meal:RefundRedeem`(void 와 분리 — 나중에 "환불은 되지만 취소는 안 되는" 단말을 두려면 action 이
    갈려 있어야 한다) + 플랫폼 관리자 제외 목록.
  - ★**환불 가시성**(`MealTransactionView`·`MerchantTransactionView` 의 `refundedMinor`/`originalAmountMinor`/
    주머니별 환불액): 환불이 `amount_minor`·`self_paid_minor` 를 **소급 변경**하는 위 설계의 대가다 —
    노출하지 않으면 15,000원을 쓰고 3,000원을 돌려받은 사람의 이력에 **12,000원만** 남고, 화면은 영수증과
    다른 숫자를 설명하지 못한다(사용자는 기록이 틀렸다고 의심하고, 매장은 자기 POS 기록과 대사할 수 없다).
    한동안 환불은 **POS 응답 순간 말고는 어디에도 보이지 않았다**. 누계 집계는 `MealRefundRepository.
    summarizeByTransactionIds`(배치 — N+1 방지, `refundedMinor > 0` 인 거래만 질의). 주머니별 합이 총액과
    같은 것은 행 단위 CHECK(`ck_meal_refund_split`)가 보장하므로 셋을 함께 실어도 어긋날 수 없다.
    화면: `/meal` 사용내역, `/merchant/{id}/transactions`("환불" 열 + 원금·횟수 + 합계 카드).
    ★**프런트가 서버의 규칙을 그대로 따라야 한다**(적대 리뷰에서 잡힌 결함 2종 — 서버에서 고친 함정을
    화면이 재현하고 있었다):
    ① `web/app/merchant/_lib/summarize.ts` — **금액축은 APPROVED 만, 환불축은 상태 무관**. VOIDED 에서
    곧바로 early-return 하면 전액 환불이 합계에서 사라져, 요약 카드는 "환불 3,000원"인데 **바로 아래 표엔
    10,000원 환불 행이 보이고** 정산 명세는 13,000원을 말한다(한 앱이 같은 기간에 세 가지를 주장).
    환불이 전액 환불 하나뿐이면 카드 자체가 사라진다.
    ② `web/app/meal/amounts.ts` — 환불 표시를 "취소가 아닐 때"로 묶으면 **전액 환불이 "0원 · 취소됨"**
    한 줄로 남는다(전액 환불은 amountMinor·selfPaidMinor 를 0 으로 되돌린다). 돈이 실제로 오간 거래일수록
    숫자가 더 사라지는 셈. 머리 금액은 환불이 있으면 **원금**을 쓴다.
    두 규칙 모두 **순수 함수로 떼어** 회귀 테스트로 고정했다(JSX 안에 있으면 테스트를 쓸 수 없고, 결함이
    정확히 그렇게 생겼다): `app/merchant/_lib/summarize.test.ts`(3)·`app/meal/amounts.test.ts`(4) —
    각각 **환불 없는 대조군** 포함.
  - 회귀: `meal/MealRefundIntegrationTest`(19 — 분담 방향 3종·다중 환불·전액 수렴·멱등·초과 거절·타 가맹 404
    + **이력 노출 + 환불 없는 대조군**), `meal/MerchantConsoleIntegrationTest`(11 — 매장 대사용 노출 포함).
- ★**테스트 픽스처의 시각 함정**(`meal/MealPolicyTimeRoundTripTest`): 끼니창의 "하루 끝"을
  `23:59:59.999999`(마이크로초 최대)로 두면 **DB 왕복에서 00:00 으로 넘어간다**. 그러면 저녁 창이
  `[16:00, 00:00)` 이 돼 반개구간 판정이 항상 거짓이고, **UTC 16시~24시(KST 새벽 1~9시)에만** 결제 관련
  테스트가 무더기로 깨진다(실측: MealRedeemIntegrationTest 9/16 실패). 낮에는 초록불이라 발견이 늦는다.
  안전한 값은 **밀리초 최대값**이고, 추측하지 않도록 그 테스트가 왕복을 측정해 `END_OF_DAY` 상수로 제공한다
  (meal 픽스처 4곳이 이 상수를 쓴다).
- **이중부기 원장 + 3-way 대사(`billing/LedgerService`·`ReconciliationService`, V37)**
  - ★**왜 지금 필요해졌나**: V36(부분 환불)이 `meal_transactions.amount_minor` 를 **변경 가능**하게 만들었다.
    집계 쿼리를 안 고치려는 선택이었고 목적은 달성했지만, 대가로 거래 테이블이 "지금 얼마인가"만 말하고
    **"그때 얼마였나"를 못 말하게** 됐다(6월분 청구 후 7월에 환불하면 6월 거래 금액이 소급 변경).
    원장은 사건을 지우지 않고 **반대 분개를 덧붙이므로** 어느 시점으로 잘라도 그때 잔액이 나온다.
  - 계정 2개: `ORG_RECEIVABLE`(조직이 우리에게, 차변 +) / `MERCHANT_PAYABLE`(우리가 가맹에, 대변 −).
    **직원 개인부담은 분개하지 않는다** — 계산대에서 직접 오가는 우리 돈이 아니다(넣으면 대차가 안 맞고,
    맞추려고 가공 계정을 만들면 원장이 거짓말을 한다). 사건 메타 `self_paid_minor` 로만 남긴다.
  - ★**결제와 같은 트랜잭션**에서 기록한다. 나중에 채우면 그 사이 원장과 장부가 어긋나고, 그걸 잡으려고
    만든 대사가 자기 지연 때문에 매번 경보를 울린다(경보 피로 → 진짜 불일치가 묻힌다).
  - **3-way 대사**(`GET /api/orgs/{org}/reconciliation?period=`): 금액축 원장↔장부, 건수축 장부↔소비이벤트,
    + 대차평형 위반 사건 수 + 통과지점 검증(미수금+미지급금=0). 실 자금이동이 없어 은행 명세는 없지만,
    이 세 기록은 **서로 독립적으로 쓰이고 이 축에서 실제로 세 번 갈라졌다**(소비 site 드리프트·void 후
    stale 확정·환불 소급 변경). IAM `billing:Reconcile`(ReadInvoice 재사용 금지 — 청구서는 조직에 보내는
    문서이고 대사는 시스템 건강 상태다). UI 는 청구서 탭 "정합성 대사".
  - ★**반대 분개의 `occurred_at` 은 취소·환불한 날이 아니라 원 거래 승인 시각**이다(넣었다가 잡은 결함).
    장부는 V36 때문에 원 거래의 달에서 금액이 **소급 감소**하는데 원장만 다음 달에 달면 **두 달이 모두**
    어긋나 대사가 매달 허위 경보를 낸다. 기록 시각은 `created_at` 에 남는다.
    ★**승인·취소·환불이 같은 달인 테스트만으로는 이 결함이 보이지 않는다** — 월 경계를 넘는 케이스가
    회귀 테스트에 있어야 한다(그래서 `MealTransaction.approvedAt` 을 `var` 로 열어 두었다. 프로덕션은
    바꾸지 않는다). 재현 시 **소비 이벤트도 함께** 옮길 것 — 셋은 같은 approvedAt 을 쓴다.
  - ★회귀 테스트에 **오염 주입 2건**이 있다(`billing/LedgerReconciliationIntegrationTest`, 10). 대사는
    정상 흐름에서 항상 0 이라 "0 이 나온다"만 확인하면 **원장을 안 쓰고 상수 0 을 돌려줘도 통과**한다 —
    일부러 분개를 어긋내고 소비 이벤트를 지워 잡히는지를 함께 본다.
  - ★★**전역 순회 3종의 공통 불변식 — 실패를 삼키되 반드시 센다**(2차 적대 리뷰에서 잡힌 결함 3종).
    `reconcileAll`·`platformPayables`·`listUnfinalized` 는 항목 하나의 실패가 전체를 깨지 않도록 예외를
    가두는데, **세지 않고 가두면** 그 항목이 결과에서 조용히 사라지고 화면은 "N개 전부 확인했고 이상 없음"
    이라고 단언한다 — 하필 **문제가 있는 항목일수록 사라진다**(대사에 실패한 조직이 `unbalanced` 에서
    빠지고, 집계에 실패한 매장의 지급액이 총액에서 빠져 자금 담당이 적은 금액으로 이체를 계획한다).
    경보·계획 화면의 목적이 그 지점에서 정확히 뒤집힌다. 그래서 세 DTO 모두 **`failed`** 를 함께
    내려보내고(WARN 로그 동반), 화면이 그 값을 **별도 문장**으로 말한다. 세 값의 뜻이 다르다:
    `scanned`=시도한 수, `skipped`=상한에 걸려 시도조차 못 한 수, `failed`=시도했으나 결과를 모르는 수.
    새 전역 순회를 추가하면 셋을 함께 낼 것. (`platformPayables` 는 매장 부재 NOT_FOUND 만 정상 스킵으로
    구분한다 — 그건 "지워진 매장"이지 실패가 아니다.)
  - ★**전역 대사·전역 지급의 트랜잭션 경계**(적대 리뷰에서 잡힌 결함 2종, 되돌리지 말 것):
    `reconcileAll`·`platformPayables` 에는 **`@Transactional` 이 없고**, 항목마다
    `ObjectProvider<Self>` 로 **프록시를 거쳐** 짧은 readOnly 트랜잭션을 연다. 이유 둘:
    ① 바깥을 트랜잭션으로 감싸면, 조직/매장 하나가 사라져 던진 예외가 다른 빈의 참여 트랜잭션
    (`OrganizationService.requireOrg`)을 지나며 **공유 트랜잭션을 rollback-only 로 표시**한다 —
    `runCatching` 이 삼켜 루프는 계속되지만 커밋 때 `UnexpectedRollbackException` 으로 **요청 전체가
    500**이 된다("건너뛴다"고 쓴 코드가 실제로는 못 건너뛴다). ② 읽기 하나로 500개를 훑는 동안
    커넥션 1개를 계속 쥔다(커넥션 풀이 워커 풀보다 20배 먼저 죽는다). **자기 호출은 프록시를 타지
    않는다** — `self.getObject().reconcile(...)` 형태를 유지할 것.
  - **전역 대사**(`GET /api/admin/reconciliation?period=`, `reconcileAll`, 화면 `/admin/reconciliation`):
    조직별 대사는 그 조직 관리자가 자기 청구서를 의심할 때 연다. 이건 반대 방향 — 조직이 100개면 하나씩
    열어 볼 수 없고, 열어 보지 않으면 아무도 모른다. org 마다 달 경계가 달라(타임존) 한 번의 group-by 로
    끝낼 수 없으므로 **UTC ±1일로 넓힌 창**에서 `orgIdsWithActivity` 로 활동 조직만 추린 뒤 조직별로 정확히
    계산한다(비용이 전 조직 수가 아니라 실제 사용량에 비례). 기본 기간은 **지난달**(이번 달은 아직 쌓이는
    중). IAM `platform:ReadReconciliation`.
    ★**`scanned`·`skipped` 를 함께 내려보낸다** — "불일치 0건"은 다 봤는데 정상이었다는 뜻일 수도, **아무것도
    안 봤다**는 뜻일 수도 있다. 둘을 구분하지 않으면 이 화면은 안심시키는 역할만 하고 경보 역할을 못 한다.
    화면도 그 둘을 다른 문장으로 말한다.
    ★후보 합집합의 **순서가 의미를 갖는다**: 상한에 걸려 잘릴 때 **장부에만 있는 조직**(= 원장이 빠진
    조직, 이 합집합을 도입한 바로 그 대상)이 먼저 잘리면 확장이 무의미해진다. 그래서 그쪽을 앞에 둔다.
    ★**후보는 원장 ∪ 장부**다(`orgIdsWithActivity` 두 개의 합집합). 원장에서만 뽑으면 **원장이 통째로
    빠진 조직**이 표본에서 구조적으로 제외돼, 조직별 대사는 amountDrift 로 정확히 잡는데 전역 화면만
    "이상 없음"이라 답하고 그 조직을 못 봤다는 흔적조차 없다 — 대사가 잡으라고 만들어진 실패 형태가
    경보 화면에서만 안 보인다(프로브 재현: 옛 코드에서 scanned 가 2 대신 1). **소비이벤트는 후보에 넣지
    않는다**(의도적): 결제 외 생산자도 소비를 적재하므로 결제 없는 조직은 countDrift 가 정상적으로 0 이
    아니고, 후보에 넣으면 정상 상태가 매달 경보로 올라와 진짜 불일치를 가린다.
    ★회귀(`admin/AdminReconciliationIntegrationTest`, 8)의 "정상 조직" 픽스처는 거래·소비이벤트·원장을
    **셋 다** 만들어야 한다. 원장만 넣으면 그 자체가 불일치라 정상 픽스처가 정상이 아니다(처음에 빠뜨려
    깨졌다 — 대사가 예민하게 작동한다는 증거이기도 하다).
  - **전역 지급 현황**(`GET /api/admin/payables[/csv]?period=`, `MerchantConsoleService.platformPayables`,
    화면 `/admin/payables`): 매장별 정산은 사장이 자기 몫을 확인할 때 연다. 이건 반대 방향 — 매장이
    100개면 하나씩 열어 볼 수 없고, 열어 보지 않으면 이번 달 총 지급액을 아무도 모른다.
    ★**매장별 `settlement()` 을 그대로 재사용**한다. 여기서 따로 집계하면 운영자가 보는 총액과 매장이
    자기 화면에서 보는 금액이 갈릴 수 있고, 그 순간 정산 분쟁을 조정할 근거가 사라진다(회귀 테스트가
    두 응답의 금액 일치를 직접 단언한다). 대상은 `merchantIdsWithActivity` 로 좁혀 비용이 실제 사용량에
    비례한다. 기본 기간은 **이번 달**(지급은 진행 중인 달을 계획한다 — 전역 대사의 지난달과 다르다).
    ★**관리 표면은 핸들러당 action 이 유일**해야 해서(`PlatformSurfaceValidator`) 조회와 CSV 가
    `platform:ReadPayables`/`platform:ExportPayables` 로 갈린다 — 처음에 같은 action 을 달았다가 기동
    검증에 걸렸다(설계대로 작동). 파일은 조직 밖으로 나가므로 화면 조회보다 큰 능력이라 분리가 옳다.
    회귀: `admin/AdminPayablesIntegrationTest`(9 — 개인부담 미포함·**매장 화면과 금액 일치**·취소 제외·
    scanned 구분·CSV 권한).
- **정산 청구서(`billing/`, V26)**: generate(org, "YYYY-MM") — org 타임존 월경계, APPROVED 만, 조직부담 합계, 사용자 라인+부서명 스냅샷, DRAFT 는 FOR UPDATE full-replace 재생성, FINALIZED 존재 시 409. finalize 는 **스냅샷 창으로 재검산 → 불일치 INVOICE_STALE 409**(void 후 stale 확정 차단, 재생성→확정으로 복구). org 타임존 변경 시 인접 FINALIZED 청구서의 스냅샷 창과 정합(확정 창 불변, 미확정 쪽이 양보). 콘솔 UI: 조직 상세 "식수 예측"·"청구서" 섹션.
- **월 청구서 자동 생성(`maintenance/InvoiceGenerationJob`)**: 그전까지 청구서는 조직관리자가 콘솔에서
  누를 때만 만들어졌다 — 아무도 누르지 않으면 그 달 청구가 **조용히 없던 일**이 된다(전역 대사도 청구서
  유무는 보지 않는다. 매출 누락은 알람이 울리지 않는 종류의 사고다). 매일 도는 잡이 org **로컬** 달력의
  직전 달 DRAFT 를 만든다.
  - ★**DRAFT 까지만** — 확정은 문서를 불변으로 굳히므로 사람의 일로 남긴다.
  - ★**없을 때만 만든다.** 규칙 하나가 멱등성과 "사람이 재생성한 초안을 덮지 않는다"를 동시에 준다
    (덮으면 관리자가 확인한 숫자가 다음 날 새벽에 조용히 바뀐다). 회귀 프로브로 재현 확인: 이 가드를
    빼면 10,000 초안이 25,000 으로 덮인다.
  - **유예 `taspa.billing.auto-generate-grace-days`(기본 2)** — 경계 직전 거래의 POS 재전송·취소가 며칠
    늦게 도착하는데, "없을 때만" 규칙 때문에 첫 초안이 옛 숫자로 굳는다.
    ★이 판정은 **`billing/InvoiceGraceWindow` 한 곳에만** 둔다(잡과 미확정 현황이 공유). 두 곳에 각각
    두면 매달 1~2일에 현황 화면이 "아직 만들 시점이 아닌" 조직 **전부**를 `MISSING`(= 시스템이 못
    만들었다) 적색 경보로 띄운다 — 조직 100개면 100줄이 거짓 경보이고 그 속에서 진짜 실패가 묻힌다
    (자동 생성 잡이 막으려던 상태 그대로다). 그래서 상태가 셋이다: `MISSING` > `DRAFT` > `PENDING`(유예).
    회귀: `billing/InvoiceGraceWindowTest`(4, **오늘 날짜에서 역산**해 날짜 독립). 통합 테스트는
    `@TestPropertySource` 로 유예를 0 에 고정한다 — 안 그러면 **매달 1·2일에만 깨진다**.
  - ★활동 확인(`InvoiceService.hasBillableActivity`)이 **생성보다 먼저**다. 만들었다 지우면 존재하지 않는
    청구서의 `INVOICE_GENERATED` 가 조직 활동로그에 남아 관리자가 사라진 청구서를 찾는다.
  - `generate(actorId=null)` = 시스템 생성(가짜 행위자를 지어내지 않는다 — `OrgAuditService` 는 null 행위자를
    이미 email=null 로 처리한다). 조직 하나가 터져도 나머지를 계속한다(누락 방지가 이 잡의 존재 이유).
  - ★**다중 인스턴스 경합은 실패가 아니다.** "이미 있으면 건너뛴다" 가드는 잠금 없는 check-then-act 라
    두 인스턴스가 같은 새벽에 동시에 통과할 수 있다. 정합성은 `UNIQUE(org_id, period)` 가 지키고
    (한쪽만 INSERT 성공 → **중복 청구서도 중복 메일도 없다**), 진 쪽은 `DataIntegrityViolationException`
    을 **INFO 로 skipped 처리**한다. 이걸 캐치올에 맡겨 ERROR 로 남기면 매달 1일 새벽마다 허위 경보가
    뜨고 경보 피로가 진짜 실패를 가린다.
  - ★**테스트 프로파일에서는 꺼 둔다**(`application-test.yml`). `@EnableScheduling` 이 켜져 있어 크론
    시각(기본 03:20)에 테스트가 돌면 잡이 청구서를 만들어 `InvoiceIntegrationTest` 의 "청구서 행은 하나만"
    단언을 무너뜨린다 — **하루 중 특정 시각에만 깨지는** 형태라 발견이 늦다(끼니창 END_OF_DAY 함정과 같다).
  - **조직관리자 알림**(`MailService.sendInvoiceDraftReady`): 이 메일이 없으면 자동 생성은 **아무 일도 하지
    않은 것과 같다** — 청구서는 만들어졌지만 아는 사람이 없어 관리자는 여전히 스스로 기억해야 한다.
    **확정 링크가 아니라 콘솔 링크**를 보낸다(확정은 숫자를 확인한 뒤의 판단이라 메일 한 번의 클릭으로
    일어나서는 안 된다). 발송 실패는 비전파 — 메일 장애로 청구 생성이 롤백되면 알림 문제를 고치려다
    청구 자체를 잃는다. 링크 base 는 **초대 메일과 같은 키**(`taspa.org-invitation.base-url`) — 새 키를
    만들면 한쪽만 고친 순간 메일이 조용히 잘못된 호스트를 가리킨다.
  - ★**미확정 추적**(`InvoiceService.listUnfinalized`, `GET /api/admin/invoices/unfinalized?period=`,
    관리 대시보드 "미확정 청구서" 섹션): 자동 생성 루프의 **마지막 구멍**. 초안을 만들고 메일까지 보내도
    조직이 확정하지 않으면 그 달은 **끝내 청구되지 않고** 아무도 모른다 — 이 잡이 막으려던 사고가 한 단계
    뒤에서 반복된다. 두 상태를 **구별**하는 것이 이 표면의 값어치다: `DRAFT`(사람이 안 눌렀다) vs
    `MISSING`(그 달 청구할 거래가 있는데 청구서 행 자체가 없다 — 잡이 실패했거나 유예 중). 뒤쪽이 더
    심각해서 목록에서 먼저 온다. 대상은 **거래가 있었던 조직**만(활동 없으면 청구서가 없는 게 정상이고,
    정상 상태가 경보에 섞이면 진짜 누락이 묻힌다). `scanned` 를 함께 낸다(전역 대사·지급 현황과 같은 이유).
    IAM `platform:ReadUnfinalizedInvoices`. 회귀: `admin/AdminUnfinalizedInvoicesIntegrationTest`(6).
  - 회귀: `maintenance/InvoiceGenerationJobIntegrationTest`(10 — 잡 인스턴스를 **직접 생성**한다. 유예 판정이
    "오늘이 며칠인가"에 달려 있어 프로퍼티로 고정하면 한 달의 특정 날짜에만 통과하는 테스트가 된다).
- **식대 정책 편집(`meal/MealPolicyService`·`MealPolicyController`, V31)**: 그전까지 12,000원/1일1회/월20만원은
  `MealPolicy` 엔티티의 Kotlin 기본값으로 사실상 하드코딩이었다(정책 행을 `save` 하는 프로덕션 코드가 **0곳**).
  `/api/orgs/{org}/meal-policy` GET/PUT/`/history` — ORG_ADMIN 세션 체인, step-up+CSRF+감사(`ORG_MEAL_POLICY_UPDATED`).
  - **해석기 seam(`MealPolicyResolver`)**: 승인(redeem)과 자격 조회(직원 화면)가 **같은 입구**를 쓴다 —
    각자 `orElseGet{ MealPolicy(...) }` 폴백을 갖고 있던 것을 여기로 모았다. 부서 재정의가 붙어도
    "화면은 15,000원인데 계산대는 12,000원"이 구조적으로 불가능하다. ★불변식: 이 클래스는 **FOR UPDATE 도
    REQUIRES_NEW 도 열지 않고 캐시하지 않는다**(redeem 이 멤버십 잠금을 쥔 채 호출한다).
  - ★**해석 결과는 엔티티가 아니라 값**(`EffectiveMealPolicy` data class, `MealPolicyValues` 인터페이스).
    상속이 붙으면 "조직 기본을 읽어 부서값으로 덮는" 코드가 자연스럽게 나오는데, 그 대상이 엔티티였다면
    dirty checking 이 커밋 시점에 **조직 기본 정책 행을 부서값으로 UPDATE** 한다(한 부서 재정의가 전사를 갈아엎음).
    `MealPolicyCalculus` 6함수도 `MealPolicyValues` 를 받게 넓혔다(로직 무변경).
  - IAM: `meal:ReadPolicy`/`UpdatePolicy`/`ReadPolicyHistory` 를 `ORG_ADMIN_ACTIONS` 에 **명시 열거**
    (★`meal:*` 와일드카드 금지 — 그 순간 조직관리자가 `meal:Redeem`·`VoidRedeem` 을 얻는다).
    TRN `trn:taspa:meal:{org}:policy` 는 4번째 세그먼트 규약을 지켜 `orgSegmentOf` 무변경.
  - 검증: 자정 넘는 창은 **명시 거절**(`openWindows` 의 `start<end` 필터가 조용히 지워 "저장했는데 점심이
    사라짐"이 된다) · 창 겹침 거절 · 배포 상한 `taspa.meal.policy-ceiling.*`(자릿수 오타 = 회사 지출).
- **부서·사업장 재정의(`MealPolicyOverrideService`, V32)**: 우선순위 **부서(가장 가까운 조상부터) > 사업장 >
  조직 > 코드 기본값**, 같은 노드에서는 기간 한정이 상시를 이긴다. 병합은 **필드 단위**라 점심시간만
  재정의한 부서도 조직의 한도 인상을 자동으로 물려받는다(전체 복제였다면 부서마다 손으로 따라 고쳐야 하고
  빠뜨린 부서가 옛 값으로 남는데 아무도 모른다). `GET/POST/PUT/DELETE /api/orgs/{org}/meal-policy/overrides`
  + `GET .../preview`(해석기를 그대로 통과 — 미리보기가 자체 로직을 가지면 화면과 계산대가 갈라진다).
  - ★**질의 수가 조직도 깊이에 비례하지 않는다** — 재정의는 org 단위로 한 번에 읽어 메모리에서 병합한다.
    트리를 타고 올라가며 노드마다 질의하면 그 왕복이 전부 redeem 의 **멤버십 잠금 구간 안**에서 일어난다.
  - `parent_id` 는 DB 가 트리를 강제하지 않으므로 조상 탐색에 방문 집합을 둔다(순환 = 승인 정지).
  - IAM `meal:ReadPolicyOverrides`/`ManagePolicyOverrides` 도 `ORG_ADMIN_ACTIONS` 에 명시 열거.
- **Stage 2b — 구조 배정 연결(`org/DepartmentBinder`, V33)**: `org_memberships.department`(자유 라벨)와
  `department_id`(FK)는 다른 축이고 **정책 재정의가 보는 것은 FK 쪽뿐**이다. 초대·CSV·SCIM 이 라벨만
  채우던 동안 부서 재정의가 신입에게 적용되지 않았다. binder 는 명시 id 우선, 없으면 이름으로 해석하되
  ★**이름이 정확히 하나일 때만** 잇는다 — 부서 이름은 형제 사이에서만 유일해서(`uq_dept_sibling`) 트리에
  같은 이름이 둘 있을 수 있고, 아무 쪽이나 고르면 절반의 확률로 **틀린 부서의 예산**을 쓴다.
  `upsertMember` 는 **이미 배정된 사람의 부서를 덮지 않는다**(초대 수락이 정책을 조용히 바꾸면 안 된다).
  ★`invite()` 의 `departmentId` 는 **마지막 파라미터**다 — `inviterId` 앞에 두면 둘 다 `UUID?` 라
  기존 위치 인자 호출이 조용히 잘못 바인딩되고 컴파일러가 잡아 주지 않는다.
  - UI `/console/{org}/meal-policy`(9번째 탭) — ★**네이티브 `min`/`max` 를 쓰지 않고 `noValidate` + 우리
    문구로 검증**한다. `type=number max=` 는 제출을 막지만 말풍선이 브라우저 언어의 영문이라 한국어 화면에
    영문 오류가 뜨고 서버의 한국어 400 과 문구가 갈린다.
- 회귀 테스트: `meal/MealQrIntegrationTest`·`MealRedeemIntegrationTest`, `forecast/ForecastIntegrationTest`, `billing/InvoiceIntegrationTest`, `org/OrgDomainAutoJoinIntegrationTest`(DnsTxtResolver 는 @MockkBean), `meal/MealPolicyResolverTest`(4, 순수 단위 — 값 타입 불변식 + **재정의 없으면 도입 전과 동일**)·`meal/MealPolicyApiIntegrationTest`(16 — 편집이 승인 판정에 즉시 반영되는지가 핵심 단언)·`meal/MealPolicyOverrideIntegrationTest`(15 — 상속 우선순위·필드 단위 병합·기간 한정·부서 삭제 CASCADE)·`org/DepartmentBindingIntegrationTest`(8 — 이름 모호성).

## 가맹점(식당) 신원 — 사람 콘솔 · POS 단말 (V29·V30)

**중심 불변식: 조회하는 사람과 결제하는 기계를 분리한다.** 가맹 관리자는 로그인해서 자기 매장의 식수
로그·예측을 보고, 승인·취소는 여전히 M2M(merchant_id 클레임) 전용이다 — 계정 탈취가 곧 무단 결제가
되지 않는다. `MerchantConsoleController` 에는 승인 경로가 아예 없다.

- **`merchant_members`(V29)**: 가맹의 사람 신원(role=MERCHANT_ADMIN 고정, status ACTIVE|SUSPENDED).
  조직 멤버십과 독립(식당 사장이 어느 회사 직원일 필요 없음). `merchants.timezone`(V29) 이 이 매장의
  하루 경계 앵커다 — 조직 타임존을 빌릴 수 없다(한 매장이 여러 조직 손님을 받으므로 어느 조직 기준인지
  정할 수 없다). **부여는 플랫폼 ADMIN 전용**(`/admin/merchants` 담당자 관리) — 매장 신원이 스스로
  증식하지 못하게 발급을 플랫폼이 쥔다.
- **접근 조건 3중**(두 곳이 정확히 같은 집합이어야 한다 — 따로 고치지 말 것): 멤버십 ACTIVE ∧ 역할
  MERCHANT_ADMIN ∧ 매장 ACTIVE. `MerchantConsoleService.isActiveMerchantAdmin`(인가 입력) 과
  `listMyMerchants`(매장 선택 목록)가 같은 조건을 쓴다 — 어긋나면 "목록에 보이는데 열면 403" 이 된다.
  손상된 enum 값은 `statusEnum()`/`roleEnum()` 이 **닫히는 쪽**으로 낙하한다(500 아님).
- **경로 분리 주의**: `/api/merchant/**` 는 M2M STATELESS 체인(@Order(-1))이라 그 아래엔 세션 엔드포인트를
  둘 수 없다. 사람 콘솔은 **`/api/merchant-console/**`**(기본 체인) — `mine`·`{id}/transactions`·
  `{id}/forecast`·`{id}/backtest`. 인가는 엔진 단독(`decideSession(merchantAdminOf=경로 merchantId)`,
  `merchant:Read*` + merchant TRN), 위임 베어러는 엔진 밖 하드 게이트로 거부.
- **가맹 그레인 예측(`MerchantForecastService`)**: 원천은 장부가 아니라 **소비 이벤트**(취소는 CONFIRMED
  필터로 자동 제외, 수량 축 존재, 결제 외 생산자 포함, 조직 예측과 같은 원천). 집계는 org 를 **넘어**
  합산한다 — 매장이 준비할 실제 인분 수이기 때문. 테넌시 앵커가 org_id 가 아니라 merchant_id 다.
- **예측의 실적 인덱스는 완결일(어제)까지로 자른다**(`forecast()` 경로 한정, 가맹·조직 양쪽). 오늘은
  아직 적재 중인 부분값이라, 자르지 않으면 기본 7일 지평의 T+7 셀이 "전주 동요일 실적"으로 **오늘의
  미완결 값**을 쓰고 조회 시각마다 숫자가 흔들린다(화면은 확정값처럼 표시한다). 대가로 그 셀은
  FOUR_WEEK_AVG 등으로 강등되는데, **부분값을 SEASONAL_NAIVE 로 위장하는 것보다 정직하다.**
  `backtest()` 는 대상이 항상 D-1 이하라 구조적으로 무관 — 즉 **백테스트 지표는 이 강등을 반영하지
  못한다**(예측 품질을 백테스트만으로 판단하지 말 것).
- ★그래서 **공개 소비 적재 API 는 `merchantId` 를 아예 받지 않는다**(400). 본문 값을 믿으면 A 조직
  생산자가 B 매장 UUID 로 적재해 그 매장 예측을 부풀릴 수 있다. 가맹 귀속은 redeem/void 트랜잭션에서만
  선다. `siteId` 도 경로 org 소속인지 검증한다(`ConsumptionEventService`). `source=payment` 예약과 함께
  **적재 경계 가드 3종**으로 묶어 볼 것.
- **손님 개인정보 비노출**: 매장에 나가는 응답 DTO(`MerchantTransactionView`)에 손님 userId·이메일·이름의
  **자리 자체가 없다**. 매장이 필요한 건 인분 수와 정산 대사용 조직·금액이다.
- **월 정산 명세**(`GET /api/merchant-console/{id}/settlement?period=`, 화면 `/merchant/{id}/settlement`):
  그전까지 매장은 "이번 달 얼마 받나"를 식수 로그를 눈으로 더해 알아야 했다.
  ★**지급 대상은 조직부담 합(`payableMinor`)뿐이다.** 개인부담은 손님이 계산대에서 **이미 냈으므로**
  더하면 매장이 받을 돈을 두 배로 기대한다 — DTO 에 총결제액(gross) 필드를 두지 않고 두 주머니를 갈라서만
  내려보내는 이유다(화면도 나란히 두되 더하지 않는다).
  창은 **매장 타임존** 월 경계다(조직 달력을 빌릴 수 없다 — 예측 그레인과 같은 이유). 그래서 매장 정산
  합계와 조직 청구서 합계는 **경계일 거래만큼 정당하게 다르다**; 결함이 아니라 두 문서가 다른 달력을
  쓴다는 사실이고, 응답의 periodStart/End 가 실제 창을 드러낸다. 기본 기간은 **이번 달**(전역 대사가
  지난달인 것과 다르다 — 매장은 "지금까지 얼마 쌓였나"를 묻는다).
  ★**금액축은 APPROVED 만, 환불축은 상태 무관**이다(자체 검토에서 잡은 결함). 전액 환불은 거래를
  **VOIDED 로 수렴**시키므로 둘을 같은 `status='APPROVED'` 필터로 묶으면 전액 환불이 명세에서 통째로
  사라진다 — 10,000 전액 + 3,000 부분 환불이 있던 달에 매장은 "환불 3,000원"을 본다(프로브로 재현 확인).
  `refunded_minor` 는 **환불만이 올린다**(순수 void 는 0 그대로)라 상태를 가리지 않고 더하는 것이 정확히
  "되돌아간 금액"이다. 대신 취소만 있던 조직 줄은 서비스가 걸러낸다(전부 0 인 줄은 소음).
  IAM `merchant:ReadSettlement` — 거래 로그와 **action 을 나눠 둔다**(나중에 "로그는 보되 금액 합계는 못
  보는" 매장 직원 역할을 두려면 지금 갈라 놔야 한다. 합친 뒤 나누는 것은 발급된 정책을 전부 손대는 일이다).
  **실 자금이동은 없다** — 명세는 집계이고 지급은 별도 절차임을 화면이 명시한다.
- **POS 단말(`web/app/pos`, BFF `web/app/api/pos/*`)**: 브라우저에는 자격증명이 없고 Next 서버만
  client secret 을 쥔다. 여기에 더해 **단말 인증**이 필수다(`web/lib/pos-session.ts`) — `/pos` 와 손님용
  `/meal` 은 같은 앱이라, 이 관문이 없으면 손님이 자기 QR 을 `/api/pos/redeem` 에 직접 보내 매장에 가지도
  않고 회사 예산으로 결제를 성립시키고 authId 로 자기 결제를 취소할 수 있다("매장 네트워크 제한"은 성립
  하지 않는다 — 손님도 QR 을 받으려면 같은 앱에 닿아야 한다). 직원이 `POS_TERMINAL_KEY` 를 한 번 입력하면
  서명된 httpOnly 쿠키가 심기고(유휴 7일·절대 90일, `/api/pos/status` 가 슬라이딩 갱신), 그 쿠키가 있는
  요청만 중계된다. 환경변수·회수 절차는 `web/.env.example`.
  - **쿠키 서명 키는 `POS_SESSION_SECRET` 으로 `POS_TERMINAL_KEY` 를 HMAC 한 값**이다. 이 파생이
    "키 교체 = 전 단말 즉시 무효"를 **구조적으로** 보장한다 — 예전엔 등록 키를 비교에만 쓰고 서명에는
    넣지 않아, 문서가 지시하는 유출 대응(키 교체)이 실제로는 아무 일도 하지 않았다.
  - ★**등록 처리 순서: 계수 → 검증 → (틀렸을 때만) 지연·429.** 계수가 검증보다 앞서야 실패 응답이
    무료 추측 기회가 되지 않고, **검증이 지연·거절보다 앞서야 맞는 키가 압력과 무관하게 통과**한다.
    검증 전에 429 를 반환하면 공격자가 틀린 키를 초당 한 번만 보내도 **맞는 키를 가진 매장의 등록을
    무기한 봉쇄**할 수 있다(제한이 지키려던 정상 영업을 제한이 깨뜨린다). 순서를 바꾸지 말 것.
  - 발신지 식별(`X-Forwarded-For`)은 **`POS_TRUSTED_PROXY` 로 신뢰를 선언했을 때만**. 기본은 위조 불가능한
    단일 버킷 — 헤더를 믿으면 공격자는 회전으로 우회하고 정상 매장만 잠긴다(실측된 과거 동작).
  - 등록 키는 길이·문자 다양성·추정 엔트로피 검사를 통과해야 하며, 미달이면 **등록 기능 자체를 끈다**
    (fail-closed). 스로틀은 감속일 뿐 실제 방어선은 키 엔트로피다. 검사는 기동이 아니라 첫 요청 시점이라,
    약한 키로도 배포는 성공한다 — 배포 후 `/pos` 를 한 번 열어 확인할 것(화면이 사유를 알려 준다).
- ★**dev 에서 `/pos` 를 쓰려면 가맹 결속 M2M 클라이언트가 필요하다**(`web/.env.local` 의 `POS_CLIENT_ID`/
  `POS_CLIENT_SECRET`). 비어 있으면 화면이 "단말 미등록 + 설정해야 할 환경변수"를 정직하게 알려 주는데,
  그걸 코드 결함으로 오해하기 쉽다. 발급은 `/admin/clients`(또는 `POST /api/admin/clients` 에
  `merchantId` 포함, secret 은 **응답 1회만** 노출) → `.env.local` 기록 → **웹 dev 재기동**(Next 는
  `.env.local` 을 기동 시 읽는다). 확인은 `GET /api/pos/status` 의 `configured:true`.
- **환불 응답의 `selfRefundedMinor` 가 계산원이 돌려줄 현금**이다. 환불 후의 `selfPaidMinor` 로 대신할 수
  없다 — 그건 "앞으로 받을 금액"이지 "지금 돌려줄 금액"이 아니다(손님은 이미 옛 금액을 냈다).
  단말이 두 값의 차로 유추하게 두면 분담 결정이 서버 몫이라는 계약이 흐려지고, 유추가 틀리면 현금이 틀린다.
- `posTxnId` 는 **단말이 생성해 재시도에서 재사용**하는 멱등키다(taspa `(merchant, posTxnId)` UNIQUE).
  서버가 매번 새로 만들면 통신 단절 후 재시도가 이중 승인이 된다. 단, 금액을 고쳐 재승인할 때 같은 키를
  쓰면 **옛 승인이 그대로 재반환**되므로 키는 (QR, 금액) 쌍에 묶는다(`app/pos/page.tsx`).
- 회귀 테스트: `meal/MerchantConsoleIntegrationTest`(15 — 자기 매장만·열거 방지 403·매장/멤버십 SUSPENDED
  차단·**세션 쿠키로는 redeem 불가**·위임 베어러 거부·손님 PII 미노출·창 상한 노출·환불 노출·
  **정산 명세 4종**(전액 환불이 환불 합계에 남는지 포함)),
  `admin/AdminMerchantConsoleIntegrationTest`(19 — 담당자 부여/해제 권한·자가 증식 금지·재부여 멱등·
  step-up·감사·**타임존 미전송 시 유지** 규약).
- **소비 적재 경계 가드 3종**(같이 볼 것): `source=payment` 예약(장부 전용) · `merchantId` 전면 거부 ·
  `siteId` 경로 org 소속 검증. 회귀: `consumption/ConsumptionEventApiIntegrationTest`.

## 부서 서브트리 위임 (`org/DepartmentDelegation*`, V34) — Workspace 의 OU 위임 대응

그전까지 조직 관리 권한은 ORG_ADMIN 하나뿐이라 **전부이거나 전무**였다 — 개발본부장에게 자기 본부
인원 관리를 맡기려면 전사 관리자를 줘야 했고, 그건 다른 본부의 인사·식대 예산까지 여는 것이었다.

- **엔진 확장 `ResourceScope`**: 대상 자원의 부서 경로(`/{root}/…/{self}/`, **앞뒤 구분자 필수** — 없으면
  경로 끝 부서가 서브트리 글롭에 안 걸린다)를 **값 객체**로 받아 조건키 `taspa:ResourceDepartmentPath` 로
  방출. ★`PolicyEvaluator` 가 `SCOPE_OWNED_KEYS`(ResourceOrg + 이 키)를 **평가 전에 지우고** 다시 채운다 —
  덮어쓰기만으로는 **스코프가 빈 요청**에 위조 키를 심는 공격이 통과한다(회귀 테스트가 그 형태를 정확히
  겨냥한다. 스코프가 채워진 요청으로 쓴 테스트는 제거 로직을 지워도 초록불이라 아무것도 증명 못 한다).
- **판정**: `ResourceOrg` 정확일치 AND 경로 글롭, 둘 다 **양성 연산자**. 부서 축 없는 자원은 키를 아예
  방출하지 않아 Allow 미적용 → **새 표면에 스코프 주입을 잊으면 닫히는 쪽으로 실패**한다.
- **명시 Deny**(`DEPARTMENT_DELEGATE_DENIED_ACTIONS`, 조건 없이 전 리소스): 역할 변경·멤버 제거·초대·
  부서 편집·조직 프로필·식대 정책·**위임 부여**. 열거에서 빼는 것만으로 부족한 이유는 위임자도 일반
  멤버이고 나중에 다른 정책을 받을 수 있어서다. 근거는 `iam/DelegationPolicyTest` 의 "넓은 Allow 를
  함께 넣어도 Deny 가 이긴다".
- **엔진이 표현 못 하는 가드 2종**(요청 하나에 자원 하나만 보므로): ①배정 **목적지 부서**도 서브트리 안
  (아니면 부서장이 자기 부서원을 남의 본부로 밀어 넣는다) ②**자기 자신 대상 금지**(위임자도 자기 부서
  소속이라 스코프를 스스로 통과한다).
- **상호배제**: ORG_ADMIN·플랫폼 ADMIN 에게 부여 거절 + 승격 시 **같은 요청 안에서** 자동 해제.
  남겨 두면 "위임을 회수했으니 안전하다"는 오해를 만든다.
- ★**응답 범위는 통과의 근거를 따라간다**(넣었다가 잡은 결함). 부여 시점 상호배제는 "관리자에게 위임
  금지"를 막지만 **위임 부여 → 이후 플랫폼 승격** 순서는 못 막는다(플랫폼 역할은 org 밖에서 바뀌어
  `detachOnPromotion` 이 닿지 않는다). 그 상태에서 위임 범위로 좁히면 관리자가 **잘린 명단을 전체로
  오인**한다(화면에 아무 표시도 없다). 더 넓은 근거(플랫폼·조직관리자)가 있으면 위임을 조회하지 않는다.
- **목록은 서비스에서 거른다**(`listMembers(orgId, departmentIds)`) — 인가 통과 후 화면에서 거르면
  **응답에 이미 전사 명단이 실려 있다**. 인가(조상 경로)와 목록(서브트리)은 `DepartmentPathService`
  한 곳에서 계산한다(따로 두면 "보이는데 열면 403"이 생긴다).
- 미배정 멤버는 **어떤 위임자도 관리 못 한다** — 배정을 지우는 것만으로 남의 범위에 넣을 수 없게.
- UI: 조직구조 탭의 "부서 관리자 위임" 섹션. 설명이 **주지 않는 것**(초대·역할 변경·식대 정책)을 명시.
- 회귀: `org/DepartmentDelegationIntegrationTest`(20 — 자기 증식·경계 이탈·밀어내기·자기 관리 공격),
  `iam/DelegationPolicyTest`(13 — Deny 우선·스푸핑·경로 형식).

## AWS IAM 스타일 정책 RBAC (`iam/`, V27) — **엔진이 유일한 인가 권위**

역할 이름을 늘리는 대신 **정책 문서 기반**으로 간다. `IamAuthorizationService` 가 인가를 판정하고,
**레거시 인가 코드는 제거됐다**(모드 스위치·shadow 계측 없음 — 되돌아갈 대상 자체가 없다).
판정 불가(정책 손상·저장소 장애)는 **거부**다. 기동 로그가 "인가 판정 권위"를 남긴다.

인가 지점은 `authorize(request, label) { decide* }` 하나만 호출한다. 컨트롤러에 남은
`denialMessage*` 계열은 **판정이 아니라 거부 사유 문구 선택**이며(거부 확정 후에만 호출),
틀려도 권한이 열리지 않는다.

**근거**(첫 배포 전 확보): 기존 통합테스트 **196개를 한 줄도 고치지 않고** 통과 + Playwright e2e 11/11.
실트래픽이 없는 배포 전 단계에서는 관측 기반 파리티가 성립하지 않으므로 테스트가 그 역할을 대신했다.

- **모델**: `PolicyDocument{Version, Statement[]}` / `Statement{Sid, Effect, Action[], Resource[], Condition[]}`.
  TRN = `trn:taspa:{service}:{org}:{type}[/{id}]`(ARN 대응, `Trn.kt`). action = `service:Action`(`IamActions`).
  저장: `iam_policies`(managed, org_id NULL=플랫폼)·`iam_inline_policies`·`iam_policy_attachments`·
  `iam_principal_groups`·`iam_group_members`. 시스템 정책(`system_managed`)은 콘솔 수정·삭제 금지(409).
- **평가(AWS 동일)**: 암묵적 거부 → **명시적 Deny 우선**(정책 간 순서 무관) → Allow → 거부. `PolicyEvaluator`.
- **테넌시 강제는 글롭이 아니라 조건**: `PolicyEvaluator` 가 요청 리소스에서 org 세그먼트를 **구조적으로**
  추출해 `taspa:ResourceOrg` 를 **권위적으로** 채운다(호출자 제공 값은 덮어씀 — 스푸핑 차단). org 스코프
  정책은 이 키의 정확일치 조건으로 격리된다 → `*` 가 `:` 경계를 넘는 글롭 특성에 org 경계를 의존시키지 않는다.
- **fail-safe 불변식**(전부 회귀 테스트로 고정):
  - 조건 미평가(양성 연산자 키 부재·수치/날짜 파싱 불가·미지원 연산자)는 **문장 effect 로 편향** —
    Deny 는 적용(거부 유지), Allow 는 미적용. Deny 가드가 데이터 누락으로 무력화되지 않는다.
  - 저장 정책 파싱 실패는 **스킵이 아니라 예외**(`PrincipalPolicyResolver.parseOrFail`) — 손상된 Deny 가
    조용히 사라지는 fail-open 차단.
  - 정책 변수 치환값의 글롭 메타문자는 이스케이프(context 값 `*` 로 전 org 매치되는 injection 차단).
  - 파서 엄격: `NotAction`/`NotResource`/`Principal`/`NotPrincipal`·알 수 없는 요소·**중복 JSON 키** 거부
    (조용한 무시는 작성자의 제외 의도를 지워 과대부여가 된다).
- **위임 토큰(DELEGATED) 방어 2중** — 이 둘이 없으면 집행 전환이 곧 권한상승이었다:
  1. **`taspa:PrincipalType == M2M` 조건**(브리지 m2m 문장 전체) — 사용자 위임 토큰에 기계 전용 scope
     (`org.scim`·`meal.consumption.write`·`meal.forecast.read`)가 붙어도 SCIM 프로비저닝·장부 적재·예측에
     도달하지 못한다(레거시의 `isUserToken` 거부를 엔진 안으로 이관).
  2. **위임 경계**(`decideDelegated` = 신원 권한 ∩ 동의) — AWS permission boundary 의미론. 사용자가 org
     멤버라도 토큰이 동의받지 않은 능력은 못 쓴다. `openid profile email` 만 가진 제3자 앱 토큰이 조직
     캘린더·소비 집계를 읽던 confused-deputy 를 닫는다. 위임 가능한 scope 표는 `DELEGATABLE_SCOPE_ACTIONS`.
  ★컨텍스트에 `taspa:PrincipalType` 이 없으면 M2M 표면 전체가 **거부**된다(fail-safe) — 새 M2M 인가 지점을
  추가할 때 `IamContextFactory.build(kind, ...)` 로 컨텍스트를 채우는 것을 잊지 말 것.
- **엔진 경계**: CSRF 는 인가가 아니라 요청 위조 방지이므로 전송 계층(SecurityConfig)에 존치한다.
  step-up 은 조건키 `taspa:StepUpPresent` 로 노출되고 `@RequireRecentAuth` 인터셉터가 계속 집행한다
  (정책으로 더 좁힐 수는 있으나 인터셉터를 제거하지 않는다).
- **shadow 계측 비침습성**: 계측 인자 조립(라이브 DB 질의)까지 `IamShadowService.safely()` 로 감싼다 —
  관측 코드가 절대 요청을 깨뜨리지 않게. (QR 발급은 커밋 **후** 계측이라 조립 예외가 성공한 발급을 500 +
  쿨다운 락아웃으로 뒤집던 결함의 방어선.) shadow OFF 면 블록 자체가 실행되지 않아 질의 0.
- **호출자 계약**: `sessionShadow` 의 `orgAdminOf`/`memberOf` 는 반드시 **요청 경로 org** 에 대해
  `isOrgAdmin`/`isActiveMember` 로 도출(둘이 org SUSPENDED·멤버십 비활성을 접는다). 원시 플래그 전달 금지.
  SCIM 은 `orgs[]` 를 인정하지 않으므로 **`scimOrg` 단일 앵커**로 넘긴다(누락 시 전 요청이 거짓 불일치).
- **관리 API/콘솔**: `/api/admin/iam`(정책·그룹·부착·inline CRUD + **`POST /simulate`**), `/admin/iam`
  (시뮬레이터 폼 포함). 전부 hasRole ADMIN + step-up + CSRF + 감사(`ADMIN_IAM_*`).
- **엔진이 레거시보다 강해지지 않게 하는 장치**(집행의 전제):
  - `platformAdmin()` 은 `*`/`*` Allow + **명시 Deny**(`PLATFORM_ADMIN_EXCLUDED_ACTIONS`) — 레거시가 플랫폼
    ADMIN 에게 주지 않는 멤버십·기계 결속 능력(식권 발급=실지출, 가맹 결제, 장부 적재, SCIM)을 제외한다.
    ★단 그 표면의 인가 지점은 `platformAdmin=false` 로 호출해야 한다(명시 Deny 는 멤버 Allow 도 이기므로,
    `true` 를 넘기면 **멤버인 플랫폼 관리자까지 거부**되는 파리티 역전이 생긴다 — MealQrController 참조).
  - ORG_ADMIN 권한은 **명시 열거**(`ORG_ADMIN_ACTIONS`, 와일드카드 금지) — `org:*` 는 앞으로 추가되는
    모든 `org:` action 을 자동 부여해, 플랫폼 전용 조작을 그 이름으로 만드는 순간 조직관리자가 얻는다.
  - SCIM 앵커는 org 가 **존재하고 ACTIVE** 일 때만 전달(`ScimAuthorization.activeOrgAnchor`).
  - 조직 정책은 그 조직 principal 에만 부착 가능(`IamPolicyService.requireOrgConsistency`).
- **관리 콘솔(`/admin`·`/api/admin`, 74 핸들러)도 엔진이 판정한다** — 선언은 `@PlatformAction(action, resource)`,
  집행은 `PlatformAuthorizationInterceptor`. 컨트롤러 본문에는 인가 호출이 없다(선언이 애노테이션인 덕에
  인가가 부수효과보다 **먼저** 실행되는 것이 구조적으로 보장된다).
  - 체인의 `hasRole("ADMIN")` 은 **남는다**. 판정 권위는 엔진이고, 체인은 (1) 엔진 배선이 빠진 경로의
    백스톱과 (2) 미인증 진입점 결정(화면 302 / API 401)을 맡는 심층 방어다. 덕분에 킬 스위치가 1줄이다
    (`WebMvcConfig` 의 인터셉터 등록 제거 → 정확히 이관 전으로 복귀).
  - **action·리소스 네임스페이스를 갈랐다**(`platform:` / `iam:`, TRN 은 `trn:taspa:platform:`·`trn:taspa:iam::`).
    ★org 콘솔과 같은 `trn:taspa:org:{org}:*` 위에 올리면, 위임 정책 한 줄
    `{"Action":"*","Resource":"trn:taspa:org:X:*"}` 이 `platform:ForceVerifyOrgDomain`(자동가입 보안앵커
    우회)·`platform:AdministerOrg`(정지 자가 해제)·`platform:AddOrgMember`(멤버 직접 주입 → `meal:IssueQr`
    앵커 획득)까지 넘긴다. 브리지의 org 문장은 리소스 글롭이 admin TRN 에도 매치하므로 **격리를 지탱하는
    것은 action 축 disjointness 하나**다 — `iam/PlatformActionNamespaceTest` 가 그걸 고정한다(삭제 금지).
  - **선언 누락은 기동 실패**(`PlatformSurfaceValidator` ApplicationRunner). 모든 통합테스트가
    `IntegrationTestBase` 의 `@SpringBootTest` 를 공유하므로 CI 에서 100% 걸린다. 검증 항목: 선언 존재 ·
    action ∈ `PLATFORM_CONSOLE_ACTIONS` · TRN 접두 · 템플릿 `{var}` ⊆ 실제 URI 변수 · action 중복
    (`platform:AccessConsole` 만 공유 허용). ★`getBean(RequestMappingHandlerMapping::class.java)` 를 쓰지 말 것 —
    actuator 의 `ControllerEndpointHandlerMapping` 이 그 타입을 상속해 `NoUniqueBeanDefinitionException` 이 나고
    컨텍스트가 죽는다. **빈 이름**으로 조회한다.
  - 인가 인터셉터는 `recentAuthInterceptor` **뒤**에 등록한다(CSRF → step-up → 인가). 앞에 두면 재인증
    만료가 401 `REAUTH_REQUIRED` 대신 403 이 되는데, 관리자는 엔진에서 항상 통과하므로 **어떤 기존 테스트도
    실패하지 않는 잠복 결함**이다.
  - 선언 없는 핸들러(합성 OPTIONS 포함)는 하드 403 도 `return true`(fail-open)도 아닌 **폴백 판정**
    (`platform:AccessConsole` = 레거시 등가) + `log.error`. 최악값이 "전 사용자 개방"이 아니라 "이관 전과 동일"이다.
  - `orgId` 는 경로변수 이름이 아니라 **렌더된 TRN 의 org 세그먼트**에서 뽑는다 — 컨트롤러마다 `{id}`/`{orgId}`
    로 달라서 이름으로 읽으면 어떤 표면에서는 컨텍스트가 조용히 비어 조건 평가가 달라진다.
  - `decideSession` 에 `orgAdminOf`/`memberOf`/`merchantAdminOf` 를 넘기지 않는다 — 관리 표면의 유일한 Allow
    원천은 플랫폼 관리자 브리지여야 한다. ★그리고 `platformAdmin=false` 로 넘기지 말 것: `MealQrController` 의
    `false` 관례는 그 action 이 제외 목록에 있어서인데, `platform:` 은 제외 목록과 교집합이 공집합이라
    `false` 면 Allow 원천이 사라져 **모든 관리자가 403** 이 된다(함정의 방향이 정반대다).
  - **근거**: 기존 통합테스트 115건(admin 표면 10 클래스)을 **한 줄도 고치지 않고** 통과 + 기동 검증 74/74.
    ★단 파리티만으로는 "아무것도 안 한 것"과 구별되지 않는다(브리지가 `*`/`*` 라 엔진에 걸리는 주체가 없다).
    배선의 **양성 증거**는 `iam/AdminEngineEnforcementIntegrationTest` 의 명시 Deny 프로브다 —
    관리자에게 그 action 만 Deny 하면 그 엔드포인트가 403 이 되고, 정책을 빼면 복귀한다.
  - **이 이관이 닫지 않는 것**(정직하게): `platform:RegisterClient`(org/merchant 결속 M2M 발급)·
    `platform:AddOrgMember`·`platform:CreateSsoConnection` 의 간접 권한상승 경로는 그대로다. 제외 목록에
    넣으면 신규 고객사 온보딩이 막힌다. 산출물은 그것들이 **이름을 갖게 됐다**는 것 — "더 안전해졌다"가
    아니라 "지금부터 좁히는 것이 가능해졌다"가 정확한 표현이다.
- **자기 락아웃 방지(`IamLockoutGuard`)** — 인가 권위가 엔진 하나로 수렴한 뒤 생긴 새 위험을 닫는다.
  명시 Deny 는 Allow 를 이기고 순서도 무관하므로 `{"Effect":"Deny","Action":"iam:*","Resource":"*"}`
  한 줄이면 **그 정책을 지울 수 있는 사람이 아무도 없다**(체인의 `hasRole` 은 심층 방어라 판정을 못 되돌리고
  복구는 DB 직접 수정뿐). 조직 축의 `guardLastAdmin` 과 같은 개념을 플랫폼 IAM 축으로 옮긴 것이다.
  - ★**패턴 매칭이 아니라 엔진으로 실제 평가**한다. "위험한 문서"를 정규식으로 찾으면 반드시 새는 형태가
    나온다(와일드카드·조건·그룹 경유·중첩 부착). 변경을 **적용한 뒤**(같은 트랜잭션, 커밋 전) 활성 플랫폼
    관리자에게 `RECOVERY_ACTIONS`(콘솔 진입 + 정책 조회·수정·삭제·부착해제·inline 해제)를 물어보고,
    전부 통과하는 사람이 없으면 409 `IAM_LOCKOUT` — 예외라 트랜잭션이 롤백된다.
  - ★**순서가 시나리오의 전부다.** 자기를 먼저 막으면 다음 요청이 인가 인터셉터에서 **403** 으로 걸려
    가드가 아예 돌지 않는다(그것도 옳다). 실제 사고는 반대다 — 남들을 먼저 막고 **마지막에 자기를**
    막는 순간 아무도 남지 않는데, 그 요청은 요청 시점엔 아직 허용되므로 가드만이 막을 수 있다.
    회귀 테스트를 자기 먼저로 쓰면 403 이 나와 **가드를 검증하지 못한다**(처음에 그렇게 썼다).
  - ★**평가 자체가 실패하면 막지 않는다**(의도). 저장 정책이 손상되면 `parseOrFail` 이 던지는데(fail-closed),
    그 상태에서 편집을 막으면 **손상된 정책을 지우는 것조차 불가능**해져 가드가 막으려던 상태를 가드가
    만든다. 모든 관리자에서 평가가 실패하면 ERROR 로그를 남기고 통과시킨다.
  - **정지된 관리자는 세지 않는다** — 로그인할 수 없으므로 복구 경로가 아니다.
  - `findIdsByRoleAndStatus` 로 좁혀 조회한다(★`findAll()` 금지 — IAM 편집마다 전 고객사 임직원을 적재한다).
  - 적용 지점은 **유효 권한을 바꾸는 변경**뿐: update/deletePolicy · attach/detach · set/removeInline ·
    add/removeGroupMember · deleteGroup. create 계열은 부착·소속 전이라 효과가 없어 제외.
  - 운영 절차(안전한 좁히기 순서 + 잠긴 뒤 DB 복구): `docs/iam-operations.md`. 화면도 저장 전에 가드의
    존재를 알린다(`/admin/iam` 상단 Notice) — 409 만 받으면 설계된 보호인지 장애인지 구분할 수 없다.
  - ★★**프로브 리소스는 집행이 렌더하는 형태와 같아야 한다.** 처음엔 inline 해제를 와일드카드
    **리터럴**(`principal` 아래 별표/별표)로 물었는데 집행은 `principal/USER/{uuid}` 로 렌더한다.
    그래서 Resource 를 `principal/USER/` + 별표로 **좁게 겨냥한 Deny** 는 가드에 보이지 않은 채
    실제 복구 경로만 정확히 끊었다 — 가드를 통과한 변경이 락아웃을 만든다. 지금은 **구체 TRN**
    (평가 대상 관리자 자신 · 저장된 각 정책 id)과 와일드카드를 **둘 다** 묻는다.
  - ★KDoc 안에 슬래시+별표를 쓰지 말 것 — Kotlin 은 블록 주석 **중첩**을 지원해 그 두 글자가 새 주석을
    열고 파일 끝에서 "Unclosed comment" 로 컴파일이 깨진다(실제로 겪었다).
  - 평가 실패는 **세어서 드러낸다**(WARN + 거절 메시지에 "N명 평가 실패") — 세지 않으면 가드가
    '락아웃 방지'라는 잘못된 이유로 편집을 막고 진짜 원인(정책 손상)이 어디에도 나타나지 않는다.
  - 회귀: `iam/IamLockoutGuardIntegrationTest`(7 — 마지막 자기 차단 거절·**롤백 확인**·거절 후에도 편집 가능·
    단일 관리자·정지 관리자 제외 + **무해한 편집 대조군**). 프로브로 확인: 가드를 무력화하면 4건이 실패하고
    대조군만 통과한다.
- **엔진 이관 제외 표면**(행 단위 소유권이 인가): `GET /api/orgs/mine`·`/memberships`,
  `GET /api/meal/transactions` — org 앵커가 없어 TRN 을 구성할 수 없다. 억지로 `org:*` 로 잡으면
  일반 멤버가 자기 계정 페이지에서 락아웃된다.
- 회귀 테스트: `iam/PolicyEngineTest`(38 — 평가·매칭·조건·파서·테넌시·위임경계·플랫폼제외),
  `iam/IamAuthorizationServiceTest`(5 — fail-closed·위임경계·기계전용 표면), `iam/IamAdminApiIntegrationTest`(4),
  `iam/PlatformActionNamespaceTest`(네임스페이스 격리), `iam/AdminEngineEnforcementIntegrationTest`(배선 증거).

## 조직 커스텀 역할 (`org/OrgRoleService`·`OrgRoleController`, 스키마 무변경)

조직 역할이 **구성원 / 조직관리자 둘뿐**이라 "구성원 목록과 청구서는 보되 식대 정책은 못 바꾸는 인사
담당"에게 권한을 주려면 조직 전체 관리자를 줘야 했다 — 그건 그 사람에게 **전사 인사·식대 예산·도메인
자동가입**까지 여는 일이다. 부서 위임(V34)은 축이 다르다(부서 서브트리로 좁힐 뿐 능력은 고정).

**저장소는 V27 을 그대로 쓴다**(마이그레이션 없음): 역할 = `iam_principal_groups`(org 스코프) +
그 그룹의 `iam_inline_policies` 1건, 부여 = `iam_group_members`. 마커는 인라인 정책 이름
`taspa:org-role` — 이 이름이 아닌 그룹은 콘솔에서 **404**(플랫폼 관리자가 만든 그룹을 조직관리자가
역할로 오인해 편집·삭제하지 못하게. 존재 자체를 알리지 않으므로 열거 방지도 겸한다).

- ★**정책 문서는 서버가 만든다.** `SaveOrgRoleRequest` 에는 **정책 문서 필드가 아예 없고** 화면은
  action 목록만 고른다. 조직관리자에게 원시 JSON 편집을 열면 그 순간 `Resource` 에
  `trn:taspa:platform::*` 를 써 넣는 것이 인가 문제가 된다 — 아래 3중 가드가 다 막긴 하지만,
  **막을 필요가 없는 입력을 받지 않는 것**이 첫 번째 방어다.
- ★**부여 가능 목록은 서버가 유일한 출처**(`ORG_ROLE_GRANTABLE_ACTIONS` = `ORG_ADMIN_ACTIONS` −
  `ROLE_NON_GRANTABLE_ACTIONS`). 화면이 목록을 들고 있으면 서버에 action 이 추가돼도 영영 안 나타나고,
  서버가 막은 능력을 계속 보여주면 저장할 때마다 400 이 난다. `GET /roles/grantable-actions` 가
  라벨까지 함께 준다.
- ★**자기 증식 차단**(`ROLE_NON_GRANTABLE_ACTIONS`): `org:ManageRoles`·`org:ChangeMemberRole`·
  `org:ManageDelegation` 은 **역할로 넘길 수 없다**. 하나라도 열리면 부여받은 역할이 새 역할을 만들거나
  자기를 ORG_ADMIN 으로 올려 **조직관리자 권한 전체를 스스로 획득**한다(부서 위임의 명시 Deny 와 같은 이유).
- ★**모르는 action 은 거르지 않고 거절**(`validateActions` → 400). 조용히 빼면 화면은 "이 능력을 줬다"고
  말하는데 실제로는 없는 상태가 되고, 그 차이를 아무도 모른다.
- ★**`PrincipalPolicyResolver.confine` — 심층 방어**. 평가기는 정책의 `org_id` 를 보지 않는다. 지금까지는
  정책을 플랫폼 ADMIN 만 만들 수 있어 org 경계가 쓰기 시점(`requireOrgConsistency`)에서만 지켜졌는데,
  조직관리자에게 역할 정의를 열면 그 전제가 사라진다. 그래서 org 스코프 문서의 **Allow 문장마다**
  `taspa:ResourceOrg` 정확일치 조건을 덧붙여, 문서가 무엇을 주장하든 그 org 밖으로 못 나가게 한다.
  **Deny 문장은 건드리지 않는다** — 조건을 붙이면 조건 미평가 시 Deny 가 약해질 수 있고(fail-safe 편향은
  Deny 를 적용하는 쪽), 남의 org 를 향한 Deny 는 권한을 넓히지 않는다.
- ★★**역할은 "지금도 그 조직 사람"일 때만 유효하다**(`PrincipalPolicyResolver.activeOrgIdsOf`).
  부여 시점 검사(`assign`)만 있던 동안 **퇴사자가 조직 권한을 그대로 유지**했다 — 부여는
  `iam_group_members` 행이고 `removeMember` 는 `org_memberships` 행만 지운다(V27 CASCADE 는
  users·groups 삭제에만 걸린다). 멤버십 SUSPENDED(SCIM)·조직 SUSPENDED 도 저장 정책에는 닿지 않았다.
  ★**비대칭이 핵심이었다**: ORG_ADMIN 은 `isOrgAdmin` 라이브 질의로 즉시 회수되고 토큰 `roles` 클레임도
  `activeMembershipsFor` 로 닫혀 있는데, **정작 인가 권위인 저장 정책 경로만** 열려 있었다.
  org 스코프 그룹·managed 정책 양쪽에 게이트를 걸고, 질의는 org 수와 무관하게 2회로 묶었다.
- ★★**초대 경로로 자기증식이 우회됐다.** `org:CreateInvitation` 은 부여 가능 목록에 있는데(HR 담당에게
  주는 것이 자연스럽다) 초대는 `role=ORG_ADMIN` 을 그대로 받아 수락 시 승격까지 반영한다 —
  받은 사람이 **자기가 통제하는 두 번째 주소**를 ORG_ADMIN 으로 초대하면 조직 전체 권한을 스스로 얻는다
  (자기 주소는 '이미 멤버'로 막히지만 주소 하나면 충분하다). 부서 위임(V34)이 같은 이유로 초대 action 을
  명시 Deny 한 것과 같은 함정이었다. 지금은 `OrgInvitationController.mayGrantOrgAdmin` 이
  `org:ChangeMemberRole` 을 **추가로** 묻고(`OrgSessionAuthorizer.permits` — 던지지 않고 값으로 판정),
  `invite(mayGrantOrgAdmin=false)` 가 기본값이라 새 호출 경로가 생겨도 닫히는 쪽으로 실패한다.
  CSV 대량 초대는 **행별 REJECTED**(부분 성공 모델 유지).
- 역할 이름에 **쉼표 금지** — 연동 선언 목록이 쉼표 구분 문자열이라 쪼개지면 영영 매칭되지 않거나
  선언한 적 없는 이름과 겹친다. 만드는 쪽·선언하는 쪽 양쪽에서 막는다.
- 역할 삭제 시 인라인 정책은 **명시 삭제**한다(`iam_inline_policies` 에는 principal FK 가 없어 CASCADE 대상이 아니다).
- 인가·감사·step-up 규약은 다른 org 콘솔 표면과 동일(`OrgSessionAuthorizer` + `@RequireRecentAuth` + CSRF).
### 연동 서비스로의 역할 전파 (`org.roles` scope + per-client 선언)

조직 역할을 **연동 서비스(RP)의 인가 입력**으로 내보낸다. 그전까지 RP 가 알 수 있는 것은 소속(`org_id`)
까지라, "이 사람이 우리 서비스에서 무엇을 할 수 있나"는 서비스마다 권한 테이블을 하나 더 두고 관리해야
했다. 운영 문서는 `docs/integration-roles.md`.

- **발급 조건 두 축, 둘 다 fail-closed**: scope `org.roles` **AND** 클라이언트 설정
  `settings.client.role-names`(쉼표 구분). 실린 값은 **선언한 이름 ∩ 사용자가 실제로 가진 이름**이다.
  선언을 안 두고 전부 보내면 ①조직의 역할 이름 전체가 RP 마다 누출되고 ②조직이 역할을 하나 추가할
  때마다 모든 RP 의 인가 입력이 예고 없이 바뀐다.
- ★**`roles` 는 항상 `org_id` 와 같은 조직의 것**이다. 대표 org 에 해당 역할이 없으면 `roles` 를 아예
  싣지 않고 `org_roles`(복수 org 용)로만 알린다 — 짝이 안 맞는 값을 그 자리에 넣으면, org 를 확인하고
  역할로 인가하는 RP 가 **다른 조직의 권한을 준다**. 그래서 대표 org 판정을 org 클레임과
  `activeMembershipsFor` **한 곳으로 모았다**(각자 판정하면 갈릴 수 있다). 회귀 프로브로 재현 확인.
- 모양은 기존 org 클레임과 대칭: `roles` ↔ `org_id`, `org_roles` ↔ `orgs`. 멤버십 역할(`org_role`)과는
  **다른 축**이다.
- ★**taspa 자신의 인가는 이 클레임을 쓰지 않는다** — 인가는 항상 IAM 엔진이 DB 를 읽어 판정한다.
  그래서 회수는 서버에서 즉시지만 **이미 발급된 토큰의 클레임은 만료(15분)까지 남는다**. 즉시 차단이
  필요하면 세션 종료·멤버십 정지. 이 지연은 숨기지 말고 문서에 계약으로 적혀 있다.
- ★**역할 이름이 곧 키**라 조직이 이름을 바꾸면 교집합에서 빠져 RP 가 그 역할을 잃는다(닫히는 방향이라
  안전하지만 담당자는 이유를 모른다 — 이름 변경 시 RP 선언 목록을 함께 갱신).
- ★**`ClientSettings` 는 맵을 통째로 교체한다.** 수정 시 `roleNamesApplied` 가 **기존 설정 위에 얹는다** —
  새로 만들어 넘기면 org-id·merchant-id 결속이 조용히 사라져 **이름만 바꿨는데 POS 단말이 결제하지
  못하게** 되고 화면 어디에도 이유가 없다. `ClientUpdateRequest.roleNames` 는 **nullable**(null=미전송
  유지, []=선언 해제) — 구분하지 않으면 이름만 고친 저장이 선언을 지운다.
- 클라이언트 스타터: `TaspaRolesJwtConverter` 가 `roles` → `ROLE_{이름}` 권한으로 옮긴다(scope 권한은
  그대로 **추가**만). `taspa.client.org-id` 로 담당 조직을 고정할 수 있고, **여러 조직의 역할을 절대
  합치지 않는다**. RP 가 자기 `JwtAuthenticationConverter` 를 등록하면 스타터는 물러난다.
- 회귀: `token/TokenCustomizerConfigTest`(13 — 교집합·미선언 fail-closed·scope 없음·**org_id 정합**·
  SUSPENDED org), `admin/AdminConsoleIntegrationTest`(선언 저장·미전송 유지·해제·**결속 보존**).

- 회귀: `org/OrgRoleIntegrationTest`(17 — 부여한 것만 열림·자기증식 action 거절·플랫폼 action 거절·
  미지의 action 거절·타 org 자원 거부·타 org 역할 404·비멤버 부여 거절·일반 멤버는 역할 관리 불가·
  편집/해제/삭제가 접근을 실제로 회수), e2e `org-roles.spec.ts`(카탈로그가 서버에서 온다 +
  **없어야 할 능력이 없다** + 생성→부여→해제).

## 돈 표면 교차 일관성 (`billing/MoneySurfaceConsistencyIntegrationTest`)

★**이 시스템에서 실제로 터진 결함은 "계산이 틀렸다"보다 "같은 사실을 두 화면이 다르게 말한다" 쪽이었다.**
정산 명세가 환불축을 `status='APPROVED'` 로 묶어 전액 환불을 통째로 누락(서버), 식수 로그 합계가 VOIDED
에서 early-return 해 같은 결함을 재현(프런트), 사용내역이 전액 환불을 "0원 · 취소"로만 표시(프런트) —
**표면마다 있는 테스트는 전부 초록불이었다.** 각자 자기 규칙 안에서는 맞았기 때문이다.

그래서 한 번의 결제·환불 흐름(15,000 한도초과 → 3,000 부분환불 / 10,000 한도내 / 8,000 전액환불)을
만들고 **청구서·원장·3-way 대사·가맹 정산·전역 지급·소비이벤트가 모두 같은 숫자를 말하는지** 한 자리에서
단언한다. 새 돈 표면을 추가하면 여기에 한 줄 더할 것 — 표면끼리 맞대지 않으면 갈라진 것을 아무도 모른다.

**★서버 테스트만으로는 부족하다 — 렌더된 화면끼리도 맞대야 한다**(`e2e/tests/money-surfaces.spec.ts`).
서버는 API 층에서, 프런트 단위테스트(`summarize.test.ts`·`amounts.test.ts`)는 순수 함수에서 정합을
지키는데, **그 사이가 비어 있었다** — 두 겹 모두 초록불인데 화면이 서로 다른 말을 하는 상태가 가능하다.
그래서 e2e 가 **진짜 결제·환불을 만든다**(SQL 픽스처가 아니라 QR 발급 → M2M redeem → refund 실제 경로:
15,000 한도초과 → 3,000 부분환불 / 10,000 한도내 / 8,000 전액환불) 뒤 다섯 화면을 브라우저로 연다.
- ★**요약 카드를 집어서** 본다. 본문 전체에 `toContain('8,000')` 을 걸면 **표 행**이 그 값을 갖고 있어
  요약 카드가 틀려도 통과한다 — 실제로 처음엔 프런트 결함을 심었는데 초록불이었다. 이 테스트의 값어치는
  "화면이 *어디서* 그 숫자를 말하는가"에 있으므로 `statValue(라벨)` 로 좁힌다.
- ★**`/admin/payables` 는 총액이 아니라 그 매장의 행**을 본다. 총액은 dev DB 의 다른 매장까지 합한 값이라
  이 조직 금액과 같을 수 없다(실측 198,000원/9매장). 불변식은 "운영자가 보는 **매장별** 금액 = 그 매장이
  자기 화면에서 보는 금액"이고, 그건 `platformPayables` 가 매장별 `settlement()` 을 재사용하기 때문이다.
- ★**QR 발급에는 사용자 쿨다운 10초**가 있다 — 연속 승인 사이에 기다리지 않으면 429 로 실패한다.
- ★**로딩 표시가 사라지는 것만으로 결과를 기다리지 마라.** 아직 요청이 시작되지 않았을 수도 있어
  그 순간엔 '로딩 아님 + 결과 없음' 이 참이다. 결과에만 있는 문구가 보일 때까지 기다린다.
- 프로브 2건으로 **잡는다는 것**을 확인: `summarize.ts` 의 VOIDED early-return 복원 → 환불 합계가
  11,000 대신 3,000, `amounts.ts` 의 원금 표시 제거 → 사용내역에서 15,000원이 사라진다.

- **통제한 변수**: org·merchant 타임존을 **둘 다 UTC**. 실제로는 두 문서가 서로 다른 달력을 쓰므로
  경계일 거래만큼 **정당하게 다를 수 있다**(그 차이를 결함으로 오인하지 않으려고 제거한 것이다).
- 프로브 확인: 정산 환불축에 `status='APPROVED'` 를 되돌리면 이 테스트가 11,000 대신 3,000 을 잡는다.
- **환불 없는 대조군** 포함 — 없으면 "환불 경로에서만 맞는지"와 "항상 맞는지"가 구별되지 않는다.

## 회계용 CSV 내보내기 (`common/export/CsvWriter`)

돈 문서 3종을 내려받는다: 조직 청구서 `GET /api/orgs/{org}/invoices/{id}/csv`, 가맹 정산 명세
`GET /api/merchant-console/{id}/settlement/csv`, 가맹 거래 로그 `.../transactions/csv`.
**권한·집계는 화면과 정확히 같은 것을 쓴다**(같은 action, 같은 서비스 호출) — 형식만 다른데 권한이나
숫자가 갈리면 그게 더 큰 사고다. 그전까지 내보내기가 **전무**해서 회계팀은 화면을 보고 옮겨 적어야 했다.

- ★**수식 인젝션 방어**가 이 코드의 존재 이유다. `=`·`+`·`-`·`@`·탭·CR 로 시작하는 값을 엑셀이 **수식으로
  실행**하는데(CWE-1236), 조직명·매장명은 사용자가 정하고 그 파일을 여는 사람은 회계 담당자다.
  ★**따옴표로 감싸는 것만으로는 막히지 않는다** — 엑셀은 언따옴표한 **뒤** 판정한다. 그래서 작은따옴표를
  덧붙여 텍스트로 못박고(escape 순서: 수식 무력화 → RFC 4180 따옴표), 회귀 테스트가 대조군과 함께 고정한다.
- **UTF-8 BOM 필수**. 없으면 윈도우 엑셀이 로컬 코드페이지로 읽어 한글이 전부 깨지는데 **파일은 열리므로
  조용히 틀린다**(받는 사람이 우리 버그로 인지하지도 못한다).
- 파일명은 ASCII 폴백 + RFC 5987 `filename*=UTF-8''` 을 함께. `Cache-Control: no-store`(재생성으로 숫자가
  바뀌는 문서라 프록시가 옛 파일을 재사용하면 잘못된 금액이 돈다).
- 거래 로그의 시각은 **UTC ISO-8601**. 매장 로컬 문자열로 내면 받는 쪽이 타임존을 알 수 없고(파일에는
  화면 머리말이 따라가지 않는다) 엑셀이 제멋대로 날짜로 재해석한다.
- 프런트는 `components/DownloadLink` — **앵커 내비게이션**으로 받는다. 동일 오리진 프록시라 세션 쿠키가
  그대로 실리고, **파일명을 서버가 단독으로 정한다**(Blob 방식이면 프런트가 이름을 다시 지어야 하고 그
  순간 갈라진다).
  ★단 앵커만으로는 **세션 만료가 조용히 실패한다**: `/api/**` 미인증 응답은 Accept 헤더와 무관하게
  **401 JSON**(`ApiAuthenticationEntryPoint`)이라 로그인 리다이렉트가 일어나지 않고, 브라우저가 그 401
  본문을 파일로 내려받는다 — 회계 담당자는 CSV 대신 영문 JSON 조각을 얻고 오류 표시도 없다(curl 로
  Accept 두 종류를 실측해 확인). 그래서 클릭 시 `GET /api/account/me` 로 세션을 먼저 확인하고 통과했을
  때만 내비게이션한다(401 이면 `lib/api.ts` 가 로그인으로 보낸다 — 화면마다 다른 실패 처리를 만들지
  않는다). 확인 자체가 실패하면 **막지 않고 진행**한다(다운로드를 못 하게 만드는 것이 목적이 아니다).
  ⌘/Ctrl·중간 클릭 같은 보조 클릭은 가로채지 않는다.
  ★확인을 통과한 뒤 **`window.location.href` 로 이동시키지 않는다**(2차 리뷰에서 잡힌 결함). 그건 최상위
  내비게이션이라, 서버가 첨부가 아닌 응답(잘못된 기간 → 400 JSON)을 주면 브라우저가 **화면 전체를 그
  문서로 대체**한다 — 다운로드 실패가 "관리 콘솔이 원시 JSON 으로 바뀌고 입력하던 조건이 사라지는"
  사고가 된다. 대신 `download` 를 단 앵커를 **합성해 누른다**: 동일 오리진이라 다운로드로 처리되어
  실패해도 보고 있던 화면이 남는다(가로채기 도입 전의 안전한 성질을 되돌린 것). 파일명은 서버가 정한다.
- 회귀: `common/export/CsvWriterTest`(7 — 수식 5종·대조군·RFC 4180·BOM·null·파일명·no-store),
  `meal/MerchantConsoleIntegrationTest`(CSV 2건 — 수식 무력화 실물 + 형식이 테넌시를 우회하지 않음),
  `billing/InvoiceIntegrationTest`(CSV 1건).

## 웹 프런트엔드 (`web/`, Next.js 16 + TypeScript + Tailwind + shadcn/Base UI)

- **동일 오리진 프록시가 인증 전략의 핵심**(`web/next.config.ts`). `/api/**`·`/login`·`/oauth2/**`·
  `/webauthn/**` 등 서버 소유 경로를 `beforeFiles` 로 taspa(:9100)에 프록시한다. 이유: `/api/orgs/**`·
  `/api/admin/**` 은 세션 쿠키 인증이고 컨트롤러가 **위임 베어러를 의도적으로 거부**하므로, 다른 오리진의
  SPA 가 액세스 토큰으로 호출하면 설계상 403 이다. 한 오리진으로 보이게 해서 세션·step-up·CSRF 를
  서버가 기대하는 그대로 흐르게 한다(서버 인가 모델 무손상). **베어러 방식으로 바꾸지 말 것.**
- **로그인 UI 는 서버가 계속 소유한다** — MFA·이메일 인증·패스키·소셜·리스크 게이트가 전부 서버 플로우에
  얽혀 있어 SPA 가 재구현하면 그 불변식이 깨진다. SPA 는 `/login` 으로 보내고 돌아온다.
- SPA 전용 서버 엔드포인트: `GET /api/csrf`(세션 바인딩 토큰 — meta 태그를 읽을 수 없는 SPA 용),
  `GET /api/account/me`(신원·platformAdmin·manageableOrgs — 화면 진입점 결정).
- `web/lib/api.ts` 가 3계약을 담는다: 세션 쿠키·CSRF 헤더 자동 첨부·401 `REAUTH_REQUIRED` → `/reauth` 이동.
- 디자인 토큰은 `app/globals.css` 의 taspa 팔레트가 **단일 출처**이고, shadcn 시맨틱 토큰(`--primary` 등)을
  거기서 파생시킨다. 서버 렌더링 로그인 화면(`static/css/auth.css`)과 같은 색이어야 한 제품으로 보인다.
- 네비게이션의 `visible` 은 **보안 경계가 아니라 UX** 다 — 인가는 서버 엔진이 판정한다.
- ⚠️ `web/AGENTS.md`: Next.js 16 은 학습 데이터와 다를 수 있으니 `node_modules/next/dist/docs/` 를 먼저 볼 것
  (App Router 의 `params`·`cookies()` 는 Promise).
- **프록시 뒤 리다이렉트 2대 함정**(둘 다 로그인 직후 사용자를 프런트 밖으로 튕겨냈다 — 되돌리지 말 것):
  1. `RequestCache` 는 **HTML 화면 요청만 저장**한다(`SecurityConfig.requestCache`). 기본값은 종류를 가리지
     않아, SPA 가 로그아웃 상태에서 보낸 `/api/account/me` 가 "로그인 후 돌아갈 곳"이 되어 사용자가
     **원시 JSON 화면에 착지**했다.
  2. `server.tomcat.use-relative-redirects: true`. 기본값은 Tomcat 이 요청 Host 로 절대 URL 을 조립하는데,
     프록시가 넘긴 Host 는 서버 자신(:9100)이라 `sendRedirect("/account")` 가 프런트 오리진을 벗어났다.
  회귀: `e2e/tests/web-spa.spec.ts` "로그인 직후 JSON 응답이 아니라 화면으로 돌아온다".
- **CORS `allowedMethods` 에서 PATCH 를 빼지 말 것**(`config/CorsConfig.kt`). 빠져 있던 동안 dev 의 표시 이름
  저장·패스키 이름 변경·캘린더 피드 토글 3곳이 **상시** 깨졌고, 증상이 두 겹으로 오도됐다: CORS 거절은
  403 + 비-JSON 본문이라 `web/lib/api.ts` 의 `readError` 가 "로그인이 필요합니다"로 정규화하는데,
  401 이 아니라 로그인 이동도 안 일어난다 — 사용자는 이유를 알 수 없다.
- **개발 환경 CORS**: dev 프로파일만 `taspa.cors.allowed-origins=http://localhost:3000`. 프록시는 브라우저의
  `Origin` 헤더를 그대로 전달하는데 :3000 ≠ :9100 이라 서버가 cross-origin 으로 보고 **전 요청을 403
  "Invalid CORS request"** 로 거절한다(정적 자원까지). 운영에서는 리버스 프록시가 같은 오리진에 두므로
  불필요하고, prod 는 기본값(전면 거부)을 유지한다. 기동 로그에 허용 오리진이 찍힌다.
- **세션은 앱 전체에서 하나**(`components/SessionProvider.tsx` → 루트 레이아웃, `lib/session.ts` 의
  `useSessionSource`/`useSession`). 훅이 각자 조회하면 `retry` 도 각자가 돼, error 화면에서 "다시 시도"를
  눌러도 **본문만 복구되고 헤더(`AppShell`)는 error 로 남는다**(네비게이션·이름·로그아웃이 사라진 채 유지).
  프로바이더 밖 `useSession` 은 **던진다** — 조용한 폴백은 그 상태를 되살리는데 증상이 화면 절반에만 나서
  발견이 늦다. 회귀: e2e `web-spa.spec.ts` "신원 확인 재시도는 본문과 헤더를 함께 복구한다"(차단을 개수가
  아니라 **구간**으로 걸어야 옛 구조에서 확실히 실패한다 — 조회가 둘이면 누가 500 을 받는지가 경합이다).
- **화면 구성**: `/meal`(직원 식권 — QR·만료 카운트다운·사용내역), `/console/[orgId]`(조직 관리 10탭 —
  개요·구성원·초대·조직구조·**식대정책**·도메인·예측·청구서·**역할**·활동로그), `/admin/*`(플랫폼 관리 10화면 — 조직·사용자·
  클라이언트·가맹(타임존·담당자)·감사·SSO·캘린더·**IAM 정책+시뮬레이터**·**지급 현황**·**정합성 대사**;
  대시보드에 **미확정 청구서** 섹션), `/account`(프로필·MFA·패스키·
  세션·신뢰기기·연결된 앱·소셜·로그인기록·탈퇴), `/merchant/[merchantId]`(가맹 관리자 — 식수 로그·**정산 명세**·예측),
  `/pos`(계산대 단말 — 등록 관문·스캔·금액·승인). 총 35 라우트(BFF `/api/pos/*` 4개 포함).
- **UI 규약**: shadcn(Base UI) 컴포넌트 + 시맨틱 클래스만(임의 색상값 금지). 공용 훅 `lib/useApi`
  (`useApi`/`useMutation`)를 쓰고 화면마다 로딩·오류 처리를 새로 짜지 않는다.
- ★**`useMutation` 은 `run` 을 ref 로 최신 렌더의 것으로 유지한다**(되돌리지 말 것). 예전엔
  `useCallback(..., [])` 이 **첫 렌더의 클로저**를 붙잡아, 값을 인자로 넘기지 않고 폼 상태를 클로저로
  읽는 화면이 **초기값(빈 문자열)을 서버로 보냈다** — 역할 이름을 입력하고 저장하면 "역할 이름은
  1~128자여야 합니다" 400 이 돌아온다. 화면에는 방금 입력한 값이 그대로 보이므로 사용자도 개발자도
  원인을 짐작할 수 없다(실제로 역할 화면에서 이렇게 터졌다). "값은 인자로 넘길 것"이라는 규약으로도
  막을 수 있지만 규약은 다음 화면에서 다시 깨진다 — 훅에서 닫는 편이 낫다.
- ★**상태 초기화는 effect 가 아니라 렌더 중에** 한다(`useApi` 의 요청 신호 비교, `useSessionSource` 의
  재시도, 식대정책 폼 시딩). effect 안에서 `setLoading(true)` 를 하면 렌더가 한 번 더 도는 것뿐 아니라
  **그 사이 한 프레임 동안 직전 조건의 결과가 최신인 것처럼** 보인다 — 조직을 바꾼 직후 이전 조직의
  금액이 스쳐 가고, "다시 시도"를 눌러도 방금 실패한 오류 화면이 그대로 남아 반응이 없어 보인다.
  신호 비교(`sameSignature`)는 **React 의 의존성 비교와 같은 규칙**(얕은 `Object.is`)이어야 한다 —
  갈리면 요청은 나가는데 화면은 안 바뀌거나(또는 매 렌더 초기화로 무한 렌더) 한다. deps 에는
  원시값만 넘길 것(참조 비교라 매 렌더 새 객체는 항상 다르다). 회귀: `lib/useApi.test.ts`(7).
- ★**렌더 중에 내비게이션하지 않는다**(`AppShell.RequireAuth`). 렌더는 여러 번 실행될 수 있어
  (동시성 렌더·StrictMode) 같은 이동이 중복으로 걸리고, 그 사이 `continue` 로 실릴 현재 경로가
  바뀌어 있을 수도 있다. 이동은 effect 에서 `goToLogin()` 한 곳으로.
- **UI 감사 하네스**(`e2e/tests/ui-audit.spec.ts`, `UI_AUDIT=1` 일 때만 실행): 한 계정에 모든 역할
  (플랫폼 ADMIN·ORG_ADMIN·가맹 담당)을 심고 **30 라우트 × 모바일/데스크톱 × 라이트/다크**를 캡처하며,
  모바일 가로 넘침과 콘솔 오류를 함께 수집한다. 라우트가 30개면 손으로는 못 훑는다 — 실제로 이 하네스가
  아래 3건을 찾았다(모바일 헤더 붕괴·한글 줄바꿈·Base UI 버튼 경고). ★역할은 **로그인 시점에 세션에
  굳으므로** DB 승격 뒤 반드시 다시 로그인해야 한다(안 하면 `/admin/*` 스크린샷이 전부 403 화면이 된다).
  `/logout` 만으로는 부족하고 `context().clearCookies()` 가 필요하다.
- ★**한글은 어절 단위로 끊는다**(`globals.css` 의 `word-break: keep-all` + `overflow-wrap: break-word`).
  CSS 기본값은 한글을 **글자 단위**로 끊어 "오후 07:44" 가 "오/후", "않습니다" 가 "않/습니다" 로 갈라진다 —
  읽을 수는 있어서 오래 안 보였지만 설명 문장 대부분이 그 상태였다. `anywhere` 가 아니라 `break-word` 인
  이유는 min-content 폭을 바꾸지 않아 표 레이아웃이 흔들리지 않기 때문이다(긴 이메일·UUID 는 그때만 끊긴다).
- ★**헤더는 모바일에서 두 줄로 접는다**(`AppShell`, `sm:contents` 로 데스크톱 한 줄 배치는 무변경).
  한 줄에 다 넣으면 390px 에서 메뉴가 눌려 **"조/직/관/리" 처럼 글자마다 줄이 바뀌어** 헤더를 읽을 수 없다.
  메뉴 줄은 가로 스크롤 + `whitespace-nowrap`. 조직 콘솔 탭(10개)은 이미 `overflow-x-auto`+`shrink-0`.
- ★**`--secondary` 는 `--card` 와 달라야 한다**(`globals.css`). 같은 값이던 동안
  `Badge variant="secondary"` 가 카드 위에서 **배경 없는 굵은 본문 텍스트**로 보였다 — '기본값 사용 중'·
  '검증됨' 같은 상태 칩이 칩으로 안 읽혔다. 라이트/다크 모두 정의된 `taspa-line` 을 쓴다.
- ★**`Section` 의 `action` 은 제목 줄에 붙인다**(`console-ui.tsx`). 제목+설명을 한 덩어리로 묶고 옆에
  action 을 두면 모바일에서 배지가 **설명문 아래로 떨어져** 무엇의 상태인지 모르게 되고, 반대로 제목
  덩어리에 `flex-1` 만 주면 **넓은 action(검색 입력)이 제목을 눌러** 설명이 다섯 줄로 접힌다(둘 다 실측).
  제목 줄과 설명을 분리하면 양쪽이 동시에 성립한다.
- ★**인라인 폼의 버튼은 `FieldAction`** 으로 감싼다. 컨테이너를 `items-end` 로 맞추면 **hint 가 있는
  `Field` 만 입력이 위로 밀려** 한 줄이 어긋난다(사업장 폼 22px·위임 폼 23px 실측).
- ★**가로 스크롤 탭은 활성 탭을 끌어온다**(`lib/useActiveTabScroll.ts`). 조직 콘솔 10탭·관리 콘솔 11탭이
  390px 에선 5개까지만 보여, 뒤쪽 탭에 들어가면 **"지금 어느 탭인가"가 화면에 전혀 없었다.**
  `block: "nearest"` 필수(빼면 세로로도 튄다). 표 아래 상세 패널은 `useRevealOnChange` —
  없으면 '상세'를 눌러도 화면에 변화가 없어 버튼이 고장난 것처럼 보인다.
- ★**빈 상태는 "조건이 안 맞다"와 "실적이 없다"를 구분한다**(예측 화면). 서버가 조건에 맞으면 셀을 항상
  만들므로 `cells.length === 0` 분기는 신규 조직에 **걸리지 않고**, 'NO_DATA' 로 도배된 표가 그려져
  화면이 고장난 것처럼 보였다. `allMissing`(전 셀 산출 불가)을 따로 둔다.
- ★**한국어 화면에 `type="month"` 를 쓰지 마라.** 브라우저 로케일대로 **"June 2026"** 이 뜬다 —
  같은 페이지의 다른 기간 입력은 `2026-06` 이라 한 화면이 같은 개념을 두 표기로 말하게 된다.
  서버 계약(`YYYY-MM`)에 맞춘 텍스트 입력을 쓴다.
- **web 은 prettier(`printWidth: 110`)로 포맷한다** — `npm run format` / `format:check`.
- ★**버튼 모양의 링크는 `ButtonLink`** 를 쓴다(`components/ui/button.tsx`). `<Button render={<Link/>}>` 은
  Base UI 의 `nativeButton` 기본값(true)과 어긋나 **네이티브 버튼 시맨틱이 사라지고**(폼 제출·키보드)
  경고는 콘솔에만 남는다 — 화면으로는 안 보여서 7곳이 그대로 배포 직전까지 왔다. `external` 은
  서버 소유 경로(`/login`·`/logout`)용이다(전체 내비게이션 필요).
- 회귀 테스트: `e2e/tests/web-spa.spec.ts` — **프록시 아키텍처의 전제**(SPA 오리진 가입 → 세션 쿠키가
  프록시된 `/api/account/me`·`/api/csrf` 까지 도달)를 못박는다. 이게 깨지면 SPA 전체가 401 이 된다.

## 여정 감사에서 나온 불변식 (6 페르소나 × 실제 플로우, 확정 44건)

★**화면 감사(라우트별 캡처)와 여정 감사는 잡는 것이 다르다.** UI 감사 하네스가 30 라우트를 라이트/다크·
모바일/데스크톱으로 전수 캡처해 0 결함이던 상태에서, 페르소나가 **실제 플로우를 걸어 보니** 블로커 6건이 나왔다. 화면 하나
하나는 멀쩡한데 **화면 사이가 끊겨** 있었기 때문이다. 새 기능을 붙일 때 라우트가 아니라 **여정**으로 볼 것.

- ★**메일·기기 링크가 착지하는 서버 소유 경로는 `web/next.config.ts` 의 프록시 목록에 반드시 있어야 한다.**
  `/orgs`(초대 수락)가 빠져 있어, 배포 문서가 지시하는 구성(공개 도메인 = SPA)에서 초대 링크가 **Next 기본
  404**로 끝났다 — 조직에 사람을 넣는 자율 경로는 초대뿐이라 온보딩이 그 자리에서 멈추는데, 메일 발송은
  성공했으므로 **서버 로그에도 흔적이 없다**. `/activate`·`/activated`(Device Grant, 사용자가 손으로 입력)도
  같은 이유로 넣었다. 회귀: `e2e/tests/web-spa.spec.ts` "프록시 허용 목록"(+ **무관 경로 404 대조군**).
- ★**"성공했습니다"를 두 화면이 말하는데 결과가 안 보이면, 목록에서 지운 쪽을 의심하라.** 가맹점 등록
  기본값이 PENDING 인데 담당자 콘솔 진입 조건은 ACTIVE 라, 등록·담당자 지정이 **둘 다 성공을 보고하고도**
  그 담당자에게는 매장이 존재하지 않는 것과 똑같이 보였다(빈 화면은 **이미 가진 권한을 다시 요청하라**고
  안내). 진입 집합과 인가 조건을 같게 유지하는 불변식은 옳지만, 그걸 지키려고 **응답에서 통째로 지우면**
  더 나쁘다 — `MyMerchantsResponse.blocked` 처럼 **사유를 갖는 별도 목록**으로 존재는 알린다.
- ★**되돌릴 수 없는 문서를 만드는 버튼은 빈 결과를 만들지 않아야 한다.** 개요의 '처리 대기'가 조직이
  존재하지도 않던 달의 청구서를 재촉했고, 따라가면 0원 초안 → '확정하기'로 바뀌어 재촉 → **0원 확정
  청구서**가 영구히 남았다(삭제·확정취소 API 가 없다). 자동 생성 잡에만 있던 활동 가드를
  `InvoiceService.generate` 로 옮겨 두 경로가 같은 규칙을 쓴다 + 화면은 `org.createdAt` 으로 재촉을 막는다.
- ★**세션 만료 뒤의 상태변경은 401 이 아니라 CSRF 실패 403 으로 온다**(토큰이 세션에 매여 있다).
  `ApiAuthenticationEntryPoint`(401)만 있고 짝이 없어서, SPA 의 모든 저장 화면이 한국어 화면에 영문
  **"Forbidden (403)"** 을 띄우고 로그인 이동도 하지 않았다. `ApiAccessDeniedHandler` 가
  `MissingCsrfTokenException` 을 `UNAUTHENTICATED` 로 표시해 프런트가 401 과 **같은 경로**로 처리한다
  (상태코드는 403 그대로 — 구분은 본문의 errorCode 가 진다).
  ★**org API(`/api/orgs` 이하)는 별도 체인(@Order 2)이라 기본 체인에 건 진입점·거부 핸들러가 닿지
  않는다** — 실제로 기본 체인에만 배선한 채 배포 직전까지 갔고, SPA 상태변경의 대부분(구성원·초대·
  식대정책·역할, 29개 호출 지점)이 그 체인이라 고쳤다는 결함이 가장 많이 쓰는 화면들에서만 그대로
  남아 있었다(2차 적대 리뷰에서 잡힘). 새 체인을 만들면 401/403 계약 배선을 반드시 함께 걸 것. 회귀:
  `HttpContractIntegrationTest` "세션 없는 상태변경" — **두 체인의 경로를 함께** 단언한다(상태코드는
  체인마다 401/403 으로 다른 것이 실측 동작이라 본문 errorCode 만 못박는다). 같은 이유로 프런트의
  403 세션-소실 판정은 `readError` 가 **합성한** UNAUTHENTICATED(비-JSON 본문)를 믿지 않는다 —
  믿으면 CORS 거절 같은 평문 403 에서 로그인 무한 루프가 된다(`synthesized` 표식).
- ★**"다시 시도" 라벨은 실제로 재시도할 때만.** 26곳이 `onRetry={mutation.clearError}` 였다 —
  저장에 실패한 사용자가 그 버튼을 누르면 **오류만 사라지고** 화면이 성공한 것처럼 조용해진다.
  `ErrorNotice` 에 `onDismiss`(="닫기")를 갈라 두었다.
- ★**게이트를 무음으로 잃지 마라.** `LoginFlowController` 에 `return "redirect:/login"` 이 21곳이었고,
  5분 TTL 이 지나면 **정답 코드를 넣은 순간에도** 아무 설명 없이 빈 로그인 화면으로 튕겼다(사용자는
  코드가 틀렸다고 오해). `LoginFlowSupport.gateLostRedirect` 가 **만료와 "애초에 시작 안 함"을 구분**해
  `?expired=1` 을 싣고, 식별자 화면이 LOGIN_HINT 로 이메일을 채운다. TTL 은 10분(이메일 코드에 5분은 짧다).
- ★**리다이렉트로 돌려보낼 수 없는 OAuth 오류는 화면도 로그도 없었다.** 미등록 client_id·redirect_uri
  불일치가 Boot 기본 Whitelabel(영문·원인 무표시)로 끝나고 WARN 도 감사로그도 0 건이라, 연동 담당자도
  플랫폼 관리자도 원인을 알 방법이 **존재하지 않았다**. `OAuth2ErrorPageHandler` 가 한국어 화면 + WARN
  (client_id·redirect_uri·상관관계 ID)을 남긴다. HTML 이 아니면 표준 OAuth2 오류 JSON.
  ★★**이 핸들러는 SAS 기본 실패 처리를 통째로 대체하므로, RP 로 돌려보낼 수 있는 오류는 반드시
  직접 리다이렉트해야 한다**(RFC 6749 §4.1.2.1). 처음엔 그 갈래가 없어서 동의 화면의 **"거부"**
  (`access_denied`)·미등록 scope(`invalid_scope`)까지 400 화면으로 삼켰다 — 사용자는 앱으로 돌아갈
  길이 없고 연동 앱은 취소를 영영 통보받지 못한다(2차 적대 리뷰에서 잡힘). 판정·URL 조립은 SAS
  `sendErrorResponse` 와 동일(1.4.2 바이트코드 실측). 회귀:
  `oidc/OAuth2AuthorizeErrorIntegrationTest`(3 — 화면 2방향 + **리다이렉트 방향**. 화면 방향만
  테스트하면 리다이렉트가 깨져도 초록불이다 — 정확히 그 상태로 배포될 뻔했다).
- ★**"친절한 문구"가 정보를 지우지 않게.** `adminErrorText` 가 `VALIDATION_ERROR` 를 무조건
  "입력값을 확인하세요."로 덮어, 서버가 정확한 사유를 준 경우에도 관리자는 **같은 값을 다시 넣고 다시
  실패**했다. 지금은 **OVERRIDE**(서버가 영어 상수만 주는 코드)와 **FALLBACK**(서버 문구가 정보를 나르는
  코드)을 가른다. 회귀: `web/app/admin/_lib/errors.test.ts`(5, **영어 기본 상수 대조군** 포함).
- ★**게이트에 우회로가 있으면 게이트가 아니라 장식이다.** 클라이언트 시크릿 모달의 "저장했습니다" 체크는
  푸터 버튼만 막았고 **ESC 한 번**이면 지나쳤다 — 서버는 해시만 저장하므로 그 값은 영영 사라진다.
  `Modal(locked)` 이 ESC·바깥 클릭·X 를 모두 막는다(제어 모드에서 `onOpenChange(false)` 를 무시).
- ★**동의 화면은 사용자가 무엇에 동의하는지 말해야 한다.** 19개 scope 중 16개가 원문 식별자로 떠서
  사용자가 "org.roles" 에 동의하고 있었다. `I18nMessagesTest` 가 **`application.yml` 을 파싱해**
  `taspa.allowed-scopes` ⊆ `ConsentController.SCOPE_DESCRIPTION_KEYS` 를 강제한다(프로브 확인).
- ★**설정이 있는데 안 도는 것은 없는 것보다 위험하다.** `taspa.client.audience` 는 선언만 있고 아무 데서도
  읽히지 않아, 연동 개발자는 검증된다고 믿는데 **다른 서비스의 토큰이 그대로 통과**했다. 지금은 실제
  검증한다(`AudienceValidatorTest` 4 — aud 없는 토큰도 거부하는 fail-closed 포함).
- ★**문서가 1순위로 권하는 경로가 실제로 동작해야 한다.** 스타터에 발행 설정이 **아예 없어서**
  `com.taspa:taspa-spring-boot-starter` 좌표는 taspa 저장소 안에서만 성립했다. `maven-publish` +
  artifactId 고정(디렉터리 이름을 그대로 쓰면 `com.taspa:spring-boot-starter` 가 되어 문서와 어긋난다).
- ★**"돌아갈 곳"이 될 수 없는 화면**(`/login`·`/logout`·`/reauth`·`/signup`·`/error`)은 `RequestCache`
  매처에서 제외한다. 없던 동안 로그아웃 후 재로그인이 **"로그아웃하시겠습니까?" 화면에 착지**했다.
- ★**step-up 리다이렉트는 작성 중이던 입력을 지운다** — 돌아온 화면이 아무 말도 안 하면 사용자는 저장이
  끝났다고 믿고 떠난다(실패했다는 사실 자체가 어디에도 없다). `AppShell.InterruptedNotice` 가
  `sessionStorage` 표식을 읽어 한 번 알린다. ★읽기는 **effect 의 setState 가 아니라**
  `useSyncExternalStore` 로 — 그 규칙(`react-hooks/set-state-in-effect`)이 CI 게이트다.
- ★**화면 게이트는 서버 인가보다 항상 넓게.** 조직 콘솔 껍데기 게이트를 `/api/orgs/mine`
  (=조직관리자 목록) 하나로 판정했더니, 서버가 정당하게 허용하는 **부서 서브트리 위임자**와
  **조직 커스텀 역할 보유자**까지 콘솔 전체에서 잠겼다 — 원래 결함(비멤버에게 403 껍데기)을 고치려다
  권한 있는 사람을 막는 더 나쁜 상태를 만든 것(2차 적대 리뷰에서 잡힘). 지금은 **멤버십**
  (`/api/orgs/memberships`)으로 판정한다 — 화면 게이트를 좁히면 그 차이만큼이 락아웃이고,
  넓으면 최악이 "껍데기가 보인다"다. 가맹 콘솔의 `blocked`(PENDING/SUSPENDED 담당자)도 같은 규칙 —
  껍데기 대신 NoAccessCard 가 사유를 말한다.
- ★**권한 없음은 오류가 아니라 상태다.** 남의 조직 콘솔 URL 을 연 사람이 10탭짜리 완전한 화면 안에서
  붉은 오류 8개와 **영원히 실패할 '다시 시도' 6개**를 만났다. `NoAccessCard`(재시도 버튼 **없음**)로
  갈랐다 — 조직·가맹 콘솔 양쪽. 플랫폼 관리자는 통과(그게 그 역할의 정의다).
- ★**안내가 사실과 달라도 화면은 멀쩡해 보인다**(그래서 오래 남는다): 조직 콘솔 빈 화면의 "다시
  로그인하면" — `/api/orgs/mine` 은 라이브 조회라 필요 없다(재로그인이 필요한 건 플랫폼 ADMIN 뿐).
  IAM 락아웃의 "플랫폼 관리자 계정인지 확인하세요" — `/admin/**` 은 이미 `hasRole` 을 통과했으므로
  그 뜻일 수가 없다(항상 "IAM 정책이 거부했다"다).
- ★**CSV 대량 초대의 부서 이름은 조용히 안 이어질 수 있다**(오타·미생성·동명). 행 결과는 CREATED 인데
  그 신입만 부서 재정의를 못 받아 **개발팀 18,000원 대신 12,000원**을 쓴다. `BulkInvitationRowResult.warning`
  으로 성공과 나란히 경고한다(실패가 아니므로 REJECTED 로 만들 수 없다).
- ★**정지 계정은 "어디서 알리느냐"가 해법의 전부다**(같은 결함의 두 절반이 해법이 정반대다).
  로그인 화면에서 정지 사실을 말하면 **계정 열거**가 된다 — 스프링의 `DisabledException` 은 비밀번호
  검증 **전에** 던져지는 사전 검사라, 아무나 이메일만 넣어 계정의 존재와 상태를 확인할 수 있다.
  그래서 거기서는 일반 실패로 수렴시킨다(현행 유지). 대신 **비밀번호 재설정**은 메일 토큰으로 이메일
  소유가 이미 증명된 지점이라 알려도 된다 — 그전에는 재설정이 정상 완료되고 사용자가 방금 자기가 정한
  비밀번호로 로그인했다가 "올바르지 않습니다"를 보고, 다시 재설정하고, 같은 곳으로 돌아왔다(원인은
  화면 어디에도 없는 무한 루프). 회귀는 **두 방향을 함께** 고정한다:
  `credential/PasswordResetIntegrationTest`(재설정은 거절+사유 / **로그인은 감춘다는 대조군**).
- ★**"사유를 실어 보냈다"와 "사유가 보인다"는 다르다.** 죽은 재설정 링크를 재요청 화면으로 보내면서
  `error` 모델 속성을 실었는데, 그 템플릿에 **오류 슬롯이 없어** 사용자는 링크를 눌렀더니 갑자기 처음
  화면이 나온 것으로 봤다 — 고치려던 침묵이 한 화면 뒤로 옮겨졌을 뿐이었다(실측으로 발견).
  리다이렉트로 사유를 넘길 때는 **받는 템플릿이 그 슬롯을 그리는지** 확인할 것.
- ★**POS 는 어느 매장인지 화면에 말해야 한다.** 결속은 환경변수 안에만 있어서 계산원이 눈앞의 화면이
  자기 가게 것인지 확인할 방법이 없었다(단말을 옮겨 설치하면 곧 "옆 가게 이름으로 승인"이고 발견은
  월말이다). `GET /api/merchant/me`(승인과 **같은 action** — 새 action 은 기존 클라이언트를 전부 거부한다).
  '다음 손님' 뒤에도 최근 승인 행에서 **같은 취소·환불 패널을 다시 연다**(재승인이 아니라 재오픈).

## 운영(Production) 준비

- **프로파일 고정**: Dockerfile 이 `SPRING_PROFILES_ACTIVE=prod` 를 내장한다. 빠지면 CSP/HSTS·rate limit·
  graceful shutdown·SMTP 타임아웃·XFF 신뢰·issuer https 검증이 **전부 꺼진 채 정상 기동**하고
  `ProductionSafetyValidator` 조차 생성되지 않는다(모든 fail-fast 의 단일 실패점) — 되돌리지 말 것.
  ★그 대가로 **로컬 데모 구성은 프로파일을 명시해야 한다**: `docker-compose.yml` 의 `app` 서비스는
  주석에 "로컬 데모에는 prod 를 쓰지 않는다"고 적혀 있으면서 정작 오버라이드가 없어 이미지 기본값
  그대로 prod 로 떴다(= 기동 실패). 지금은 `SPRING_PROFILES_ACTIVE: dev` 를 명시한다.
- **필수 환경변수 fail-fast 는 두 층이다**(`config/RequiredProdEnvValidator` → `ProductionSafetyValidator`).
  - **있는가**: `EnvironmentPostProcessor` 가 플레이스홀더 해석보다 **앞서** 돌며 누락을 **한 번에 전부**
    나열한다. 그전에는 `MAIL_HOST` 하나가 없을 때 배포자가 받는 것이
    `Error processing condition on MailSenderAutoConfiguration` 로 시작하는 스택트레이스였고(진짜 원인은
    맨 아래 `Caused by` 한 줄), 하나 고칠 때마다 다음 변수에서 다시 죽었다. 더 나쁜 것은 **순서**다 —
    `ProductionSafetyValidator` 는 값이 이미 풀린 뒤에 도는 빈이라 **아예 실행되지 않았다**(키 강도·
    issuer https 안내가 나올 기회 자체가 없었다).
  - **쓸 만한 값인가**: https·비-localhost·dev 기본 비밀번호·키 강도·두 키 분리(기존 그대로).
  - ★**등록은 `META-INF/spring.factories` 로만 된다.** Boot 3.4 의 `EnvironmentPostProcessorsFactory`는
    `SpringFactoriesLoader` 만 쓴다 — 자동설정에 쓰는 `.imports` 방식으로 등록하면 **조용히 무시**되어
    검증기가 있는데도 예전 그대로 실패한다(실제로 그렇게 만들어 컨테이너에서 확인했다). 그래서 회귀
    테스트가 **실제 `SpringApplication` 을 prod 로 띄운다**(refresh 전에 던지므로 DB 없이 즉시 끝난다).
    단위 테스트만으로는 이 실수가 통과한다 — 프로브로 확인: 등록 파일을 지우면 그 테스트만 실패한다.
  - 목록과 `application-prod.yml` 의 대응도 테스트가 강제한다(yml 을 파싱해 양방향 비교). 주석 규약으로
    두면 새 변수를 추가할 때 그 변수만 옛 실패 형태로 되돌아간다.
- **★배포 매니페스트의 선택 변수는 `KEY:`(값 없는 맨 키)로 쓴다 — `${KEY:-}` 금지**(적대 리뷰에서
  잡힌 blocker). `${KEY:-}` 는 미설정 시 변수를 **빈 문자열로 정의**하는데, 스프링 플레이스홀더 기본값
  (`${A:${B}}`)은 값이 **없을 때만** 발동하고 비었을 때는 발동하지 않는다. 그래서
  `TASPA_PUBLIC_BASE_URL: ${TASPA_PUBLIC_BASE_URL:-}` 이던 동안 **문서대로 배포하면 서버가 뜨지 못했다**
  (README·예시 파일이 그 변수를 "선택"이라 적어 아무도 설정하지 않는다 → base-url 이 빈 문자열 →
  `ProductionSafetyValidator` 거부 → `restart: unless-stopped` 크래시 루프, 그것도 배포자가 설정한 적
  없는 속성 이름으로). 맨 키 형태는 `--env-file` 값이 있으면 전달하고 없으면 **변수를 만들지 않는다**(실측).
  같은 함정이 `TASPA_METRICS_SCRAPE_PASSWORD` 에도 있었다 — 빈 문자열도 `@ConditionalOnProperty` 는
  "존재"로 보므로 메트릭 미사용 배포에서 전용 Basic 체인이 켜진 채 **스크레이퍼도 ADMIN 도** 못 들어간다.
  ★그래서 리허설이 이제 **환경변수 집합을 매니페스트에서 읽는다**(`docker compose config --format json`).
  손으로 적은 `-e` 목록을 검증하면 "배포될 산출물"이 아니라 리허설이 지어낸 조합을 검증하게 되고,
  실제로 그 때문에 이 결함을 구조적으로 못 봤다. 매니페스트에 새 변수가 생기면 리허설이 이름을 대며 실패한다.
- **가입은 모든 경로에서 인증 코드를 보낸다**(`AccountService.signup` 안 — 컨트롤러가 아니다).
  ★서버 렌더링 `/signup` 만 발송하고 **공개 JSON API `POST /api/accounts/signup` 은 하지 않던** 시기가
  있었다. 그 경로로 가입한 사용자는 로그인하는 순간 "…으로 보낸 6자리 코드를 입력하세요" 화면에 도착하는데
  **메일이 한 통도 가지 않았다** — 화면이 존재하지 않는 메일을 기다리라고 말하므로 사용자는 받은편지함만
  새로고침하고, "다시 보내기"를 눌러야 한다는 것을 스스로 추측해야 한다.
  `MfaAwareAuthenticationSuccessHandler` 의 주석("EMAIL_VERIFICATION 은 가입 시 이미 발급됨")이 이미 이
  불변식을 전제하고 있었다 — 전제를 코드가 아니라 **호출자 규약**에 맡긴 것이 결함의 형태다.
  e2e 가 전부 HTML `/signup` 을 쓰는 바람에 **아무 테스트도 그 경로를 밟지 않았고**, prod 리허설에서야
  드러났다. 회귀: `account/AccountControllerIntegrationTest`(발송 + **거절 시 무발송 대조군**).
  ★**실제 SMTP 발송은 커밋 이후**다(`EmailVerificationService.sendCodeAfterCommit` —
  `OrgInvitationService.sendInvitationAfterCommit` 과 같은 규약). 열린 트랜잭션으로 SMTP 왕복을 기다리면
  가입이 공개 엔드포인트라는 이유만으로 **커넥션 풀이 워커 풀보다 20배 먼저 죽고**, 롤백된 트랜잭션의
  코드를 담은 메일이 나가면 사용자는 "보낸 코드"를 입력하는데 영원히 실패한다. 대가로 발송 실패는
  가입을 되돌리지 않는다(계정은 남고 "다시 보내기"로 복구 — 어차피 그 순간엔 재발송도 실패하므로
  계정을 지워 봐야 사용자가 얻는 것이 없다). 회귀: `verification/EmailVerificationMailTimingIntegrationTest`
  (**롤백 시 무발송** + 커밋 시 발송 대조군 — 정상 경로만 보면 인라인 호출과 구별되지 않는다).
- **★수신자를 요청자가 정하는 발송 경로는 전부 rate limit 버킷에 묶는다**(`RateLimitFilter`).
  사용자 단위 쿨다운(`EmailVerificationService.resend` 의 60초)은 **이미 그 사용자에게 보낸 적이 있을
  때만** 작동하므로, 매번 새 주소를 대는 공격에는 아무 제한이 되지 못한다. 현재 묶인 경로:
  `POST /signup`·`/api/accounts/signup`·`/login/social-email`(같은 `signup` 버킷 — HTML/JSON/소셜 보완을
  나누면 번갈아 쳐서 실효 한도가 배가 된다) + `POST /api/account/email/change`(별도 버킷 — 계정이
  있어야 하므로 표적이 다르고, 가입 폭주가 정상 사용자의 주소 변경을 굶기면 안 된다).
  ★`/login/social-email` 은 **호출마다 새 users 행까지 만든다** — 발송량과 테이블 증식이 같이 간다.
- **로그의 상시 WARN 은 그 자체로 비용이다**: `hibernate.dialect` 명시를 제거했다(HHH90000025). 무해한
  WARN 이 매 기동 최상단에 뜨면 사람이 WARN 을 훑지 않는 습관이 들고, 진짜 경고가 그 습관에 묻힌다.
- **★사용자에게 나가는 링크 base 는 세 개이고 셋 다 `ProductionSafetyValidator` 가 검사한다**
  (비밀번호 재설정·매직링크·**조직 초대**). 초대 base 에 prod 오버라이드가 없던 동안 prod 에서
  **조직 초대 메일과 청구서 초안 알림 링크가 `http://localhost:9100`** 을 가리켰다 — 받는 사람에겐
  그냥 열리지 않는 링크라 온보딩이 통째로 막히는데 서버 로그에는 아무 흔적도 없다(발송은 성공이므로).
  셋은 같은 키(`TASPA_PUBLIC_BASE_URL:${TASPA_ISSUER_URI}`)를 공유해야 한다 — 하나만 고치면 그 메일만
  조용히 다른 호스트를 가리킨다. 새 메일 링크를 추가하면 **검증기 목록에도 반드시 넣을 것**
  (넣지 않으면 다음에 또 같은 형태로 새고, 그때도 로그에는 안 보인다).
- **배포 산출물 2종**(`deploy/`): 매니페스트 `docker-compose.prod.yml`(+`.env.prod.example`)와
  **리허설 `rehearsal/run.sh`**. 리허설은 prod 이미지를 실제로 띄워 빈 DB 마이그레이션 → 가입 → 메일
  코드 → 게이트 → 세션 API → 관리 콘솔 쓰기를 밟고, 보안 헤더·메트릭 인증·rate limit·graceful shutdown·
  **기동 로그 WARN 0** 까지 본다. 자기 컨테이너만 지운다(개발용 컨테이너 무해).
  ★**e2e 가 대신하지 못한다** — Playwright 는 dev 프로파일(시딩·localhost issuer·약한 시크릿)을 전제한다.
  위에 적은 결함 3종(환경변수 안내·가입 코드 미발송·compose 프로파일)은 **서버 통합 테스트 96 클래스와
  e2e 22 건을 모두 통과한 상태**에서 이 리허설로만 드러났다. 새 배포 전제를 추가하면 여기에 검사도 추가할 것.
  ★`.gitignore`/`.dockerignore` 의 `.env`·`*.env` 패턴은 **`.env.prod` 를 잡지 않는다**(앞은 정확히 `.env`,
  뒤는 `.env` 로 끝나는 이름) — `.env.*` 와 `deploy` 를 명시로 추가했다.
  ★**리허설 스크립트 자체의 함정 3종**(전부 실제로 거짓 실패를 냈다 — 거짓 실패는 거짓 통과만큼 나쁘다.
  사람이 이 스크립트의 실패를 무시하게 만든다):
  ① `docker logs --since` 에 넘기는 타임스탬프는 **`Z` 를 붙여야 한다**. 타임존이 없으면 docker 가
  **로컬 시각**으로 읽어, KST 에서 `date -u` 값을 그대로 주면 9시간 전으로 해석돼 `--since` 가 무효가 된다
  (첫 기동의 정상 WARN 이 잡혀 "기동 로그 WARN 0" 검사가 뒤집혔다).
  ② `set -o pipefail` 아래에서 **`큰출력 | grep -q` 를 쓰지 마라**. `grep -q` 는 첫 매치에서 즉시 끝나
  파이프를 닫고, 상류(`docker logs`)가 SIGPIPE(141)로 죽어 **찾았는데도 파이프라인이 실패**가 된다
  (프로브 재현: 20만 줄 입력에 `pipeline status=141`). 로그는 변수에 받아 here-string 으로 검사한다.
  ③ 집계 수치를 **이름표 붙여 sed 로 뽑지 마라**. `.*PARSED=\(...\)` 의 탐욕 매칭이 `UNPARSED=` 안의
  `PARSED=` 를 집어 **다른 값을 읽는다**(프로브: `PARSED=123 UNPARSED=0` → 123 이 아니라 0). 정상 실행이
  "검사한 로그가 0줄"이라는 거짓 실패를 냈다. 위치 인자로 싣고 `read -r a b c` 로 받는다.
- **헬스체크 분리**: 컨테이너 HEALTHCHECK 는 `/actuator/health/liveness`(집계 health 아님). 집계에는 mail
  기여자가 포함돼 SMTP 장애가 컨테이너 재시작으로 번진다. prod 는 `management.health.mail.enabled=false` 로
  이중 분리. **프로브는 base `application.yml` 에서 항상 켠다** — 프로파일에 따라 404 가 되면 헬스체크가
  재시작 루프를 만든다(프로브 존재를 프로파일과 분리).
- **★DB 세션 타임아웃(무한 대기 방어선, `application.yml` 의 hikari `data-source-properties.options`)**:
  `lock_timeout=3s` + `idle_in_transaction_session_timeout=60s`. **base(전 프로파일)에 둔다** — prod 전용이면
  값이 틀렸다는 걸 프로덕션에서 알게 되고, 통합 테스트가 같은 타임아웃 아래 돌아야 미래의 자기 교착이
  "무한 정지"가 아니라 몇 초 만의 테스트 실패가 된다. 근거: 초대 수락 자기 교착이 무한 대기였던 이유가
  타임아웃 0 이었다(위 "비관적 잠금 불변식"). `idle_in_transaction_...` 은 **`statement_timeout` 이 절대
  잡지 못하는** "트랜잭션은 열렸는데 DB 에 아무 말도 안 하는" 바깥 세션을 잡는 유일한 수단이다
  (하한은 `PasswordResetService` 가 트랜잭션 안에서 도는 HIBP 3s + bcrypt ~0.4s ≈ 3.5초).
  - **`statement_timeout` 은 의도적으로 켜지 않는다.** `RetentionCleanupJob` 의 oauth2_authorization 정리는
    `GREATEST(...)` 라 인덱스를 못 타는 순차 스캔 DELETE 이고 **단일 트랜잭션**이라, 한 문장이 죽으면 8개
    DELETE 가 통째로 롤백된다 — 매일 새벽 조용히 실패하고(스케줄 예외는 ERROR 로그 한 줄) 테이블이 무한
    증가한다. 켜려면 그 잡의 `SET LOCAL statement_timeout = 0`(또는 배치 DELETE)이 **세트로** 필요하다.
    같은 이유로 그 잡은 `SET LOCAL lock_timeout = '30s'` 로 잠금 대기만 완화해 둔다.
  - **JDBC URL 에 넣지 말 것** — `IntegrationTestBase` 의 `@DynamicPropertySource` 가 `spring.datasource.url`
    을 통째로 갈아끼워 테스트에서 조용히 사라진다. **`ALTER ROLE/DATABASE ... SET` 도 금지**(별도
    `flyway migrate` 잡의 커넥션까지 오염). 적용 여부는 회귀 테스트가 `pg_settings` 로 단언한다
    (`OrgInvitationServiceIntegrationTest` — 조용한 미적용이 최악이라서).
  - **마이그레이션은 앱 풀을 쓰지 않는다**(`config/FlywayConnectionConfig`). `CREATE INDEX CONCURRENTLY`
    (V30~)는 lock_timeout·statement_timeout 양쪽에 죽고 실패하면 **INVALID 인덱스**를 남기는데
    `IF NOT EXISTS` 가 이름만 보고 건너뛰어 무증상 성능 붕괴가 된다. `spring.flyway.init-sqls` 로
    `SET ... = 0` 하는 우회는 **쓰지 마라** — Flyway 가 빌린 풀 커넥션에 그 SET 이 남아 풀 일부가
    타임아웃 없는 커넥션이 된다(실측으로 확인). 전용 커넥션을 주는 쪽이 정답이다.
- **커넥션 풀이 워커 풀보다 20배 먼저 죽는다**: 교착 요청 1건이 커넥션 2개(바깥+REQUIRES_NEW)를 묶으므로
  prod 풀 20 → 요청 10건에 전멸(워커는 200). 그래서 풀 지표가 사실상 유일한 실시간 조기 경보다.
  `leak-detection-threshold: 60000`(60초 이상 커넥션 보유 스레드의 **스택 트레이스** WARN),
  `server.tomcat.mbeanregistry.enabled: true`(이게 false 면 `tomcat_threads_busy_threads` 게이지가 **아예
  등록되지 않는다** — micrometer 가 JMX 질의로 읽기 때문). 완료 시점에만 기록하는 `http_server_requests`
  타이머에는 **끝나지 않는 요청이 영원히 안 나타난다** — 진행 중 지표(`http_server_requests_active_*`,
  기본 on)와 `hikaricp_connections_pending/timeout_total` 을 봐야 한다.
- **메트릭 스크레이핑**: `TASPA_METRICS_SCRAPE_PASSWORD` 설정 시에만 `/actuator/prometheus` 전용 Basic 체인
  (`MetricsSecurityConfig`, @Order(-2), STATELESS, `ROLE_METRICS`, 앱 계정과 분리, 상수시간 비교) 등록.
  빈 기본값을 yml 에 두지 말 것 — `@ConditionalOnProperty` 는 빈 문자열도 "존재"로 판정한다.
- **암호화 키 강도**: prod 는 blank·짧은 키·저엔트로피·**두 키(mfa/jwk) 동일 값**을 기동 거부한다
  (`ProductionSafetyValidator`/`EncryptionConfig`). 이전엔 dev 기본 문자열 하나만 막아 `MFA_ENCRYPTION_KEY=x`
  가 통과했다 — 키는 salt 없는 SHA-256 1회로 AES 키가 되므로(`AesEncryptionService`) 약한 값은 DB 유출 시
  즉시 복원 → `jwk_keys` 복호화 → **발급자 서명 개인키** → 전 연동 서비스 토큰 위조로 이어진다.
  생성은 `openssl rand -base64 32`. 이미 두 키를 같은 값으로 운영 중이면 교체 전 재암호화 절차 확인
  (`docs/architecture.md`).
- **콜백 타임아웃**: 소셜 토큰 교환(`FederatedTokenClient`, 5s)+ userinfo(`FederatedUserServices`, 5s).
  토큰 엔드포인트가 비어 있으면 공급자 지연이 워커 고갈 → IdP 전면 마비로 번진다.
- **관측성**: 요청 상관관계 `CorrelationIdFilter`(MDC `correlationId`, 인바운드 `X-Request-Id` 검증·정규화로
  로그 인젝션 차단, finally 로 MDC 제거, 응답 반향). prod 콘솔은 Boot 3.4 내장 구조화 로깅(ECS JSON,
  외부 의존성 0) — 기존 평문 파서가 있다면 배포 전 전환 필요.
- **다중 인스턴스**(V35 에서 유일한 **실패** 항목 해소): `OrgDomainReverifyJob` 은 V28
  `last_reverify_failure_on` 으로 **날짜 멱등**(카운터가 "연속 실패 일수" — 무잠금 증가로 임계가 하루에
  소진되던 버그 수정). 패스키 등록 옵션은 `passkey/JdbcCreationOptionsRepository` 로 **DB 영속화**했다.
  - 그전에는 옵션이 발급 인스턴스의 힙에만 있어, LB 뒤에서 "A 가 발급 → 자격증명 POST 가 B 로" 가면
    등록이 실패했다(사용자에겐 지문을 찍었는데 아무 일도 안 일어남 + 재시도해도 같은 확률로 반복).
  - 객체를 통째로 저장할 수 없다: `PublicKeyCredentialCreationOptions` 는 Serializable 이 아니고,
    **Jackson 왕복도 불가**하다 — `WebauthnJackson2Module` 믹스인은 직렬화 전용이라 역직렬화 creator 가
    없다. 그래서 필드를 풀어 저장하고 로드 시 재구성한다. ★`passkey/CreationOptionsSerializationTest` 는
    "된다"가 아니라 **"안 된다"를 못박는** 테스트다(`InvalidDefinitionException` 단언) — 다음 사람이 같은
    막다른 길을 다시 걷지 않게. 라이브러리가 역직렬화를 지원하면 그 테스트가 실패하고, 그게 저장소를
    단순화할 신호다.
  - ★재구성이 충분한 근거(6.4.4 바이트코드 실측): `Webauthn4JRelyingPartyOperations.registerCredential` 이
    옵션에서 읽는 것은 **rp · challenge · authenticatorSelection.userVerification · pubKeyCredParams ·
    user** 다섯 뿐이고 `WebAuthnRegistrationFilter` 는 필드를 직접 읽지 않는다. excludeCredentials·
    extensions 는 브라우저가 **생성 시점에** 소비해 검증에 관여하지 않으므로 복원하지 않는다.
  - 회귀: `passkey/PasskeyRegistrationSessionIntegrationTest`(3 — 왕복 + **검증기에 전달되는 옵션을
    붙잡아 발급 응답과 대조**. 왕복 테스트만으로는 재구성 충실도를 못 본다, registerCredential 이 스텁이라)
    + e2e `passkey.spec.ts`(가상 인증기로 실제 등록·로그인 — 최종 증거).
  - **남은 항목은 실패가 아니라 감쇠다**(정직한 구분): 앱단 rate limit 은 인스턴스별 카운팅이라 실효 한도가
    인스턴스 수만큼 커진다(게이트웨이 제한이 1차 방어라는 전제는 그대로). JWK(60초)·SSO 등록 캐시는
    DB 값의 TTL 캐시라 **반영 지연**일 뿐 오동작이 아니다.
- **CI**: `.github/workflows/ci.yml` — `build-and-test`(서버) · `web-build`(웹, 병렬) → `docker-build`.
  - **ktlint 는 실질 게이트다**(`ignoreFailures=false` + baseline). detekt 는 Kotlin 2.1.10 비호환으로 배제.
    - baseline(`server/config/ktlint/baseline.xml`)에 기록된 위반만 유예하고 **그 뒤 새 위반은 빌드를 깨뜨린다.**
      한동안 `ignoreFailures=true` 였는데 그러면 리포트만 쌓이고 **아무것도 막지 않는다**.
    - ★`ktlintGenerateBaseline` 재실행은 **기존 위반을 새로 사면하는 행위**다. 지금 실패하는 것을 통과시키려고
      돌리면 게이트가 다시 장식이 된다 — 규칙을 의도적으로 바꿀 때만.
    - 프로브로 확인: 위반 있는 파일을 추가하면 `ktlintMainSourceSetCheck` 가 FAILED, 지우면 통과.
      ★단 파일을 만들고 곧바로 지우면 gradle VFS 스냅샷이 삭제를 못 봐 **없는 파일로 계속 실패**한다
      (`--rerun-tasks` 로 확인할 것 — 제품 결함으로 오해하기 쉽다).
    - ★★**`ktlintFormat` 은 전 코드베이스를 재포맷한다**(파일 지정 옵션이 아니다). 실제로 384/453 파일이
      한 번에 바뀌었고, 그중 **보이지 않는 문자를 조용히 삭제**했다. 피해는 두 층이었다:
      ① 문자 리터럴(`'\uFEFF'`)이 빈 문자 리터럴이 돼 **컴파일이 깨졌다**(그래서 발견) —
      ② `CsvWriter.BOM` **문자열 상수가 빈 문자열이 됐다**(컴파일은 통과, 스위프에서 4개 클래스 실패).
      ②가 배포되면 윈도우 엑셀이 UTF-8 을 로컬 코드페이지로 읽어 **파일은 열리는데 한글이 전부 깨진다** —
      받는 사람이 우리 결함으로 인지하지도 못한다. **대량 포맷 후 컴파일 성공은 아무 증거가 아니다**;
      전체 스위프를 돌릴 것.
    - ★**보이지 않지만 의미 있는 문자는 반드시 이스케이프로 쓴다**(`"\uFEFF"`, `'\uFEFF'`).
      리터럴로 넣으면 에디터·리뷰·컴파일 어디에도 보이지 않아 어떤 도구가 지워도 알 수 없다.
      NBSP 를 들여쓰기로 쓰는 곳(부서 트리 라벨)은 의도된 사용이므로 그대로 둔다.
  - **kover 는 리포트만**(임계 게이트 없음). ★로컬에서 임계값을 정할 수 없다: 커버리지 리포트는 실행 단위로
    덮이는데 이 장비에서는 전체 테스트를 한 JVM 에서 못 돌린다(위 ryuk 교착) — 클래스별 실행으로는 마지막
    클래스만 반영돼 1.8% 같은 값이 남는다. 임계 설정은 CI(Linux) 측정값을 근거로 해야 한다.
  - **web 게이트**: typecheck → **lint** → **format:check** → unit test → build. lint·format 게이트가 없던
    동안 규칙 위반이 조용히 쌓였다(렌더 중 setState·렌더 중 내비게이션이 그렇게 남아 있었다).
    포맷은 `prettier`(devDependency, `printWidth: 110`) — 문서(`*.md`)는 `.prettierignore` 로 제외한다
    (손으로 맞춘 줄바꿈이 문단의 읽는 순서를 만든다).

## Roadmap

Phase 1 코어+OIDC → Phase 2 MFA/이메일검증/패스키(WebAuthn)/키회전·audit 영속화/세션 영속화·원격 세션 관리 완료 →
Phase 3 클라이언트 SDK 확충/서비스 간 인증 → Phase 4 리스크 기반 인증(선행 구현 완료 — `risk/`) →
Phase 5 (현재) 정책 RBAC shadow → 집행 승격(위 "집행 승격 전 결정 필요" 해소 후), IAM Role+AssumeRole(2단계),
permission boundary·SCP·리소스 기반 정책(3단계)
