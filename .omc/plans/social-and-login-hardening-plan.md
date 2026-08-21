# 소셜 로그인 + 신뢰 기기 + 로그인 알림 + step-up + 매직 링크 구현 스펙

리서치(공급자 실측 + 보안 모범사례)로 확정된 스펙. "확정 사실"은 재조사 없이 신뢰할 것.
구현은 2단계: **Stage A(소셜 로그인)** → **Stage B(신뢰 기기·알림·step-up·매직 링크)**. Stage B는 A의 결과에 통합된다.

## 공통 원칙 (기존 설계 유지)

- 게이트 불변식: 부분 인증 상태는 SecurityContext에 절대 넣지 않는다 — 세션 속성(PendingAuth)만. `LoginFlowSupport`(clearSecurityContext/startPending/completeAuthentication) 재사용·확장.
- 완전 인증 principal은 항상 로컬 `UserDetails` 기반 `UsernamePasswordAuthenticationToken` — 소셜 로그인도 최종적으로 `completeAuthentication`으로 승격해 principal 모델을 통일한다 (`OAuth2AuthenticationToken`을 세션에 남기지 말 것 — 기존 코드의 principal 캐스팅이 깨짐).
- **소셜 로그인도 로컬 MFA 게이트를 적용한다** (Auth0/Okta 모델). 예외는 유효한 신뢰 기기 쿠키뿐. 패스키만 게이트 생략 유지(자체 2요소).
- `./gradlew build` 전체 통과가 각 Stage 완료 기준 (Docker 켜져 있음).

---

# Stage A — 소셜 로그인 (구글·카카오·네이버)

## A-1. 확정 사실 (리서치 실측)

- 구글: `CommonOAuth2Provider.GOOGLE` 내장 (provider 블록 불필요). OIDC, id_token에 `email`, `email_verified`. user-name-attribute `sub`.
- 카카오: **순수 OAuth2 방식 채택** (OIDC도 지원하지만 issuer-uri는 기동 시 디스커버리 HTTP 호출 발생 + email_verified 판단은 v2/user/me가 명확).
  - authorization-uri `https://kauth.kakao.com/oauth/authorize`, token-uri `https://kauth.kakao.com/oauth/token`, user-info-uri `https://kapi.kakao.com/v2/user/me`, user-name-attribute `id`, `client-authentication-method: client_secret_post` **필수 명시**, scope `account_email, profile_nickname, profile_image`.
  - 응답: 최상위 `id`(Long), `kakao_account: { email, is_email_valid, is_email_verified, profile: { nickname, profile_image_url } }`. 이메일은 동의 거부 시 **없을 수 있음**. 검증된 이메일 = `is_email_valid && is_email_verified` 둘 다 true.
- 네이버: OIDC 미지원. authorization-uri `https://nid.naver.com/oauth2.0/authorize`, token-uri `https://nid.naver.com/oauth2.0/token`, user-info-uri `https://openapi.naver.com/v1/nid/me`, 응답 `{resultcode, message, response:{id, email, name, nickname, profile_image}}`. **이메일 검증 플래그 없음 → 항상 미검증 취급.** 커스텀 UserService에서 `response` 평탄화 후 `id`를 name attribute로 하는 DefaultOAuth2User 재구성.
- SAS 공존: 충돌 없음 (공식 가이드 존재). oauth2Login은 @Order(2) 체인. `/oauth2/authorization/**`(버튼 링크), `/login/oauth2/code/**`(콜백) permitAll. SAS의 `/oauth2/authorize`와 경로 충돌 없음.
- 조건부 등록: yaml 등록 방식은 client-id 비면 기동 실패 → **프로그래매틱 `ClientRegistrationRepository` 빈** (환경변수 `GOOGLE_CLIENT_ID/SECRET`, `KAKAO_CLIENT_ID/SECRET`, `NAVER_CLIENT_ID/SECRET` 쌍이 있는 것만 등록). 0건이면 빈을 만들지 말고(InMemory는 빈 리스트 불허) `ObjectProvider<ClientRegistrationRepository>`로 존재할 때만 `http.oauth2Login(...)` 적용. 로그인 페이지 버튼은 repository(Iterable) 순회로 등록된 것만 렌더링.
- 테스트: ① spring-security-test `oauth2Login()` post-processor(핸들러 이후 로직), ② WireMock으로 token/userinfo 스텁 + `/oauth2/authorization/kakao` → state 추출 → 같은 세션으로 `/login/oauth2/code/kakao?code=fake&state=...` 콜백 (플로우 전체). 테스트 프로퍼티로 provider URI를 WireMock으로 교체.

## A-2. 계정 연결 정책 (보안 리서치 확정 — Keycloak/Auth0 모델)

