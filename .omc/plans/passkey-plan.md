# 패스키(WebAuthn) 구현 스펙 — Spring Security 6.4 네이티브

taspa에 구글식 패스키 인증을 추가한다. 아래 "확정 사실"은 로컬 jar(javap)와 공식 문서(6.4.5 태그)에서 실측한 것이므로 그대로 신뢰하고 재조사하지 말 것.

## 확정 사실 (리서치 실측)

- 의존성: `com.webauthn4j:webauthn4j-core:0.28.6.RELEASE` 명시 추가 필수 (Boot BOM 미관리, 없으면 NoClassDefFoundError)
- DSL: `http.webAuthn { }` — 설정 항목은 rpName, rpId, allowedOrigins, disableDefaultRegistrationPage 뿐
- 커스텀 저장소: `org.springframework.security.web.webauthn.management.UserCredentialRepository`(save/delete(Bytes)/findByCredentialId(Bytes)/findByUserId(Bytes))와 `PublicKeyCredentialUserEntityRepository`(findById(Bytes)/findByUsername(String)/save/delete)를 **@Bean으로 등록하면 configurer가 자동 사용** (getSharedOrBean). 6.4에는 JDBC 구현 없음 → 직접 구현
- `CredentialRecord` 14필드: credentialType, credentialId(Bytes), userEntityUserId(Bytes), publicKey(PublicKeyCose→getBytes()), signatureCount(long), uvInitialized, transports(Set<AuthenticatorTransport>), backupEligible, backupState, attestationObject(Bytes, null 가능), attestationClientDataJSON(Bytes, null 가능), created(Instant), lastUsed(Instant), label. `ImmutableCredentialRecord.builder()` 존재
- 엔드포인트(필터 처리, 컨트롤러 없음): POST `/webauthn/register/options`(인증 필수, 미인증 400) · POST `/webauthn/register`(JSON에 label 필수) · DELETE `/webauthn/register/{id}`(204) · POST `/webauthn/authenticate/options`(익명 허용 → allowCredentials 빈 배열 = discoverable/usernameless) · POST `/login/webauthn`
- CSRF: 위 엔드포인트 전부 `X-CSRF-TOKEN` 헤더 필요 (403 방지). Thymeleaf에서 `${_csrf.headerName}`/`${_csrf.token}`을 JS로 주입할 것
- 기본 JS: spring-security-web-6.4.4.jar 내 `org/springframework/security/spring-security-webauthn.js` (10KB). **이 파일을 jar에서 추출해 `server/src/main/resources/static/js/webauthn.js`로 벤더링**하고 (Apache-2.0 라이선스 헤더 유지, 출처 주석 추가) `disableDefaultRegistrationPage(true)` 설정 (커스텀 로그인 페이지 사용 시 기본 서빙이 꺼지는 조건 때문). jar 경로: `/Users/woosunguk/.gradle/caches/modules-2/files-2.1/org.springframework.security/spring-security-web/6.4.4/b33b3eba22ef5242caef0cf3c0575b0cc303519b/spring-security-web-6.4.4.jar`
- 벤더링한 JS의 전역 함수: `setupLogin(csrfHeaders, contextPath, buttonEl)` — 클릭 시 옵션 fetch → navigator.credentials.get → POST /login/webauthn → 성공 JSON `{redirectUrl, authenticated}` 받아 이동, 실패 시 `/login?error`로 이동. `setupRegistration(csrfHeaders, contextPath, {getRegisterButton, getSuccess, getError, getLabelInput, getDeleteForms})` — label 입력 필수, 성공 시 `/webauthn/register?success`로 이동(→ 우리는 커스텀이므로 이 이동 후 /account로 재유도하거나, 함수 호출 후 동작을 감싸서 처리)
- 성공 시 SecurityContext는 HttpSessionSecurityContextRepository에 저장(세션 키 동일), saved request 소비 → OIDC continuation 자동 성립. `AuthenticationFilter`는 성공 시 `changeSessionId()` 호출(세션 고정 방어 내장 — 실측)
- 등록 옵션은 `residentKey=REQUIRED`, `userVerification=PREFERRED`, attestation NONE, EdDSA/ES256/RS256, timeout 5분으로 하드코딩됨(Webauthn4JRelyingPartyOperations)
- rpId와 allowedOrigins 불일치 시 조용히 실패 → dev 기본값 rpId=localhost, allowedOrigins=http://localhost:9100

## 설정

