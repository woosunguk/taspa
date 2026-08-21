# taspa 연동 가이드

새 프로젝트를 taspa 중앙 인증에 붙이는 방법을 설명한다. taspa는 표준 OAuth2/OIDC Provider이므로
언어/프레임워크에 관계없이 표준 OIDC 클라이언트로 연동할 수 있다.

## 1단계. 클라이언트 등록

taspa에 애플리케이션을 **RegisteredClient**로 등록해야 한다. 등록 정보는
`oauth2_registered_client` 테이블에 저장된다.

- 로컬 개발용 데모 클라이언트는 dev 프로파일에서 자동 시딩된다:
  ```bash
  ./gradlew :server:bootRun --args='--spring.profiles.active=dev'
  ```
  - `client_id`: `demo-app`
  - `client_secret`: `demo-secret` (bcrypt 저장)
  - `redirect_uri`: `http://localhost:8080/login/oauth2/code/taspa`
  - `scope`: `openid`, `profile`, `email`
  - grant: `authorization_code`(+PKCE), `refresh_token`, `client_credentials`
  - **경고**: 이 자격 증명은 **로컬 데모 전용**이다 — 운영에 재사용/커밋하지 말 것.
    운영 클라이언트는 관리 콘솔에서 별도 발급하고 secret 은 환경변수로 주입한다.

- 신규 클라이언트는 **관리 콘솔 `/admin/clients`** 에서 등록한다(ADMIN 역할 필요).
  - **기밀(confidential)**: 서버 사이드 앱. secret 은 등록 응답에서 1회만 표시된다.
  - **공개(public)**: SPA·모바일. secret 없음(`token_endpoint_auth_method=none`) + PKCE 강제.
  - 코드로 시딩하려면 `oidc/RegisteredClientConfig.kt`의 예시를 참고한다.

필요한 값:

| 항목 | 설명 |
|------|------|
| client_id / client_secret | 클라이언트 식별자 / 비밀 (공개 클라이언트는 secret 없이 PKCE) |
| redirect_uri | 인가 코드 콜백 URL (정확히 일치해야 함) |
| scope | `openid` 필수(OIDC), 그 외 `profile`, `email` 등 |

## 2단계 (A). Spring 애플리케이션 — 제공 스타터 사용

가장 간단한 방법. `:client:spring-boot-starter` 의존성을 추가하고 issuer만 설정하면
taspa가 발급한 JWT를 검증하는 `JwtDecoder`가 자동 구성된다.

`build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.taspa:taspa-spring-boot-starter:0.0.1-SNAPSHOT")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
}
```

> **아티팩트 좌표는 `com.taspa:taspa-spring-boot-starter` 하나다.** taspa 저장소 안에서 작업할 때만
> `implementation(project(":client:spring-boot-starter"))` 를 쓴다.
>
> 사내 저장소에 올리려면 `TASPA_MAVEN_REPO_URL`(+`TASPA_MAVEN_USERNAME`/`PASSWORD`)을 설정하고
> `./gradlew :client:spring-boot-starter:publish`. 저장소가 없으면 `publishToMavenLocal` 로
> `~/.m2` 에 넣어 바로 시험할 수 있다.

`application.yml`:
```yaml
taspa:
  client:
    issuer-uri: http://localhost:9100
    audience: orders-api      # 선택 — 지정하면 aud 를 실제로 검증한다
```

> **`audience` 를 지정하면 그 값이 토큰 `aud` 에 없는 요청은 거부된다.** 같은 IdP 가 발급한
> **다른 서비스의 토큰**을 내 API 가 받아들이지 않게 하는 장치다(회귀: `AudienceValidatorTest`).
> 지정하지 않으면 iss·exp·nbf 만 검증한다 — 여러 서비스가 한 IdP 를 공유한다면 지정하는 편이 좋다.

`taspa.client.issuer-uri`가 있으면 `TaspaResourceServerAutoConfiguration`이
issuer 디스커버리 기반 `JwtDecoder` 빈을 등록한다. 이후 표준 Spring Security
Resource Server 설정으로 엔드포인트를 보호하면 된다:

```kotlin
@Bean
fun filterChain(http: HttpSecurity): SecurityFilterChain {
    http
        .authorizeHttpRequests { it.anyRequest().authenticated() }
        .oauth2ResourceServer { it.jwt {} }
    return http.build()
}
```

## 2단계 (B). Spring 웹 로그인(OIDC Login)

브라우저 로그인(SSO)이 필요하면 표준 `spring-boot-starter-oauth2-client`를 사용한다.
**전체 예제가 [`examples/demo-client`](../examples/demo-client)에 있다**
(동작 시나리오는 e2e `sso-flow.spec.ts` 로 고정 — taspa dev·demo-client 를 띄운 뒤
`npx playwright test sso-flow` 로 실행해 검증한다). 핵심 설정:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          taspa:
            client-id: demo-app
            client-secret: demo-secret
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/taspa"
            scope: openid, profile, email
        provider:
          taspa:
            # 상시 기동 서비스라면 issuer-uri 한 줄로 충분하다(기동 시 디스커버리).
            # 단 issuer-uri 는 앱 부팅 시점에 taspa 가 떠 있어야 한다 — taspa 와 독립적으로
            # 부팅해야 하는 앱은 examples/demo-client 처럼 엔드포인트 4개를 명시할 것.
            issuer-uri: http://localhost:9100
```

명시 엔드포인트 방식(demo-client)은 부팅 독립성을 얻는 대신 **ID 토큰 `iss` 클레임 검증이
생략**된다 — Spring Security 는 `issuer-uri` 가 설정된 경우에만 iss 등가를 검사한다(서명
검증은 `jwk-set-uri` 로 계속 수행). 프로덕션 클라이언트는 `issuer-uri`(디스커버리) 방식을
권장하고, 명시 방식은 로컬 데모/부팅 독립성이 꼭 필요한 경우로 한정할 것.

주의: taspa 가 등록 시 PKCE 를 강제(`requireProofKey`)하는 클라이언트라면, Spring Security 는
기밀 클라이언트에 PKCE 를 기본 적용하지 않으므로
`OAuth2AuthorizationRequestCustomizers.withPkce()` 를 붙여야 한다
(`examples/demo-client` 의 `SecurityConfig.kt` 참고).

### 로그아웃 — RP-Initiated Logout(Single Logout)

taspa 는 OIDC **RP-Initiated Logout** 을 지원한다(디스커버리의 `end_session_endpoint` =
`/connect/logout`). 클라이언트가 여기로 `id_token_hint` + `post_logout_redirect_uri` 를 보내면
taspa 는 **OP 의 SSO 세션까지 종료**한 뒤 등록된 복귀 URI 로 리다이렉트한다. 이것으로 "이 앱만
로그아웃"이 아니라 "taspa 세션까지 로그아웃(재로그인 시 재인증 필요)"을 구현할 수 있다.

준비물:

1. **post_logout_redirect_uri 등록**: 관리 콘솔 `/admin/clients` 의 *post-logout redirect URI* 필드에
   복귀 URL 을 등록한다(정확 일치 검증 — 미등록 값으로 요청하면 taspa 가 로그아웃을 거부).
   demo-app 은 dev 시더(`oidc/RegisteredClientConfig.kt`)가 `http://localhost:8080/` 를 등록한다.
2. **Spring 클라이언트 배선** — `OidcClientInitiatedLogoutSuccessHandler`:
   ```kotlin
   val handler = OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository).apply {
       setPostLogoutRedirectUri("{baseUrl}/")   // 종료 후 복귀 지점(등록값과 일치)
   }
   http.logout { it.logoutSuccessHandler(handler) }
   ```
   이 핸들러는 `ClientRegistration` 의 `end_session_endpoint` 메타데이터를 읽는다. `issuer-uri`(디스커버리)
   방식이면 자동으로 채워지고, `examples/demo-client` 처럼 엔드포인트를 명시하는(부팅 독립성) 방식이면
   디스커버리 메타데이터가 비어 있으므로 `end_session_endpoint` 를 별도로 주입해야 한다
   (`SecurityConfig.kt` 는 로그아웃 핸들러 전용 보강 `ClientRegistrationRepository` 로 이 값을 주입한다).