정규화 결과 `(provider, providerId, email?, emailVerifiedByProvider, displayName?)` 기준으로 `FederatedLoginSuccessHandler`가 분기:

1. `federated_identities`에 (provider, providerId) 존재 → 그 사용자로 **게이트 판정** 후 로그인
2. 부재 + email 있음 + 같은 email의 로컬 계정 존재:
   - `emailVerifiedByProvider && 로컬.emailVerified` **둘 다 true** → 자동 연결 + 게이트 판정 (근거: better-auth 계정 선점 탈취 사례 — 로컬 미검증 계정에 자동 연결하면 탈취됨)
   - 아니면(네이버 전부 포함) → **기존 계정 확인 플로우**: PendingAuth 확장 stage `SOCIAL_LINK`(+ pendingLink(provider, providerId) 세션 저장, SecurityContext 비움) → `/login/link-confirm` 페이지 "이미 이 이메일로 taspa 계정이 있습니다. 본인 확인을 위해 인증 코드를 보냈습니다" → 기존 `EmailVerificationService`로 코드 발송·확인 → 연결 + 게이트 판정
3. 부재 + email 있음 + 로컬 계정 없음 → 신규 계정 생성 (password_hash NULL, displayName, emailVerified = emailVerifiedByProvider) + identity 연결:
   - provider 검증 이메일 → 게이트 판정(신규라 MFA 없음 → 완전 인증)
   - 미검증(네이버) → 기존 `EMAIL_VERIFICATION` 게이트로 (기존 인프라 그대로)
4. 부재 + email 없음(카카오 미동의) → PendingAuth stage `SOCIAL_EMAIL`(pendingLink 유지) → `/login/social-email` 페이지: 이메일 입력 → 코드 발송·확인(EmailVerificationService) → 계정 생성(검증됨) + 연결 + 완전 인증

**게이트 판정** 공통 함수: user.mfaEnabled && 신뢰기기 쿠키 무효(Stage B 전에는 항상 무효) → pending MFA → /login/mfa; else `completeAuthentication`.

oauth2Login successHandler 호출 시점에는 필터가 이미 OAuth2AuthenticationToken을 세션에 저장한 뒤이므로, 게이트/링크 분기 시 반드시 `LoginFlowSupport.clearSecurityContext` 계열로 제거 (기존 MfaAwareAuthenticationSuccessHandler와 동일 패턴).

## A-3. 구현 항목

- `server/build.gradle.kts`: `spring-boot-starter-oauth2-client` 추가
- V5__social_login.sql:
  ```sql
  ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
  CREATE TABLE federated_identities (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      provider VARCHAR(32) NOT NULL,
      provider_user_id VARCHAR(255) NOT NULL,
      email_at_link VARCHAR(255),
      created_at TIMESTAMP NOT NULL DEFAULT now(),
      UNIQUE (provider, provider_user_id)
  );
  CREATE INDEX idx_federated_identities_user ON federated_identities(user_id);
  ```
- `federation/` 신규 모듈: `SocialAttributesExtractor`(공급자별 정규화 — 카카오/네이버 중첩 처리), 커스텀 `OAuth2UserService` + `OidcUserService`(델리게이트 패턴), `FederatedLoginSuccessHandler`(A-2 분기), `FederationService`(연결/해제/조회), `FederationController`(GET /api/federations, DELETE /api/federations/{provider} — 해제), `SocialClientRegistrations`(조건부 빈), link-confirm/social-email 페이지 컨트롤러는 LoginFlowController에 추가
- `domain/federation/FederatedIdentity` + repository
- `User.passwordHash`를 nullable(String?)로. `LoginUserDetailsService`: password NULL이면 **더미 bcrypt 해시 대입**(AuthService.DUMMY_HASH 패턴 재사용 — 폼 로그인 항상 실패 + 타이밍 균일). 실패 메시지는 기존 일반 메시지 유지(소셜 전용 여부 비노출)
- SecurityConfig @Order(2): `ObjectProvider<ClientRegistrationRepository>` 있으면 `.oauth2Login { loginPage("/login"), userInfoEndpoint(userService/oidcUserService), successHandler, failureHandler(→ /login?error=social) }`. permitAll 추가: `/oauth2/authorization/**`, `/login/oauth2/code/**`, `/login/link-confirm`, `/login/social-email`
- 로그인 페이지(identifier): 등록된 공급자 버튼 "Google로 계속하기 / 카카오로 계속하기 / 네이버로 계속하기" (공급자 브랜드 로고 에셋 사용 금지 — 텍스트 버튼 + 중립 아이콘. 브랜드 가이드 준수는 실배포 시 사용자가 공식 에셋 추가)
- password 페이지: 해당 계정에 연결된 공급자가 있으면 그 버튼도 표시 (소셜 전용 계정 UX)
- 계정 페이지: "연결된 계정" 섹션 — 목록 + 연결 추가(로그인된 상태에서 /oauth2/authorization/{id} → successHandler가 로그인된 세션이면 연결만 수행하고 /account?linked=1 복귀) + 해제(**잔여 로그인 수단 검증**: password_hash != NULL + 패스키 수 + 남은 소셜 수 ≥ 1 아니면 409 + "먼저 비밀번호를 설정하거나 패스키를 등록하세요") + 해제 시 알림 메일 + audit
- `/login?error=social`: "소셜 로그인에 실패했습니다. 다시 시도해 주세요"
- docs: `docs/social-login-setup.md` (A-1의 공급자별 콘솔 등록 절차 + redirect URI + 환경변수), README/architecture/CLAUDE 갱신
- 테스트: WireMock 플로우 3종(자동 연결 성공 / 미검증 이메일 → link-confirm / 이메일 없음 → social-email), oauth2Login() 헬퍼로 unlink 정책(마지막 수단 차단), passwordless 폼 로그인 실패, 기존 테스트 무손상