`taspa.webauthn.*` @ConfigurationProperties: `rp-id`(기본 localhost), `rp-name`(기본 taspa), `allowed-origins`(기본 [http://localhost:9100]). SecurityConfig 기본 체인에:

```kotlin
.webAuthn {
    it.rpName(props.rpName).rpId(props.rpId)
        .allowedOrigins(*props.allowedOrigins.toTypedArray())
        .disableDefaultRegistrationPage(true)
}
```

인가 규칙 추가: `/login/passkey`, `/js/**`, POST `/webauthn/authenticate/options`, POST `/login/webauthn` → permitAll. `/webauthn/register/options`, `/webauthn/register`, DELETE는 anyRequest().authenticated()로 커버(명시 불필요하지만 명시해도 무방).

## DB — V4__passkeys.sql

```sql
CREATE TABLE webauthn_user_entities (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    external_id VARCHAR(256) NOT NULL UNIQUE,   -- 인증기에 노출되는 base64url ID (랜덤 32B, users.id 노출 금지)
    name VARCHAR(255) NOT NULL,                 -- email
    display_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE TABLE webauthn_credentials (
    credential_id VARCHAR(1024) PRIMARY KEY,    -- base64url
    user_entity_external_id VARCHAR(256) NOT NULL REFERENCES webauthn_user_entities(external_id) ON DELETE CASCADE,
    credential_type VARCHAR(32) NOT NULL,
    public_key_cose BYTEA NOT NULL,
    signature_count BIGINT NOT NULL DEFAULT 0,
    uv_initialized BOOLEAN NOT NULL DEFAULT false,
    transports VARCHAR(255),                    -- csv
    backup_eligible BOOLEAN NOT NULL DEFAULT false,
    backup_state BOOLEAN NOT NULL DEFAULT false,
    attestation_object BYTEA,
    attestation_client_data_json BYTEA,
    label VARCHAR(100) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP
);
CREATE INDEX idx_webauthn_credentials_user ON webauthn_credentials(user_entity_external_id);
```

Hibernate ddl-auto validate 유지 — 엔티티 정확 일치.

## 코드 구조 (package-by-feature 유지)

- `domain/passkey/`: `PasskeyUserEntity`, `PasskeyCredential` JPA 엔티티 + Spring Data 리포지토리
- `passkey/`(신규 feature 모듈):
  - `JpaPublicKeyCredentialUserEntityRepository`: findByUsername(email) → 기존 행 반환(없으면 null — SS operations가 없을 때 새로 만들어 save() 호출함), save 시 email로 users 조인해 user_id 매핑, external_id는 SS가 준 Bytes(base64url) 저장
  - `JpaUserCredentialRepository`: CredentialRecord ↔ PasskeyCredential 14필드 왕복 매핑 (Bytes ↔ base64url 문자열, PublicKeyCose.getBytes() ↔ BYTEA, transports Set ↔ csv). save()는 upsert (인증 성공 시 SS가 signatureCount/lastUsed 갱신 후 save 호출함). **delete(Bytes)에 소유권 가드**: SecurityContextHolder의 현재 인증 사용자가 소유하지 않은 credential이면 삭제하지 않고 WARN 로그 (SS 기본 DELETE /webauthn/register/{id}에 소유권 검사가 없을 가능성 방어)
  - `PasskeyService`: 사용자별 목록(라벨/생성일/마지막 사용), 이름 변경, 삭제(소유권 검사), 보유 여부(hasPasskeys)
  - `PasskeyController`: GET /api/passkeys, PATCH /api/passkeys/{credentialId} {label}, DELETE /api/passkeys/{credentialId} — 인증 필수, 소유권 검사
- `Bytes ↔ base64url String` 변환은 `Bytes.toBase64UrlString()`/`Bytes.fromBase64(String)` 같은 SS API를 우선 확인해 사용 (javap로 Bytes 클래스 메서드 확인 후 사용; 없으면 java.util.Base64 urlEncoder)

## 로그인 플로우 통합 (구글식)

- `LoginFlowController.submitIdentifier`: 계정 확인 후 `passkeyService.hasPasskeys(user)`면 `redirect:/login/passkey`, 아니면 기존 `redirect:/login/password`
- 신규 GET `/login/passkey` (LOGIN_HINT 세션 필요, 없으면 /login): 제목 "본인임을 확인", 부제 "지문, 얼굴 또는 화면 잠금으로 본인임을 확인합니다", 이메일 칩 표시, 주 버튼 "계속"(id=passkey-signin, setupLogin 연결), 하단 링크 "다른 방법 시도" → `/login/password`
- `/login`(identifier) 페이지: "다음" 아래에 구분선 + 보조 버튼 "패스키로 로그인"(id=passkey-signin, usernameless)
- `/login/password` 페이지: 사용자가 패스키 보유 시 "패스키 사용" 텍스트 링크 (모델에 hasPasskeys 전달)
- **위생 규칙**: GET `/login/passkey`, `/login/password`, `/login/mfa`, `/login/verify-email`에서 이미 완전 인증 상태(SecurityContextHolder에 authenticated non-anonymous)면 `redirect:/account`
- 패스키 로그인은 MFA/이메일 게이트를 생략한다 (구글 정책: 패스키=소유+생체로 2FA 충족. 불변식: 패스키 등록은 인증된 세션에서만 가능하고 인증 세션은 이메일 인증을 통과했으므로, 미인증 이메일 계정에 패스키가 존재할 수 없다 — 이 불변식을 코드 주석과 architecture.md에 명시)
- 실패 폴백: 벤더링 JS의 실패 이동 경로가 `/login?error`이므로, GET /login에서 `error` 파라미터 시 "패스키 인증에 실패했습니다. 다시 시도하거나 비밀번호로 로그인하세요" 안내 표시

## 계정 페이지 (account.html)

"패스키" 섹션 추가 (MFA 섹션 아래):
- 목록: 각 항목 = 라벨 + 생성일 + 마지막 사용일 + 연필 아이콘(인라인 이름 변경 → PATCH /api/passkeys/{id}) + X(삭제 confirm → DELETE /api/passkeys/{id}; 안내 문구 "삭제해도 기기에 저장된 패스키는 남습니다. 기기 설정에서 별도로 삭제하세요")
- "패스키 만들기" 주 버튼: 라벨 입력 필드(기본값 예: "내 기기") → setupRegistration 사용 또는 벤더링 JS의 create 흐름 직접 호출 → 성공 시 목록 갱신(fetch GET /api/passkeys 재조회; setupRegistration의 `/webauthn/register?success` 이동 동작은 사용하지 않도록 커스텀 UI 콜백 구성 또는 직접 fetch 구현)
- JS의 모든 webauthn/API fetch에 CSRF 헤더 주입 (`<meta name="_csrf_header">`/`<meta name="_csrf">` 패턴 권장). 단 /api/** 는 CSRF ignore이므로 /api/passkeys는 헤더 불필요 — /webauthn/**, /login/webauthn만 필수

## 문구 (구글 국문 패턴 — 상표/로고 복제 금지)

"패스키로 로그인" · "패스키 만들기" · "다른 방법 시도" · "본인임을 확인" · "지문, 얼굴 또는 화면 잠금으로 본인임을 확인합니다" · 버튼 "계속"/"나중에". 합니다체, 명사형 종결 버튼.

## 테스트

통합(Testcontainers, 기존 IntegrationTestBase):
1. `PasskeyRepositoryIntegrationTest`: CredentialRecord 14필드 전부 채워 save → findByCredentialId/findByUserId 왕복 동등성 (특히 Bytes/PublicKeyCose/transports/Instant), user entity save→findByUsername/findById 왕복, signatureCount 갱신 upsert
2. `PasskeyEndpointsIntegrationTest`: 미인증 POST /webauthn/register/options → 400 또는 401(실측값으로 단언), 미인증 POST /webauthn/authenticate/options → 200 + allowCredentials 빈 배열, 인증 세션 POST /webauthn/register/options → 200 + rp.id/user.name 단언 (CSRF는 `csrf()` 헬퍼)
3. `PasskeyOwnershipIntegrationTest`: 사용자 A의 credential을 B 세션으로 DELETE /api/passkeys/{id} → 403/404, A 세션 → 204·실삭제. 어댑터 delete 가드 단위 검증 포함
4. 기존 17개 테스트 무손상

e2e (`e2e/tests/passkey.spec.ts`, Chromium 전용, 서버+DB+Mailpit 실행 전제 주석):
CDP 가상 인증기 레시피 (실측 확정):
```ts
const client = await page.context().newCDPSession(page);
await client.send('WebAuthn.enable');
const { authenticatorId } = await client.send('WebAuthn.addVirtualAuthenticator', {
  options: { protocol: 'ctap2', transport: 'internal', hasResidentKey: true,
             hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true },
});
```
시나리오: 고유 이메일로 가입 → Mailpit API(`http://localhost:8025/api/v1/messages`)에서 6자리 코드 추출 → 이메일 인증 → /account → 라벨 입력 후 패스키 만들기 (`WebAuthn.credentialAdded` 이벤트 대기) → 목록에 표시 확인 → 로그아웃 → /login 이메일 입력 → /login/passkey 화면 확인 → "계속" 클릭 (`WebAuthn.credentialAsserted` 대기) → /account 도달 단언. 추가 케이스: /login에서 usernameless "패스키로 로그인" 직행.

## 문서

README 기능 표에 패스키 추가, docs/architecture.md에 "인증 수단(Authentication Methods)" 섹션(패스키 아키텍처, 저장 모델, MFA 생략 불변식, rpId/origin 규칙), CLAUDE.md 모듈 갱신.

## 제약·완료 기준

- 기존 로그인/MFA/이메일 인증 플로우 무손상 (게이트 불변식 유지: pending은 SecurityContext 밖)
- auth-playground 수정 금지, git init 금지
- `./gradlew build` 전체 통과 (Docker 켜져 있음), 신규 통합테스트 포함
- e2e는 컴파일/문법 완성도까지 (실행은 오케스트레이터가 서버 기동 후 수행)