3. 전체 동작 예제는 `examples/demo-client` 에 있다 — `/me` 에 **로컬 로그아웃**과 **SLO** 두 버튼을 두고,
   왕복 시나리오(로그인 → SLO → 재로그인 시 재인증)를 e2e `sso-flow.spec.ts` 로 고정한다.

> **한계 — OP-Initiated Back-Channel Logout 미지원**: Spring Authorization Server 1.4.2 에는
> OP 가 각 RP 로 `logout_token`(JWT)을 push 하는 **Back-Channel Logout 송신부가 없다**(로컬 jar 실측:
> `backchannel_logout_supported`/`backchannel_logout_uri` 메타데이터·`logout_token` 생성 로직 전무).
> 따라서 "한 곳에서 로그아웃하면 참여 RP 전부가 자동 무효화"되는 완전한 SLO 는 기성으로 제공되지
> 않으며, 커스텀 구현(클라이언트 메타데이터 `backchannel_logout_uri` 저장 · OP 세션↔RP 매핑 레지스트리 ·
> 서명 `logout_token` POST · 디스커버리 광고)이 필요하다. RP 수신부(`http.oidcLogout().backChannel()`)는
> Spring Security 6.4 가 기성으로 지원하지만 OP 송신부가 없으면 실효가 없으므로 배선하지 않았다.
> 현재 제공 범위는 **RP-Initiated Logout(위)** 이며, 이것으로 SSO 세션 종료 + 클라이언트 복귀는 충족된다.

## 2단계 (C). Node.js / Express — openid-client v6

> 아래 조각은 **예시**다(taspa 엔드포인트·클레임에 맞춰 작성했으나 e2e 로 검증되지 않음).

```js
import * as client from 'openid-client';

// 디스커버리(http://localhost:9100/.well-known/openid-configuration) 기반 설정.
// openid-client v6 는 https 를 강제하므로 로컬 http 는 allowInsecureRequests 필요.
const config = await client.discovery(
  new URL('http://localhost:9100'),
  'my-node-app',            // 관리 콘솔에서 등록한 client_id
  'my-node-secret',
  undefined,
  { execute: [client.allowInsecureRequests] },
);

// 1) 로그인 시작 — code + PKCE
const codeVerifier = client.randomPKCECodeVerifier();
const codeChallenge = await client.calculatePKCECodeChallenge(codeVerifier);
const state = client.randomState();
// codeVerifier/state 는 세션에 저장해 둔다.
const authorizationUrl = client.buildAuthorizationUrl(config, {
  redirect_uri: 'http://localhost:3000/callback',   // 등록된 redirect_uri 와 정확히 일치
  scope: 'openid profile email',
  code_challenge: codeChallenge,
  code_challenge_method: 'S256',
  state,
});
// res.redirect(authorizationUrl.href)

// 2) 콜백 — code 교환 + ID 토큰 검증(서명·nonce·state 는 라이브러리가 수행)
const tokens = await client.authorizationCodeGrant(config, new URL(req.url, 'http://localhost:3000'), {
  pkceCodeVerifier: codeVerifier,
  expectedState: state,
});
const claims = tokens.claims();
// claims.sub === users.id(UUID, 안정적 식별자 — 이메일 아님), claims.email(이메일),
// claims.preferred_username(이메일), claims.name, claims.email_verified — §클레임 매핑 참조
```

## 2단계 (D). Next.js — Auth.js(next-auth) custom provider

> 아래 조각은 **예시**다(Auth.js v5 기준, 검증되지 않음). redirect_uri 는
> `{origin}/api/auth/callback/taspa` 로 등록해야 한다.