# Stage B — 신뢰 기기 + 로그인 알림 + step-up + 매직 링크

## B-1. 신뢰 기기 (MFA 30일 스킵 — OWASP 권장 설계)

- V6__login_hardening.sql (Stage B 테이블 일괄):
  ```sql
  CREATE TABLE trusted_devices (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      token_hash VARCHAR(64) NOT NULL UNIQUE,
      ua_label VARCHAR(255),
      created_at TIMESTAMP NOT NULL DEFAULT now(),
      last_used_at TIMESTAMP,
      expires_at TIMESTAMP NOT NULL
  );
  CREATE INDEX idx_trusted_devices_user ON trusted_devices(user_id);
  CREATE TABLE login_events (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      ip VARCHAR(64), ua_label VARCHAR(255), method VARCHAR(32) NOT NULL,
      created_at TIMESTAMP NOT NULL DEFAULT now()
  );
  CREATE INDEX idx_login_events_user_time ON login_events(user_id, created_at);
  CREATE TABLE magic_link_tokens (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      token_hash VARCHAR(64) NOT NULL UNIQUE,
      created_at TIMESTAMP NOT NULL DEFAULT now(),
      expires_at TIMESTAMP NOT NULL,
      used_at TIMESTAMP
  );
  ```
- `TrustedDeviceService`: 발급(SecureTokenGenerator 256-bit → SHA-256 해시 저장, 쿠키 `taspa_td` HttpOnly/Secure(prod)/SameSite=Lax/Max-Age=30일/Path=/), 검증(해시 조회 + 만료 + user 일치), **사용 시 회전**(새 토큰 재발급, 이전 행 갱신), 폐기(개별/전체)
- MFA 게이트 판정(비밀번호 성공 핸들러 + 소셜 핸들러 공통 유틸로 추출): mfaEnabled && !trustedDeviceValid → pending MFA
- `/login/mfa` 페이지: 체크박스 "이 기기에서 30일 동안 묻지 않음" → 성공 시 쿠키 발급
- 무효화 트리거: 비밀번호 재설정 성공, MFA 해제/재등록 시 해당 사용자 전체 폐기 (기존 서비스에 훅)
- 계정 페이지 "신뢰하는 기기" 섹션: 목록(ua_label, 생성/마지막 사용) + 개별/전체 해제 (해제는 step-up 대상)
- 만료 30일 고정, sliding 연장 금지

## B-2. 로그인 알림

- `completeAuthentication`(모든 완전 인증의 수렴점) + `PasskeyAuthenticationSuccessHandler`에서 login_events 기록 (method: password/mfa/passkey/social:{provider}/magic)
- 신규 기기 판정: 해당 user의 최근 30일 login_events에 같은 (ip, ua_label) 없고 신뢰 기기 쿠키도 아니면 → "새 로그인이 감지되었습니다" 메일 (기기/IP/시각, "본인이 아니면 비밀번호를 변경하세요"). 같은 (ip, ua_label)로는 24시간 내 재발송 금지
- ua_label: User-Agent에서 브라우저/OS 요약 추출 (간단 파서, 라이브러리 추가 없이)

## B-3. Step-up 재인증 (auth_time 패턴)

