# examples/demo-client

taspa 를 IdP 로 사용하는 최소 OIDC 클라이언트(RP) 예제. dev 프로파일이 시딩하는
`demo-app` 클라이언트(client_id `demo-app` / secret `demo-secret` /
redirect `http://localhost:8080/login/oauth2/code/taspa`)와 정확히 일치하는 설정을 갖는다.

> **경고**: `demo-app`/`demo-secret` 은 **로컬 데모 전용 자격 증명**이다. 운영에서는 절대
> 재사용하지 말 것 — 관리 콘솔(`/admin/clients`)에서 별도 클라이언트를 발급하고 secret 은
> 환경변수/시크릿 매니저로 주입한다.

## 실행

```bash
# 1. 인프라 + taspa 기동 (demo-app 시딩을 위해 dev 프로파일 필수)
docker compose up -d postgres mailpit
./gradlew :server:bootRun --args='--spring.profiles.active=dev'

# 2. 데모 클라이언트 기동 (8080)
./gradlew :examples:demo-client:bootRun
```

http://localhost:8080 접속 → "taspa로 로그인" → taspa 로그인·동의 → `/me` 에서 클레임 확인.

## 이 앱이 검증하는 것

- **OIDC Authorization Code + PKCE**: demo-app 은 `requireProofKey(true)` 로 시딩되므로
  기밀 클라이언트지만 인가 요청에 `code_challenge` 를 붙인다(`SecurityConfig` 의
  `OAuth2AuthorizationRequestCustomizers.withPkce()` — Spring Security 는 공개 클라이언트에만
  PKCE 를 기본 적용).
- **동의 화면**: `requireAuthorizationConsent(true)` → 최초 로그인 시 taspa 의 구글식 동의
  화면("허용")을 거치고, 동의는 저장되어 재로그인 시 생략된다.
- **클레임 매핑**: `sub`(=`users.id` UUID — **안정적·불변 식별자, 이메일 아님**),
  `email`/`email_verified`(email scope), `name`/`preferred_username`(=이메일, profile scope) —
  `/me` 가 OidcUser 프로필과 ID 토큰 클레임 전체를 표로 보여준다. 계정 키로는 `sub`(UUID)을 쓰고
  이메일은 표시용으로만 쓴다(이메일 변경에도 sub 불변). 자세한 매핑·마이그레이션은
  `docs/integration-guide.md` 참조.
- **SSO 세션과 로컬 세션의 분리**: `/logout` 은 이 앱의 세션만 끊는다. taspa 세션이 남아 있어
  다시 로그인 버튼을 누르면 재인증 없이 즉시 `/me` 로 복귀한다(시나리오는 e2e
  `sso-flow.spec.ts` 에 기술 — 위 전제로 서버들을 띄운 뒤 `npx playwright test sso-flow` 로 실행).

## 설정에서 주의할 점

`application.yml` 의 provider 설정은 `issuer-uri` 대신 **엔드포인트를 명시**한다.
`issuer-uri` 는 기동 시점에 OIDC 디스커버리 HTTP 호출을 강제하므로 taspa 가 떠 있지 않으면
이 앱의 부팅이 깨진다 — 예제/빌드가 taspa 와 독립적으로 동작하도록 명시 방식을 쓴다.
단 이 방식은 **ID 토큰 `iss` 클레임 검증이 생략**되는 트레이드오프가 있다(Spring Security 는
issuer-uri 가 설정된 경우에만 iss 등가를 검사). 로컬 데모라 감수하며, 프로덕션 클라이언트는
issuer-uri(디스커버리) 방식을 권장한다.

테스트는 두지 않는다(컨텍스트 로드 자체는 taspa 없이도 되지만 검증 가치가 낮다 —
루트 `./gradlew build` 에 컴파일·bootJar(`demo-client.jar`)로 포함되는 것까지가 빌드 계약이고,
동작 검증은 위 전제로 서버들을 띄운 뒤 e2e `sso-flow.spec.ts` 를 실행해 수행한다).