```ts
// auth.ts
import NextAuth from 'next-auth';

export const { handlers, auth, signIn, signOut } = NextAuth({
  providers: [
    {
      id: 'taspa',
      name: 'taspa',
      type: 'oidc',
      issuer: 'http://localhost:9100',
      clientId: process.env.TASPA_CLIENT_ID,
      clientSecret: process.env.TASPA_CLIENT_SECRET,
      authorization: { params: { scope: 'openid profile email' } },
      checks: ['pkce', 'state'],
      profile(profile) {
        // taspa 클레임: sub(=users.id UUID, 안정적 키), name, email, preferred_username(=이메일), email_verified.
        // id 는 반드시 sub(UUID)로 매핑 — 이메일이 바뀌어도 동일 계정을 가리킨다(§클레임 매핑).
        return { id: profile.sub, name: profile.name, email: profile.email };
      },
    },
  ],
});
```

## 3단계. SPA(공개 클라이언트) — oidc-client-ts

SPA 는 secret 을 보관할 수 없으므로 관리 콘솔에서 **공개(public) 유형**으로 등록한다
(`token_endpoint_auth_method=none` + PKCE 강제, secret 발급 없음).

- 디스커버리 문서: `http://localhost:9100/.well-known/openid-configuration`
- Authorization Endpoint: `/oauth2/authorize` (Authorization Code + PKCE)
- Token Endpoint: `/oauth2/token`
- JWKS: `/oauth2/jwks` (access/id 토큰 서명 검증)
- UserInfo: `/userinfo`

> 아래 조각은 **예시**다(검증되지 않음).

```ts
import { UserManager } from 'oidc-client-ts';

const userManager = new UserManager({
  authority: 'http://localhost:9100',               // 디스커버리 자동 수행
  client_id: 'my-spa',                              // 공개 유형으로 등록한 client_id
  redirect_uri: 'http://localhost:5173/callback',
  response_type: 'code',                            // + PKCE 는 oidc-client-ts 기본
  scope: 'openid profile email',
});

// 로그인 시작: await userManager.signinRedirect();
// 콜백 페이지: const user = await userManager.signinCallback();
// user.profile.sub === users.id(UUID, 안정적 키 — 이메일 아님),
// user.profile.email(이메일), user.profile.preferred_username(이메일), user.profile.name (§클레임 매핑)
```

**refresh token rotation 주의**: taspa 는 refresh token 재사용을 금지한다
(`reuseRefreshTokens=false` — 갱신할 때마다 새 refresh token 으로 교체되고 이전 것은 폐기).
여러 탭이 같은 refresh token 으로 동시에 갱신하면 나중 요청이 실패하므로, SPA 에서는
`automaticSilentRenew` 사용 시 탭 간 저장소 공유(기본 `sessionStorage` 대신 `localStorage` 등)와
갱신 직렬화를 고려해야 한다. 브라우저에 refresh token 을 두는 위험 자체도 감안할 것
(가능하면 짧은 세션 + 재로그인(SSO 세션 덕에 즉시 복귀)이 단순하다).

또한 SPA 오리진에서 taspa API 를 직접 호출해야 한다면 `taspa.cors.allowed-origins` 에
해당 오리진을 등록해야 한다(기본은 cross-origin 전면 거부).

## 2단계 (E). 서버 대 서버(M2M) — client_credentials

사용자가 관여하지 않는 연동이다: POS 단말의 결제 승인, HR 시스템의 계정 프로비저닝(SCIM),
결제 시스템의 식수 적재, 예측 파이프라인의 집계 조회.

★**이 절이 없던 동안 M2M 연동에는 문서가 한 줄도 없었다.** OpenAPI(Swagger UI)에도 보안 스키마가
없어, 연동 담당자는 어떤 grant 를 쓰는지·어떤 scope 가 있는지·왜 403 이 나는지를 알 방법이 없었다.

### 토큰 발급

```bash
curl -u "$CLIENT_ID:$CLIENT_SECRET" \
  -X POST http://localhost:9100/oauth2/token \
  -d grant_type=client_credentials \
  -d scope="meal.consumption.write"
```

Spring 이라면 `spring-boot-starter-oauth2-client` 의 `client_credentials` 등록을 쓰면 된다
(웹 티어의 POS BFF `web/lib/pos-terminal.ts` 가 같은 흐름을 손으로 구현한 예다).