- 세션 속성 `TASPA_AUTH_TIME` — `completeAuthentication` 및 패스키 성공 핸들러에서 기록/갱신
- `@RequireRecentAuth` 애노테이션 + `HandlerInterceptor`: `now - auth_time > 10분`(`taspa.step-up.max-age-minutes`, 기본 10)이면 — HTML 요청: 원래 URL 세션 저장 후 `/reauth`로 리다이렉트; API 요청(/api/**): 401 JSON `{errorCode: "REAUTH_REQUIRED"}` → 페이지 JS가 `/reauth?continue=<현재경로>` 이동
- `/reauth` 페이지 (인증 필수): "계속하려면 본인임을 확인하세요" — 비밀번호 입력(password_hash 있으면) + "패스키 사용" 버튼(패스키 있으면). 성공 → auth_time 갱신 → continue 복귀. 패스키 재인증은 기존 `/webauthn/authenticate/options` + `/login/webauthn` 재사용 — 단 이미 인증된 세션의 재인증이므로 성공 후 auth_time 갱신 로직이 `PasskeyAuthenticationSuccessHandler`에 필요(이미 인증된 상태에서의 성공 = 재인증으로 간주)
- 적용 대상(@RequireRecentAuth): `/api/mfa/**`(setup/activate/disable/regenerate), `/api/passkeys/**`(PATCH/DELETE) + `/webauthn/register/options`·`/webauthn/register`는 필터 처리라 애노테이션 불가 → 인터셉터 대신 **등록 페이지 진입점인 account 페이지의 패스키 만들기 버튼이 사전에 GET /api/reauth/check를 호출해 만료면 /reauth로 보내는 방식 + 서버측은 `/webauthn/register` 요청을 가로채는 별도 필터(step-up 필터)를 WebAuthnRegistrationFilter 앞에 배치**, `/api/federations/**`, 신뢰 기기 해제 API
- 신뢰 기기 쿠키는 step-up을 면제하지 않는다
- 테스트 프로파일에서 임계값 초 단위 설정 가능하게 (`taspa.step-up.max-age-minutes` 대신 duration 타입 권장)

## B-4. 매직 링크

- password 페이지(+ 소셜 전용 계정 안내 영역)에 "이메일로 로그인 링크 받기" → POST `/login/magic/request` (LOGIN_HINT 세션의 이메일 대상, 60초 재발급 제한, 미존재 이메일에도 동일 응답 화면 "메일함을 확인하세요")
- 메일 링크: `{base-url}/login/magic?token=...` — **GET은 토큰을 소비하지 않고** 확인 페이지 렌더 ("taspa에 로그인하시겠습니까?" + 이메일 표시 + "로그인" 버튼) — 이메일 스캐너 선클릭 방지
- POST `/login/magic` → 토큰 검증(해시 조회, 15분 만료, 단일 사용 — used_at 마킹과 세션 승격을 같은 트랜잭션으로) → 이메일 미검증 계정이면 emailVerified=true 마킹 → **게이트 판정(MFA 유지)** → completeAuthentication. 클릭한 브라우저에서 로그인 성립(요청 브라우저 자동 로그인 금지)
- 만료/사용됨/무효 → "링크가 만료되었거나 이미 사용되었습니다" + 재요청 링크
- `MagicLinkService` + `domain/credential/MagicLinkToken`, 설정 `taspa.magic-link.token-expiry-minutes: 15`

## B-5. 테스트 (Stage B)

- 통합: 신뢰 기기(MFA 스킵 + 회전 + 비밀번호 재설정 시 전체 폐기), step-up(임계 초과 시 401 REAUTH_REQUIRED / 재인증 후 통과 — 세션 속성 직접 조작으로 시간 시뮬), 매직 링크(정상/만료/재사용/MFA 게이트 유지/미검증 계정 검증 마킹), 로그인 알림(신규 기기 발송 + 24h 중복 억제 — JavaMailSender mock 캡처)
- e2e (Playwright): `trusted-device.spec.ts`(가입→MFA 설정→로그아웃→로그인(체크박스 체크)→로그아웃→재로그인 시 MFA 스킵 확인), `magic-link.spec.ts`(가입·인증→로그아웃→이메일 입력→링크 받기→Mailpit API에서 링크 추출→랜딩→로그인 버튼→계정 도달)

## 문서 (Stage B 포함 일괄)

README 기능 표, architecture.md("인증 수단" 섹션에 소셜/신뢰 기기/step-up/매직 링크 + 정책 결정 근거: 소셜 MFA 유지, auto-link 이중 검증 조건, 신뢰 기기 회전), CLAUDE.md 모듈, docs/social-login-setup.md 신규.

## 제약

- auth-playground 수정 금지, git init 금지, 기존 테스트(33개+passkey e2e) 무손상
- 공급자 브랜드 로고/트레이드드레스 에셋 커밋 금지 (텍스트 버튼)
- 실 공급자 클라이언트 ID는 없음 — 조건부 등록으로 미설정 시 버튼 미노출·기동 정상이 요구사항