### ★M2M 토큰은 "결속(binding)"으로 테넌시가 정해진다

scope 만으로는 부족하다 — **어느 조직·어느 매장의 데이터인지**는 클라이언트 등록 설정이 정한다.
관리 콘솔 `/admin/clients` 에서 등록할 때 지정한다.

| 결속 | 등록 설정 | 토큰 클레임 | 없으면 |
|------|-----------|-------------|--------|
| 조직 | `orgId` | `org_id` | 조직 스코프 API 전부 403(fail-closed) |
| 가맹점 | `merchantId` | `merchant_id` | 결제 승인·취소·환불 전부 403 |

### 주요 scope

| scope | 용도 | 필요한 결속 |
|-------|------|-------------|
| `meal.redeem` | 식권 승인·취소·환불, 단말 신원 조회(`GET /api/merchant/me`) | merchant |
| `meal.consumption.write` | 소비(식수) 이벤트 적재 | org |
| `meal.consumption.read` | 자기 조직 식수 집계 조회 | org |
| `meal.consumption.read.all` | 전 조직 집계 조회(신뢰 플랫폼 전용) | — |
| `meal.forecast.read` | 식수 예측 조회 | org |
| `org.scim` | SCIM 2.0 사용자 프로비저닝 | org |
| `calendar.read` | 자기 조직 캘린더 조회 | org |

전체 목록은 `application.yml` 의 `taspa.allowed-scopes` 이고, 관리 콘솔의 클라이언트 등록 화면이
같은 목록을 체크박스로 보여준다. 등록에 없는 scope 를 요청하면 토큰 발급이 400 으로 거절된다.

### ★사용자 위임 토큰으로는 이 API 들에 도달할 수 없다

기계 전용 표면(`org.scim`·`meal.consumption.write`·`meal.forecast.read`)은 IAM 엔진이
`taspa:PrincipalType == M2M` 조건으로 막는다. 사용자 로그인으로 받은 액세스 토큰에 그 scope 가
붙어 있어도 거부된다 — 제3자 앱이 사용자를 대신해 장부를 적재하거나 조직 인사를 바꾸는
confused-deputy 를 구조적으로 닫기 위한 것이다(`docs/architecture.md` IAM 절).

### 403 이 나면 확인할 것

1. 토큰에 그 scope 가 실렸는가(`https://jwt.io` 로 디코드해 `scope` 클레임 확인).
2. 클라이언트에 org/merchant 결속이 있는가(`org_id`/`merchant_id` 클레임).
3. 경로의 orgId 와 토큰의 `org_id` 가 같은가 — **다르면 언제나 403**이다(테넌시 격리).
4. 사용자 로그인으로 받은 토큰을 쓰고 있지 않은가(위 절).

## 클레임 매핑 — sub 은 안정적 UUID, 이메일은 email 클레임

taspa 가 발급하는 표준 클레임. **`sub` 은 id_token·access_token 모두**에 있고, 나머지 신원(PII) 클레임은
**id_token 과 `/userinfo` 에만** 실린다 — access_token 은 베어러 토큰이라 PII 를 넣지 않는다. 따라서 사용자
프로필이 필요하면 **access_token 이 아니라 id_token 또는 `/userinfo`** 를 읽어야 한다. `/userinfo` 응답은
id_token 클레임을 **요청 scope 기준 표준 클레임 화이트리스트로 필터링한 투영**이다(비표준 커스텀 클레임은 제외됨).

| 클레임 | 값 | 요구 scope | 실리는 토큰 |
|--------|-----|-----------|-------------|
| `sub` | **`users.id`(UUID) — 발급자 내 불변·재사용 금지 식별자** | 항상 | id_token · access_token · /userinfo |
| `email` / `email_verified` | 이메일 / 검증 여부 | `email` | id_token · /userinfo |
| `name` | 표시 이름(`display_name` ?: 이메일 로컬파트) | `profile` | id_token · /userinfo |
| `preferred_username` | 이메일(사람이 읽는 로그인 식별자, 변경 가능) | `profile` | id_token · /userinfo |

**계정 키는 반드시 `sub`(UUID)로 저장한다.** 이메일은 표시·연락·검색용으로만 쓰고 1차 키로 쓰지 않는다 —
이메일은 사용자가 바꿀 수 있고, 바뀌어도 `sub` 은 동일하게 유지되므로 같은 계정을 계속 가리킨다.

### ⚠️ 마이그레이션 주의 (sub=이메일 → sub=UUID 변경)

과거 taspa 는 `sub` 을 **이메일**로 발급했다. 이제 `sub` 은 **`users.id`(UUID)** 다.

- **sub 을 이메일로 저장해 두던 기존 클라이언트**는 다음 로그인부터 `sub` 값이 달라져(이메일 → UUID)
  기존 로컬 계정과 매핑이 끊긴다. 조치 중 하나가 필요하다:
  1. (권장) 한 번의 마이그레이션으로 로컬 사용자 테이블의 키를 `email` 에서 taspa `sub`(UUID)로 재매핑한다.
     최초 로그인 시 `email`(=이전 sub)로 기존 행을 찾아 새 `sub`(UUID)를 채우고, 이후에는 `sub` 으로만 조회.
  2. 또는 로컬 계정 조회를 (전환기 동안) `email` 클레임 기준으로 유지하되, 신규 저장 키는 `sub`(UUID)로 전환.
- **신규 클라이언트**는 처음부터 `sub`(UUID)를 유일 키로 사용하면 이 문제가 없다.
- 세션/토큰 무효화: 서버 측 `principal_name` 인덱스는 여전히 이메일이므로 taspa 내부 세션·동의·관리 조회는
  영향받지 않는다. 변경 범위는 **발급 토큰의 sub 값**뿐이다.

## 체크리스트

- [ ] taspa에 클라이언트 등록(client_id/secret/redirect_uri/scope)
- [ ] issuer-uri 설정(`http://localhost:9100`)
- [ ] `openid` 스코프 포함
- [ ] redirect_uri 정확히 일치
- [ ] (기밀 클라이언트) secret 안전 보관 / (공개 클라이언트) PKCE 사용
- [ ] 로컬 계정 키는 `sub`(UUID)로 저장 — 이메일을 키로 쓰지 않음(§클레임 매핑)
- [ ] 기존 클라이언트 마이그레이션: sub=이메일 → sub=UUID 재매핑 완료(§마이그레이션 주의)
- [ ] (로그아웃 필요 시) post_logout_redirect_uri 등록 + RP-Initiated Logout 배선(§로그아웃) —
      OP-Initiated Back-Channel Logout 은 SAS 1.4.2 미지원(완전 SLO 는 커스텀 필요)
- [ ] (M2M) 클라이언트에 org/merchant **결속** 지정 — scope 만으로는 403 이다(§2단계 E)
- [ ] (선택) `taspa.client.audience` 지정 — 다른 서비스용 토큰을 내 API 가 받지 않게 한다

## 연동이 막혔을 때 — 오류별 첫 확인

| 증상 | 뜻 | 확인할 것 |
|------|-----|-----------|
| 한국어 "로그인 요청을 처리할 수 없습니다" + `invalid_client` | client_id 미등록 | 관리 콘솔 목록의 ID 와 정확히 같은지 |
| 같은 화면 + `invalid_request` (redirect_uri) | **정확일치 실패** | 끝의 `/` 하나, http/https, 포트까지 |
| 토큰 엔드포인트 400 `invalid_scope` | 등록에 없는 scope 요청 | 클라이언트 등록의 scope 목록 |
| API 403 (M2M) | 결속 없음 또는 org 불일치 | 토큰의 `org_id`/`merchant_id` 클레임 |
| API 401 `REAUTH_REQUIRED` | step-up 필요(세션 경로) | 최근 재인증 — 이건 연동 문제가 아니다 |

★인가 오류는 **서버 로그에도 남는다**(`OAuth2ErrorPageHandler` WARN + 요청 상관관계 ID).
운영자에게 문의할 때 화면의 오류 코드와 시각을 함께 주면 그 줄을 바로 찾을 수 있다.
