# taspa 아키텍처

## 1. 개요

taspa는 여러 서비스가 공유하는 **중앙 인증 시스템(IdP)**이다. 표준 OAuth2 / OIDC를 통해
인증(Authentication)과 토큰 발급을 일원화하고, 각 서비스(클라이언트)는 taspa가 발급한
JWT를 검증(Resource Server)만 하면 된다.

```
                          ┌──────────────────────────────────────────┐
                          │                 taspa (IdP)              │
                          │            http://localhost:9100         │
                          │                                          │
  ┌────────────┐  1.로그인 │  ┌───────────┐   ┌────────────────────┐  │
  │  End User   ├────────► │  │ formLogin │   │ OAuth2/OIDC 엔드포인트 │  │
  │ (Browser)   │◄────────┤  │ /login    │   │ /oauth2/authorize   │  │
  └─────┬──────┘  SSO세션  │  └─────┬─────┘   │ /oauth2/token       │  │
        │                  │        │         │ /oauth2/jwks        │  │
        │ 2.authorize      │   ┌────▼─────┐   │ /.well-known/...    │  │
        │  +PKCE           │   │  users   │   └─────────┬──────────┘  │
        │                  │   │  (JPA)   │             │             │
        │                  │   └──────────┘   ┌─────────▼──────────┐  │
        │                  │                  │ oauth2_registered_ │  │
        │                  │                  │ client / auth /    │  │
        │                  │                  │ consent (JDBC)     │  │
        │                  │                  └────────────────────┘  │
        │                  └───────────────────────┬──────────────────┘
        │ 3.code                                   │ JWKS(공개키)
        ▼                                          ▼
  ┌─────────────────┐  4.code→token(PKCE)   ┌─────────────────────────┐
  │  Client App     ├──────────────────────►│  Client App (Resource)  │
  │  (8080대)       │◄──────────────────────┤  taspa-spring-boot-     │
  │  OIDC 로그인     │      JWT access/id     │  starter 로 JWT 검증     │
  └─────────────────┘                        └─────────────────────────┘
```

## 2. 모듈 책임

| 모듈 | 책임 |
|------|------|
| `:server` | 계정(회원가입/로그인), OAuth2 Authorization Server, OIDC, JWKS 노출, 감사 |
| `:client:spring-boot-starter` | Spring 클라이언트가 의존성 하나로 JWT 검증을 자동 구성 (Resource Server) |
| `e2e` | 서버 헬스 스모크 + 패스키 시나리오 테스트 (Playwright, CDP 가상 인증기) |

### `:server` 패키지 구조 (package-by-feature)

| 패키지 | 책임 |
|--------|------|
| `account` | 회원가입 API(`POST /api/accounts/signup`)와 계정 서비스, 웹 페이지(`AccountPageController`: `/account`, `/signup`) |
| `credential` | 비밀번호 정책, 계정 잠금(`AccountLockoutService`), 비밀번호 재설정(`PasswordResetService`+컨트롤러) |
| `login` | identifier-first 로그인 플로우(`LoginFlowController`), 부분 인증 상태(`PendingAuth`), 게이트 전환 로직(`LoginFlowSupport`), formLogin 성공/실패 핸들러, `LoginUserDetailsService` |
| `mfa` | TOTP(`TotpService`), MFA 설정/활성/해제/검증(`MfaService`), `/api/mfa/**`(`MfaController`) |
| `passkey` | WebAuthn(패스키): Spring Security 저장소 어댑터(`JpaPublicKeyCredentialUserEntityRepository`, `JpaUserCredentialRepository`), 관리 서비스(`PasskeyService`), `/api/passkeys`(`PasskeyController`), RP 설정(`WebAuthnProperties`) |
| `federation` | 소셜 로그인(구글·카카오·네이버): 소셜 등록 소스(`SocialClientRegistrations`), userinfo 정규화(`SocialAttributesExtractor` — 조직 OIDC `oidc:{regId}` 브랜치 포함), 계정 연결 분기(`FederatedLoginSuccessHandler` — 조직 OIDC 도메인 일치 강제 포함), 연결 관리(`FederationService`, `/api/federations`) |
| `enterprise` | 기업 SSO(Stage E): 커넥션 CRUD·HRD·도메인 일치 강제·DB→ClientRegistration 변환(`SsoConnectionService`), 소셜+조직 OIDC 통합 레포지토리(`CompositeClientRegistrationRepository`), SAML SP 레지스트리(`DbRelyingPartyRegistrationRepository`), SAML 어트리뷰트 정규화·성공 핸들러(`Saml2AttributesExtractor`, `Saml2FederatedLoginSuccessHandler`) — §8.11 |
| `verification` | 이메일 인증 코드 발급/검증/재발송(`EmailVerificationService`) |
| `mail` | `JavaMailSender` 래핑(`MailService`) — 인증 코드/재설정 링크 발송 |
| `oidc` | `AuthorizationServerSettings`, JDBC 저장소(registered client/authorization/consent), 데모 클라이언트 시딩, 동의 화면(`ConsentController`) |
| `token` | JWK 영속화·회전(`JwkStorageService`), DB 기반 동적 `JWKSource`(`JwkConfig`), OIDC 클레임 커스터마이저(`TokenCustomizerConfig`: 서명 kid 고정 + sub=`users.id`(UUID) 고정[id_token·access_token] + scope에 따라 email/name/preferred_username 클레임 추가[id_token 한정] — §3.2) |
| `audit` | 감사 이벤트 기록 — 구조화 로그 + DB 영속화(`audit_events`, JSON detail) |
| `session` | 원격 세션 관리(Spring Session JDBC): 활성 세션 목록/개별·일괄 폐기(`SessionManagementService`, `/api/sessions` — `SessionController`), 세션 메타 기록(`SessionMetadata`: IP/브라우저 라벨) — §4.1 |
| `domain/{user,mfa,verification,credential,passkey,federation,sso,jwk,audit}` | JPA 엔티티 + 리포지토리(`User`, `BackupCode`, `EmailVerificationCode`, `PasswordResetToken`, `PasskeyUserEntity`, `PasskeyCredential`, `FederatedIdentity`(+`connection_id`), `SsoConnection`, `SsoDomain`, `JwkKey`, `AuditEvent`) |
| `common/crypto` | `AesEncryptionService`(AES-GCM) — 용도별 빈 분리(`EncryptionConfig`: `mfaEncryptionService`(TOTP 시크릿 + `sso_connections.oidc_client_secret_encrypted`) / `jwkEncryptionService`(서명 개인키)). `taspa.jwk.encryption-key` 미설정 시 mfa 키로 폴백하지만 **이는 dev 단일 키 운영 전용**이다 — prod 는 `application-prod.yml` 이 두 환경변수를 모두 필수로 요구하고 `ProductionConfigChecks.checkEncryptionKeys` 가 **두 키가 같은 값이면 기동을 중단**한다(§3.1) |
| `common/exception` | `ErrorCode`, `AuthException`, `GlobalExceptionHandler` |
| `common/security` | `SecureTokenGenerator` (SHA-256 해시, 256bit 랜덤 토큰) |
| `config` | `SecurityConfig`(이중 필터체인 + 명시적 `HttpSessionSecurityContextRepository`/`RequestCache`), `PasswordEncoderConfig`(BCrypt 12), `CorsConfig` |

## 3. 토큰 전략

- **인가(Authorization)**: OAuth2 Authorization Code Grant + **PKCE**. 공개/기밀 클라이언트 모두 PKCE 사용을 권장(데모 클라이언트는 `requireProofKey(true)`).
- **Access Token**: JWT, 기본 수명 **15분**. `client/spring-boot-starter`가 issuer 기반으로 서명 검증.
- **Refresh Token**: 수명 **30일**, **rotation**(`reuseRefreshTokens=false`)으로 재사용 방지.
- **ID Token(OIDC)**: `openid` 스코프 요청 시 발급. 사용자 신원 클레임 포함(§3.2).
- **키 배포(JWKS)**: `/oauth2/jwks`로 공개키 공개. 클라이언트는 issuer 디스커버리로 자동 취득.
- **서명 키**: RSA 2048, DB 영속화(`jwk_keys`, 개인키 포함 JSON 을 AES-GCM 암호화 저장). 재시작해도 같은 키를 로드하므로 기존 토큰이 계속 검증된다.

### 3.2 subject(sub) 안정화 — sub=UUID, 이메일은 email 클레임

`TokenCustomizerConfig` 는 scope 에 따라 표준 클레임을 채운다. `sub` 은 id_token·access_token 모두에 싣지만,
나머지 신원(PII) 클레임(`email`/`name`/`preferred_username`)은 **id_token 에만** 넣는다 — access_token 은
베어러 토큰이라 리소스 서버가 로그로 남길 수 있어 PII 유출면을 줄인다(userinfo 는 id_token 에서 파생되므로 노출은 유지).

| 클레임 | 값 | scope | 실리는 토큰 |
|--------|-----|-------|-------------|
| `sub` | **`users.id`(UUID) — 안정적·불변 식별자** | 항상 | id_token · access_token |
| `email` / `email_verified` | 이메일 / 검증 여부 | `email` | id_token(→ /userinfo) |
| `name` | `display_name` ?: 이메일 로컬파트 | `profile` | id_token(→ /userinfo) |
| `preferred_username` | 이메일(사람이 읽는 로그인 식별자) | `profile` | id_token(→ /userinfo) |

- **왜 sub 을 이메일에서 UUID 로 바꿨나**: Spring Authorization Server 기본값은 `sub` = principal name(=이메일)이다. 그러나 OIDC 규격상 **sub 은 발급자 내에서 불변·재사용 금지**여야 하고, 이메일은 사용자가 바꿀 수 있는 값이다. sub 을 이메일에 묶어두면 이메일 변경이 곧 신원 변경이 되어(다운스트림 계정 매핑이 깨진다) 안전한 이메일 변경을 막는다. 그래서 커스터마이저가 발급 토큰의 sub 을 `users.id`(UUID)로 덮어쓴다 — **이메일이 바뀌어도 sub 은 불변**이다.
- **principal name 은 이메일 그대로**: 로그인 식별자(`LoginUserDetailsService`)·Spring Session `PRINCIPAL_NAME` 인덱스·`oauth2_authorization.principal_name` 은 모두 이메일을 계속 쓴다. 토큰 subject 매핑만 UUID 로 분리한다.
- **userinfo 도 동일**: `/userinfo` 응답은 SAS 기본 매퍼(`DefaultOidcUserInfoMapper`)가 id_token 클레임을 **그대로 복사하는 게 아니라 요청 scope 기준 표준 클레임 화이트리스트로 필터링**해 내보낸다(sub 은 항상 포함, `email`/`email_verified` 는 `email` scope, `name`/`preferred_username` 은 `profile` scope 의 표준 클레임이라 통과). taspa 가 싣는 클레임은 전부 표준 클레임이라 결과가 id_token 과 동일하고 **sub=UUID 가 일관되게 노출된다**. 다만 향후 비표준 커스텀 클레임을 id_token 에 추가해도 화이트리스트에서 빠져 `/userinfo` 에는 나타나지 않는다는 점에 유의.
- **하위호환 주의(마이그레이션)**: sub 을 계정의 1차 키로 저장해 둔 **기존 클라이언트는 이 변경으로 매핑이 어긋난다**(이전 sub=이메일 → 신규 sub=UUID). 마이그레이션 지침은 `docs/integration-guide.md` 참조 — 신규 클라이언트는 처음부터 sub(UUID)을 계정 키로, 이메일은 표시/연락용으로만 사용한다. 회귀 테스트: `TokenCustomizerConfigTest`.

### 3.1 서명 키 수명주기 (ACTIVE → RETIRED → 삭제)

- **부트스트랩**: ACTIVE 키가 없으면 `JwkStorageService` 가 생성해 저장(kid=UUID) — 재기동 멱등.
- **회전**: `JwkRotationJob`(일 1회)이 `rotation-period`(기본 30d) 경과 시 ACTIVE→RETIRED 로 내리고 새 ACTIVE 를 만든다. `SELECT ... FOR UPDATE` 로 중복 회전을 방지한다.
- **유예(RETIRED)**: RETIRED 키도 JWKS 에 계속 게시되어 kid 매칭으로 기존 토큰 검증을 지탱한다.
  `retirement-grace`(기본 7d) **하한 = 발급 토큰 최대 수명 + 외부 RS 의 JWKS 캐시 TTL** — 이보다 짧으면 유효한 토큰이 조기 무효화된다.
- **삭제**: grace 를 넘긴 RETIRED 행은 잡이 삭제한다. 삭제 즉시 해당 키로 서명된 토큰은 전부 검증 불가.
- **다중 키 공존 시 주의(불변식)**: `NimbusJwtEncoder` 는 서명 가능한 RSA 키가 2개 이상 매칭되면 첫 키를 고르는 게 아니라 **`JwtEncodingException` 을 던진다**. 그래서 `TokenCustomizerConfig` 가 모든 JWT 발급에 `jwsHeader.keyId(activeKid)` 를 고정한다 — 이 줄을 제거하면 회전 유예 구간의 토큰 발급이 전부 실패한다(회귀 테스트: `JwkStorageIntegrationTest`).
- **캐시**: `JwkStorageService` 는 복호화된 키를 60초 인메모리 캐시한다. `rotate()`/`purgeExpired()` 는
  **트랜잭션 커밋 확정 후** 캐시를 무효화한다(커밋 전 무효화는 동시 조회의 재캐시로 무력화된다).
  단, **DB 행을 SQL 로 직접 삭제·수정하는 경우 캐시 무효화가 트리거되지 않는다** — 반영 지연 상한은
  인스턴스당 캐시 TTL 60초이며, 다중 인스턴스에서는 이 TTL 이 본질적 상한이다.

**유출 대응 runbook**: 서명 키 유출 의심 시 ① `JwkStorageService.rotate()` 즉시 실행(새 ACTIVE 발급, 유출 키는 RETIRED), ② 유출 키의 `jwk_keys` 행을 즉시 삭제(= 해당 키로 서명된 모든 토큰 즉시 무효화 — 정상 사용자 토큰도 함께 무효화되므로 재로그인 유도 공지 병행). **SQL 직접 삭제는 인스턴스별 스냅샷 캐시를 무효화하지 못하므로 최대 60초(캐시 TTL) 동안 유출 키가 JWKS 에 남아 그 키의 토큰이 계속 검증될 수 있다 — 즉시 반영이 필요하면 전 인스턴스를 재기동한다**, ③ 외부 RS 의 JWKS 캐시 TTL 경과(또는 재기동) 후 완전 차단됨을 확인.

**prod 암호화 키 강도 규칙(`EncryptionConfig` → `ProductionConfigChecks.checkEncryptionKeys`)**: prod 프로필 기동 시 `MFA_ENCRYPTION_KEY`·`TASPA_JWK_ENCRYPTION_KEY` 각각에 대해 **① 빈 값 ② 소스에 공개된 dev 기본값(`dev-only-key-do-not-use-in-production`) ③ 32자 미만 ④ 서로 다른 문자 10종 미만(길이만 채운 반복 패턴)** 을 거부하고, 추가로 **⑤ 두 키가 같은 값이면 기동을 중단**한다. 키는 salt 없는 SHA-256 1회로 AES 키가 되므로(`AesEncryptionService`) 원문 문자열 자체가 전수대입 대상이고, 두 키를 같은 값으로 두면 키를 나눈 목적(한쪽 유출 격리)이 사라져 TOTP 시크릿 유출이 곧 서명 개인키 유출이 된다. 검사는 `ProductionSafetyValidator` 빈이 아니라 **키를 소비하는 `EncryptionConfig` 의 `init` 에서** 호출한다 — 그 빈이 먼저 생성돼 약한 키로 암호화를 시작하는 순서를 막기 위함이다. 회귀 테스트: `config/ProductionConfigChecksTest`.

**mfa 키 폴백은 dev 전용이다**: `taspa.jwk.encryption-key` 미설정 시 mfa 키로 폴백하지만(`EncryptionConfig`), prod 에서는 `application-prod.yml` 이 `${TASPA_JWK_ENCRYPTION_KEY}` 를 기본값 없이 요구하고(미주입이면 플레이스홀더 해석 실패로 기동 불가) 폴백이 성립하더라도 위 ⑤ 가 거부한다. 즉 **prod 에서 두 저장물이 같은 키로 암호화되는 상태는 존재할 수 없다.**

**암호화 키 전환 절차(폴백/기존 키 → 새 전용 키)**: 위 규칙 때문에 **두 키를 같은 값으로 운영 중이던 기존 설치는 이번 규칙 이후 기동이 막힌다** — 그 복구 경로가 이 절차다. `jwk_keys` 행은 그때의 키로 암호화돼 있어, 키만 바꿔 재기동하면 **기존 행 전체가 복호화 불가**가 되어 토큰 발급·JWKS 가 전면 장애가 된다(부트스트랩은 ACTIVE 행이 없을 때만 동작하므로 자동 복구도 없다 — `JwkStorageService.load()` 가 복구 절차를 가리키는 메시지로 fail-fast 한다). 전환은 반드시: ① 이전 키 설정으로 기동한 상태에서 `rotate()` 는 **새 키로 재암호화하지 않으므로**(새 ACTIVE 행만 현재 키로 암호화된다) 단순 rotate 로는 부족 — 이전 키로 복호화한 각 행을 새 키로 재암호화하는 스크립트를 돌리거나, ② 다운타임을 허용한다면 `DELETE FROM jwk_keys` 후 새 키로 재기동(재부트스트랩 — **기존 발급 토큰 전체가 무효화**되므로 재로그인 공지 병행). MFA 키를 바꾸는 경우도 같다 — `users.mfa_secret_encrypted` 와 `sso_connections.oidc_client_secret_encrypted` 가 함께 복호화 불가가 되므로 재암호화 스크립트 또는 MFA 재등록·커넥션 시크릿 재입력이 필요하다.

## 4. 데이터베이스

- **users** (`V1`, `V3`): 계정. `email_verified`·`status`·계정 잠금 필드에 더해 `V3`에서 `display_name`, `mfa_enabled`, `mfa_secret_encrypted`(AES-GCM 암호문) 추가.
- **oauth2_registered_client / oauth2_authorization / oauth2_authorization_consent** (`V2`): Spring Authorization Server 1.4.x 공식 JDBC 스키마. PostgreSQL은 `blob`을 지원하지 않아 모든 `blob`을 `text`로 변환. 진행 중 authorization과 동의 내역을 JDBC로 영속화한다.
- **backup_codes / email_verification_codes / password_reset_tokens** (`V3__google_style_auth.sql`): MFA 백업 코드(bcrypt 해시 + `used_at`), 이메일 인증 코드(SHA-256 hex 해시 + `expires_at`/`consumed_at`), 비밀번호 재설정 토큰(SHA-256 해시 + `expires_at`/`used`). 모두 `user_id` FK(`ON DELETE CASCADE`).
- **jwk_keys / audit_events** (`V8__jwk_and_audit.sql`): 서명 키(개인키 포함 JSON 의 AES-GCM 암호문, `status` ACTIVE|RETIRED — §3.1)와 감사 이벤트(JSON `detail`, 보존 기본 365일 — `RetentionCleanupJob`).
- **spring_session / spring_session_attributes** (`V9__spring_session.sql`): Spring Session JDBC 3.4.2 공식 PostgreSQL 스키마 그대로(`spring.session.jdbc.initialize-schema=never` — 스키마의 단일 소스는 Flyway). §4.1 참고.
- **sso_connections / sso_domains** (`V14__enterprise_sso.sql`): 기업 SSO 커넥션(프로토콜·엔드포인트·인증서·정책 플래그, `oidc_client_secret_encrypted` 는 `MFA_ENCRYPTION_KEY` 로 암호화한 AES-GCM 암호문)과 도메인 매핑(`verified` 수동 검증). `federated_identities.connection_id`(`ON DELETE SET NULL`) 추가 — 소셜 연결은 null. §8.11 참고.
- Hibernate `ddl-auto: validate`, Flyway가 스키마의 단일 소스.

### 4.1 세션 저장 (Spring Session JDBC)

- **저장 구조**: 세션 본체는 `spring_session`(만료·`PRINCIPAL_NAME` 인덱스), 속성은 `spring_session_attributes`(JDK 직렬화 BYTEA, `ON DELETE CASCADE`). `PRINCIPAL_NAME` 은 세션의 `SPRING_SECURITY_CONTEXT` 에서 `authentication.name`(=이메일)으로 자동 인덱싱된다 — pending(부분 인증) 세션은 SecurityContext 가 없어 인덱싱되지 않으므로 세션 목록에 잡히지 않는다(원하는 동작, §7 불변식 유지).
- **재시작 생존**: 세션 상태가 DB 에 있으므로 서버를 재시작해도 로그인이 유지된다(인메모리 세션의 "배포마다 전원 로그아웃" 제거).
- **원격 세션 관리**: `FindByIndexNameSessionRepository.findByPrincipalName(email)` 로 본인 세션을 조회하고 `deleteById` 로 즉시 폐기한다(부활 없음 — 3.4.2 소스 실측). 화면/API 는 세션 ID 원문 대신 **publicId(세션 ID SHA-256 hex 앞 16자)** 만 노출한다. 폐기 계열(`DELETE /api/sessions/{publicId}`, `POST /api/sessions/revoke-others`)은 step-up(`@RequireRecentAuth`) 대상이며, `/api/**` CSRF 면제에서도 제외되어 `X-CSRF-TOKEN` 헤더가 필요하다(SameSite=Lax 가 막지 못하는 서브도메인發 same-site 요청 방어 — CORS 도 화이트리스트 기본 거부, `CorsConfig`). 비밀번호 재설정 시 신뢰 기기와 함께 **모든 세션을 폐기**한다(계정 탈취 대응).
- **직렬화 주의(불변식)**: 세션 속성은 전부 Serializable 이어야 한다. 유일한 예외였던 패스키 등록 옵션(`PublicKeyCredentialCreationOptions`)은 `CachedCreationOptionsRepository`(세션엔 랜덤 키 문자열만, 옵션 객체는 인메모리 TTL 5분 — 단일 인스턴스 전제)로 우회한다. 6.4 DSL 에 주입 지점이 없어 `SecurityConfig` 가 빌드된 체인에서 옵션 필터를 교체하고 등록 필터에 저장소를 주입한다.
- **세션 쿠키**: Spring Session 의 쿠키명은 `SESSION`(Base64 인코딩) — 로그아웃의 `deleteCookies` 도 `SESSION` 을 지운다.

**사고 대응 runbook(전체 세션 일괄 무효화)**: 대규모 세션 탈취 의심 시 `TRUNCATE spring_session CASCADE;` 실행 — 모든 사용자가 즉시 로그아웃되며(진행 중 요청만 해당 요청 수명 동안 유효) 재로그인으로 복구된다. 특정 사용자만이라면 `DELETE FROM spring_session WHERE principal_name = '<이메일>';` 또는 관리 API 경로(`SessionManagementService.revokeAll`)를 사용한다.

## 5. auth-playground에서 계승한 패턴

| 패턴 | 계승 내용 |
|------|-----------|
| 비밀번호 정책 | `PasswordPolicyService` + `@ConfigurationProperties`(min-length 12 등) 이식 |
| bcrypt | `BCryptPasswordEncoder(strength=12)` |
| bcrypt 타이밍 방어 | 존재하지 않는 계정에도 더미 해시로 bcrypt 비교(Phase 2 로그인 서비스에서 재도입) |
| 토큰 해시 저장 | `SecureTokenGenerator`(SHA-256 해시) — Phase 2 이메일 검증/리셋 토큰에 사용 |
| 계정 잠금 | `failed_login_attempts`, `locked_until` 컬럼 + `UserDetails.isAccountNonLocked` |
| 예외 처리 | `ErrorCode` → `AuthException` → `GlobalExceptionHandler` HTTP 매핑 |
| 통합 테스트 | `IntegrationTestBase`(`@SpringBootTest` + Testcontainers `postgres:16`) |

### auth-playground 대비 개선점

- **세션 토큰(X-Session-Token) → 표준 OAuth2/OIDC**: 단일 앱 전용 세션 인증을 다중 서비스용 표준 프로토콜로 대체.
- **JWK 관리 도입**: 자체 서명 키 + JWKS 공개로 무상태 토큰 검증 지원.
- **audit 도입**: 계정/인증 이벤트 기록 지점 마련(`AuditEventService`).
- **email verification / status**: 계정 라이프사이클(미검증/정지) 표현 컬럼 선반영.

## 6. 로드맵 상세

- **Phase 1 (현재)**: 코어 계정 + OAuth2 Authorization Code/PKCE + OIDC + JWKS. 컴파일·기동 가능한 스켈레톤.
- **Phase 2**: MFA(TOTP, auth-playground 이식), 이메일 검증(Mailpit), JWK **DB 영속화 + 회전**, 감사 이벤트 **DB 영속화**, 로그인 서비스(계정 잠금/타이밍 방어) 완성.
- **Phase 3**: 비 Spring 클라이언트 SDK, **서비스 간 인증**(client_credentials 스코프/정책), 클라이언트 셀프서비스 등록.
- **Phase 4**: 리스크 기반(Adaptive) 인증, 디바이스/세션 관리 UI. (Passkey/WebAuthn 은 Phase 2 에서 선행 구현 — §8 참고)

## 7. 구글 스타일 로그인 플로우

### 7.1 identifier-first + 게이트 시퀀스

```
GET /login ── 이메일 입력 ──► POST /login/identifier
                               │ UserRepository 조회
                               │  없음 → "taspa 계정을 찾을 수 없습니다" 재렌더
                               │  있음 → 세션에 login hint 저장
                               ▼
GET /login/password (이메일 칩) ── 비밀번호 ──► POST /login/password  [formLogin]
                                                │ 비밀번호 인증 성공
                                                ▼
                          MfaAwareAuthenticationSuccessHandler
                          (이 시점엔 필터가 이미 세션에 완전 인증을 저장한 상태)
                                                │
              ┌─────────────────────────────────┼─────────────────────────────────┐
              │ 게이트 불필요                      │ 게이트 필요(미인증 이메일 또는 MFA)  │
              ▼                                  ▼
   SavedRequestAware 로 위임          1) SecurityContext clear + 세션의
   (원래 요청/authorize 계속)             SPRING_SECURITY_CONTEXT 제거(빈 컨텍스트 저장)
                                      2) 세션에 PendingAuth(userId, stage, expiresAt=+5분)
                                      3) stage 에 따라 리다이렉트
                                         · 이메일 미인증 → /login/verify-email
                                         · MFA 활성    → /login/mfa
                                                ▼
              GET|POST /login/verify-email      GET|POST /login/mfa
              (6자리 코드; 통과 후 MFA 필요        (TOTP 6자리 / "다른 방법"→백업 코드)
               하면 stage=MFA 로 전환)                    │
                                                        │ 검증 성공
                                                        ▼
                                        LoginFlowSupport.completeAuthentication
                                        · request.changeSessionId()  (세션 고정 방어)
                                        · UserDetails 로드 → 완전 인증을 SecurityContext 에 설정
                                        · HttpSessionSecurityContextRepository.saveContext
                                        · PendingAuth 제거
                                        · saved request 로 리다이렉트(없으면 /account)
```

가입 직후에는 비밀번호를 다시 받지 않고(방금 본인이 만든 계정이므로) 곧바로
`PendingAuth(EMAIL_VERIFICATION)` 을 만들어 인증 페이지로 보낸다.

### 7.2 왜 pending 상태를 SecurityContext 밖에 두는가 (핵심 보안 설계)

Spring Authorization Server 의 `/oauth2/authorize` 는 `SecurityContext` 의 인증 여부만 본다.
만약 "비밀번호는 통과했지만 MFA/이메일 인증은 아직"인 **부분 인증**을 `authenticated=true` 로
`SecurityContext`(=세션의 `SPRING_SECURITY_CONTEXT`)에 넣어두면, authorize 엔드포인트가 이를
완전 인증으로 오인하여 **MFA 없이 authorization code 를 발급**하는 취약점이 생긴다.

그래서 taspa 는:

- 부분 인증을 절대 `SecurityContext` 에 넣지 않고, 직렬화 가능한 `PendingAuth`(userId·stage·만료)만
  세션 속성으로 보관한다.
- formLogin 필터가 성공 시점에 세션에 저장해 둔 완전 인증을, 게이트가 필요하면 성공 핸들러가
  즉시 걷어낸다(빈 컨텍스트 저장 + 세션 속성 직접 제거).
- 게이트 통과(정확한 TOTP/백업 코드/이메일 코드) 시에만 `changeSessionId()` 후 완전 인증을 세운다.

이 불변식을 지키는 회귀 테스트가 `GoogleLoginFlowIntegrationTest` 의
`mfa gate prevents oauth2 authorize code issuance until totp verified` 이다.
pending 상태에서 `GET /oauth2/authorize` 는 code 없이 `/login` 으로 리다이렉트되고,
MFA 통과 후 동일 요청은 클라이언트 redirect_uri 로 code 를 발급한다 — 이 대비가 이 설계의 존재 이유다.

## 8. 인증 수단(Authentication Methods)

### 8.1 패스키(WebAuthn) 아키텍처

Spring Security 6.4 네이티브 `http.webAuthn { }` DSL 을 사용한다. 컨트롤러 없이 필터가 4개의
엔드포인트를 처리한다.

| 엔드포인트 | 인증 | 처리 필터 |
|-----------|------|-----------|
| `POST /webauthn/register/options` | 필수(미인증 400) | `PublicKeyCredentialCreationOptionsFilter` |
| `POST /webauthn/register` | 필수, JSON 에 `label` 필수 | `WebAuthnRegistrationFilter` |
| `POST /webauthn/authenticate/options` | 익명 허용 → `allowCredentials` 빈 배열(usernameless) | `PublicKeyCredentialRequestOptionsFilter` |
| `POST /login/webauthn` | 익명(어서션 검증) | `WebAuthnAuthenticationFilter` |

모두 CSRF 보호 대상이므로 `X-CSRF-TOKEN` 헤더가 필요하다(`/api/**` 는 CSRF ignore — 단
파괴적 세션 API `/api/sessions/**` 는 예외적으로 보호를 유지한다, §4.1).
클라이언트 JS 는 spring-security-web 6.4.4 jar 의 기본 스크립트를
`static/js/webauthn.js` 로 벤더링했다(Apache-2.0). `disableDefaultRegistrationPage(true)` 를 쓰면
기본 JS 서빙이 꺼지기 때문이다.

### 8.2 저장 모델

`@Bean/@Component` 로 등록된 커스텀 저장소를 configurer 가 자동 사용한다(6.4 에는 JDBC 구현이 없다).

- `webauthn_user_entities` — 사용자 핸들. WebAuthn 인증기에는 `external_id`(base64url 랜덤 32B)만
  user handle 로 노출하고 `users.id`(UUID)는 WebAuthn 프로토콜 표면에 노출하지 않는다(인증기 간 상관관계 방지).
  단, OIDC 토큰의 `sub` 은 의도적으로 `users.id`(UUID)를 노출한다(안정적 식별자, §3.2) — 두 노출은 목적이 다르다.
  `user_id` FK ON DELETE CASCADE.
- `webauthn_credentials` — Spring Security `CredentialRecord` 14필드의 영속 표현.
  공개키(COSE)/attestation 은 BYTEA, `Bytes` 값은 base64url 문자열, transports 는 csv.
  어댑터 `save()` 는 upsert — 인증 성공 시 signatureCount/lastUsed 갱신에 사용된다.
- 어댑터 `delete()` 에는 소유권 가드가 있다: 현재 인증 사용자가 소유하지 않은 credential 삭제
  요청은 무시하고 WARN 로그만 남긴다(SS 기본 `DELETE /webauthn/register/{id}` 의 소유권 미검사 방어).
- 관리 API 는 `/api/passkeys`(목록/이름 변경/삭제, 소유권 검사 후 타인 credential 은 404).

### 8.3 패스키의 MFA 생략 불변식

패스키 로그인은 MFA·이메일 인증 게이트를 생략한다(구글 정책과 동일: 패스키 = 소유 + 생체/PIN 으로
2FA 충족). 이것이 안전한 이유는 다음 불변식 때문이다.

> 패스키 등록(`POST /webauthn/register/options|register`)은 **완전 인증된 세션**에서만 가능하고,
> taspa 의 완전 인증은 이메일 인증 게이트를 통과해야만 성립한다. 따라서 **미인증 이메일 계정에
> 패스키가 존재할 수 없다.**

MFA·이메일 게이트는 생략하지만 **계정 상태 검사는 생략하지 않는다**. Spring Security 6.4.4 의
`WebAuthnAuthenticationProvider` 는 어서션 검증 후 UserDetails 의 enabled/locked 를 검사하지
않으므로(바이트코드 실측), taspa 는 `AccountStatusCheckingAuthenticationProvider` 래퍼로 인증 성공
직후 상태를 재검사한다.

- `status != ACTIVE`(SUSPENDED 등) → `DisabledException`. 운영자 정지 조치는 패스키로도 우회할 수 없다.
- `lockedUntil` 잠금 중 → `LockedException`. 잠금은 비밀번호 브루트포스 방어 장치라 패스키(브루트포스
  불가)에는 원리상 불필요하지만, "잠긴 계정은 로그인할 수 없다"는 정책 일관성을 위해 패스키도 차단한다.
  만료된 잠금은 검사 시점에 자동 해제된다.

세션 고정 방어도 명시적으로 주입한다: 6.4.4 의 `WebAuthnAuthenticationFilter` 는 기본 세션 전략이
Null 이라(실측 — 폼 로그인과 달리 `changeSessionId()` 가 내장돼 있지 않다) `SecurityConfig` 가 빌드된
체인에서 필터를 찾아 `ChangeSessionIdAuthenticationStrategy` 를 설정한다.

성공 핸들러는 `PasskeyAuthenticationSuccessHandler` 로 교체했다. 비밀번호 경로와 동일하게
`LOGIN_SUCCESS` 감사 이벤트를 남기고 잠금 카운터를 리셋하며, saved request 가 있으면(OIDC
continuation) 그 URL 로, 없으면(직접 로그인) `/account` 로 보낸다 — 기본 핸들러의 fallback `"/"` 는
매핑이 없어 404 가 되기 때문이다(방어적으로 `GET /` → `/account` 리다이렉트도 추가). SecurityContext
는 동일한 `HttpSessionSecurityContextRepository` 에 저장되므로 OIDC continuation(`/oauth2/authorize`
재진입)이 비밀번호 로그인과 동일하게 성립한다.

### 8.4 rpId / origin 규칙

- `taspa.webauthn.rp-id`(기본 `localhost`) 는 사이트 도메인과 일치해야 한다.
- `taspa.webauthn.allowed-origins`(기본 `http://localhost:9100`) 는 브라우저 오리진과 정확히
  일치해야 한다. 불일치 시 브라우저/서버 검증이 **조용히 실패**하므로 배포 환경에서는 반드시
  실제 도메인/오리진으로 재설정한다.
- 등록 옵션은 `residentKey=REQUIRED`(discoverable), `userVerification=PREFERRED`, attestation NONE,
  EdDSA/ES256/RS256, timeout 5분으로 하드코딩되어 있다(`Webauthn4JRelyingPartyOperations`).

### 8.5 소셜 로그인 (구글 · 카카오 · 네이버)

`oauth2Login`(spring-boot-starter-oauth2-client)은 `@Order(2)` 기본 체인에만 조건부로 붙는다 —
클라이언트 자격 증명 환경변수 쌍이 있는 공급자만 `SocialClientRegistrations` 가 프로그래매틱으로
등록하고(`InMemoryClientRegistrationRepository`), 0건이면 빈이 없어 oauth2Login 자체가 적용되지
않는다(버튼 미노출·기동 정상). SAS 의 `/oauth2/authorize` 와 클라이언트의 `/oauth2/authorization/**`
는 경로가 달라 충돌하지 않는다.

공급자 프로토콜 요약:

| 공급자 | 방식 | 이메일 검증 신뢰 |
|--------|------|------------------|
| 구글 | OIDC (`CommonOAuth2Provider.GOOGLE`) | `email_verified` 클레임 |
| 카카오 | 순수 OAuth2 (`client_secret_post`), userinfo `/v2/user/me` | `is_email_valid && is_email_verified` (이메일은 동의 거부 시 부재 가능) |
| 네이버 | 순수 OAuth2, userinfo 가 `response` 아래 중첩 → 커스텀 UserService 가 평탄화 | 플래그 없음 → **항상 미검증 취급** |

**정책 결정 근거:**

- **소셜 로그인도 로컬 MFA 게이트를 적용한다** (Auth0/Okta 모델). 공급자 인증이 taspa 계정의
  2단계 인증을 대체하지 않는다. 예외는 패스키(자체 2요소)뿐이며, 유일한 MFA 스킵 경로는
  유효한 신뢰 기기 쿠키(§8.6)다. 게이트 판정은 `LoginFlowSupport.requiredGate`(공용 함수)로 수렴하고,
  신뢰 기기 확인은 그 안의 `TrustedDeviceService.validateAndRotate` 호출로 분리되어 있다.
- **자동 연결은 이중 검증 조건**: 공급자 이메일 검증 **AND** 로컬 이메일 검증일 때만 자동 연결한다
  (Keycloak/Auth0 모델). 미검증 로컬 계정에 자동 연결하면 공격자가 피해자 이메일로 미리 만들어 둔
  계정을 소셜 로그인으로 탈취할 수 있다(better-auth 계정 선점 사례). 조건 미충족 시
  `/login/link-confirm` 에서 이메일 코드로 소유 확인 후 연결한다.
- **principal 모델 통일**: oauth2Login 필터가 세션에 저장한 `OAuth2AuthenticationToken` 은
  성공 핸들러(`FederatedLoginSuccessHandler`)가 반드시 걷어낸다 — 게이트 분기는
  `startPending`(SecurityContext 제거 + 세션 pending), 완전 인증은 `completeAuthentication`(로컬
  `UserDetails` 기반 `UsernamePasswordAuthenticationToken`)으로 덮어쓴다. 기존 코드의 principal
  캐스팅(`authentication.name` = 이메일)이 소셜 로그인에도 그대로 성립한다.
- **소셜 전용 계정**: `users.password_hash` 는 NULL 허용. 폼 로그인 시 `LoginUserDetailsService` 가
  더미 bcrypt 해시를 대입해 항상 실패시키되 타이밍·메시지로 계정 유형을 노출하지 않는다.
  연결 해제는 잔여 로그인 수단(비밀번호/패스키/다른 소셜)이 1개 이상 남을 때만 허용한다(409).
- **부분 인증 불변식 유지**: 소셜 게이트(`SOCIAL_LINK`/`SOCIAL_EMAIL`)도 `PendingAuth` 세션 속성으로만
  존재한다. 공급자 신원은 `PendingSocialLink` 세션 속성에 보관하고, `SOCIAL_EMAIL` 처럼 로컬 계정이
  아직 없는 구간은 `PendingAuth.userId = null` 로 표현한다.
- **로그인된 세션의 "연결 추가"**: oauth2Login 필터가 성공 핸들러 호출 전에 SecurityContext 를
  교체하므로, 계정 페이지의 연결 시작 엔드포인트(`/account/federations/link/{provider}`)가
  `SocialLinkIntent` 세션 마커를 심어 연결 플로우임을 식별한다. 핸들러는 연결만 수행하고
  원래 사용자 principal 을 복원한 뒤 `/account?linked=1` 로 복귀시킨다.

공급자 콘솔 등록 절차와 환경변수는 [social-login-setup.md](social-login-setup.md) 참고.

### 8.6 신뢰 기기 (MFA 30일 스킵)

`trusted_devices` 테이블 + `taspa_td` 쿠키(HttpOnly, SameSite=Lax, prod 에서 Secure). 쿠키에는 256-bit
토큰 원본, DB 에는 SHA-256 해시만 저장한다. MFA 성공 화면의 "이 기기에서 30일 동안 묻지 않음"
체크 시에만 발급된다.

**정책 결정 근거:**

- **사용 시 회전(rotation)**: 검증 성공마다 새 토큰을 재발급하고 같은 행의 해시를 갱신한다.
  탈취된 쿠키가 재사용되면 정당한 기기와 토큰이 어긋나 게이트가 다시 닫힌다. 단 만료 시각은
  발급 시점 기준 **고정**(sliding 연장 금지, OWASP 권장) — 30일이 지나면 반드시 MFA 를 다시 통과해야 한다.
- **게이트 판정 수렴**: `LoginFlowSupport.requiredGate` 가
  `TrustedDeviceService.validateAndRotate` 를 호출하므로 폼·소셜·이메일 인증 후 재판정·매직 링크
  전부에 동일하게 적용된다. 패스키는 게이트 자체가 없으므로 무관하다.
- **무효화 트리거**: 비밀번호 재설정 성공, MFA 해제/재등록 시 해당 사용자의 신뢰 기기를 전부
  폐기한다. 계정 페이지 "신뢰하는 기기" 섹션에서 개별/전체 해제도 가능하다(해제는 step-up 대상).
- 신뢰 기기 쿠키는 **MFA 게이트만** 스킵한다 — step-up 재인증(§8.8)은 면제하지 않는다.

### 8.7 로그인 알림 (신규 기기 감지)

모든 완전 인증은 `login_events` 에 (ip, ua 요약 라벨, method) 로 기록된다 — 수렴점은
`LoginFlowSupport.completeAuthentication` + 비밀번호 무게이트 경로(`MfaAwareAuthenticationSuccessHandler`)
+ 패스키(`PasskeyAuthenticationSuccessHandler`). method 라벨: `password` / `mfa` / `passkey` /
`social:{provider}` / `magic`.

최근 30일 이력에 같은 (ip, ua 라벨)이 없고 유효한 신뢰 기기 쿠키로 검증된 요청도 아니면
"새 로그인이 감지되었습니다" 메일을 보낸다. 이벤트가 항상 먼저 기록되므로 같은 기기의 재로그인은
자연히 재발송(24시간 규칙 포함)이 억제된다. 알림 발송 실패는 로그인 자체를 실패시키지 않는다.
ua 라벨은 라이브러리 없이 대표 브라우저/OS 패턴만 요약한다("Chrome / macOS").

### 8.8 Step-up 재인증 (auth_time 패턴)

세션 속성 `TASPA_AUTH_TIME`(OIDC auth_time 개념)을 모든 완전 인증과 `/reauth` 성공이 갱신한다.
`taspa.step-up.max-age`(기본 10분)를 초과한 세션이 민감 작업에 접근하면:

- API(`/api/` 이하): 401 `{errorCode: "REAUTH_REQUIRED"}` → 페이지 JS 가 `/reauth?continue=...` 로 이동
- HTML: 원래 URL 세션 저장 후 `/reauth` 리다이렉트

적용 대상(`@RequireRecentAuth` + `RecentAuthInterceptor`): `/api/mfa/**` 전체,
`/api/passkeys/{id}` PATCH/DELETE, `DELETE /api/federations/{provider}`, 신뢰 기기 해제 API.
패스키 등록(`/webauthn/register/options`, `/webauthn/register`)은 필터 기반이라 애노테이션이 불가 →
`StepUpEnforcementFilter` 를 ExceptionTranslationFilter 직후(두 등록 필터보다 앞)에 배치해 강제하고,
계정 페이지 JS 는 등록 시작 전 `GET /api/reauth/check` 로 사전 점검한다.

`/reauth` 는 비밀번호(있으면) 또는 패스키(있으면 — 기존 `/webauthn/authenticate/options` +
`POST /login/webauthn` 재사용, 성공 핸들러가 auth_time 갱신)로 본인 확인 후 continue 경로로
복귀한다(로컬 경로만 허용 — open redirect 방지). 재인증 실패는 계정 잠금 카운터에 합산된다.
신뢰 기기 쿠키는 step-up 을 면제하지 않는다.

### 8.9 매직 링크 (이메일 로그인)

password 페이지의 "이메일로 로그인 링크 받기" → `magic_link_tokens`(SHA-256 해시, 15분 만료,
단일 사용 `used_at`) 발급 → 메일 링크 `{base-url}/login/magic?token=...`.

- **GET 랜딩은 토큰을 소비하지 않는다** — 확인 페이지("taspa에 로그인하시겠습니까?")만 렌더하고,
  "로그인" 버튼(POST)이 소비·승격한다. 이메일 보안 스캐너의 선클릭으로 토큰이 타는 것을 방지한다.
- 로그인은 **링크를 클릭한 브라우저에서** 성립한다(요청한 브라우저 자동 로그인 금지).
- 링크 클릭 = 이메일 소유 증명이므로 미검증 계정은 검증 처리되지만, **MFA 게이트는 유지**된다
  (`requiredGate` 공용 판정 — 매직 링크는 1차 인증일 뿐).
- 요청 응답은 계정 존재 여부와 무관하게 동일하다(열거 공격 방지). 60초 재발급 제한.

### 8.10 리스크 기반 인증 (Adaptive Auth)

`risk/RiskEvaluationService` 가 **비밀번호 로그인 성공 직후** 신호를 평가해
`LoginFlowSupport.requiredGate(user, risk)` 판정에 반영한다. 별도 테이블 없이
`login_events` 와 `users` 필드를 재사용한다.

**적용 범위 — 비밀번호 경로만.** 다른 수단이 면제되는 근거:

| 수단 | 면제 근거 |
|------|-----------|
| 패스키 | 피싱 내성 — origin 바인딩 공개키 서명이라 훔친 자격 증명으로 원격 재현 불가 |
| 소셜 | 공급자(구글·카카오·네이버)가 자체 리스크 엔진으로 1차 인증을 보증 |
| 매직 링크 | 이메일 소유 증명 자체가 챌린지(이메일 코드)와 동일한 요소 |

**신호** (`taspa.risk.unseen-window-days` 기본 90):

| 신호 | 정의 |
|------|------|
| `unseenDevice` | 최근 90일 `login_events` 에 같은 (ip, ua 라벨) 없음 && 유효한 신뢰 기기 쿠키 없음 |
| `recentFailures` | 이 로그인 직전 `failedLoginAttempts` ≥ 3 (잠금 상한 5 전에 발동). 잠금 만료 자동 해제는 카운터를 보존하므로 잠금까지 간 실패 이력도 잡힌다 |
| `rapidIpChange` | 직전 성공 로그인이 30분 이내 && 다른 IP |

**IP 신뢰 전제**: 신호의 IP 는 `RequestClientInfo.ip` = **`remoteAddr` 만** 쓴다 —
`X-Forwarded-For` 는 클라이언트가 임의 조작 가능해 이를 신뢰하면 피해자의 과거 IP 를 헤더에
실어 `unseenDevice`/`rapidIpChange` 를 스푸핑으로 무력화할 수 있다. 리버스 프록시 뒤 배포는
`server.forward-headers-strategy=native`(+`server.tomcat.remoteip.internal-proxies` 로 신뢰
프록시 지정)를 설정해 `remoteAddr` 자체가 실제 클라이언트 IP 가 되게 한다.

**판정표**:

| 레벨 | 조건 | 조치 |
|------|------|------|
| HIGH | unseenDevice && (recentFailures \|\| rapidIpChange) | MEDIUM 조치 + 보안 경고 메일("차단하려면 비밀번호를 변경하세요", 사용자별 15분 쿨다운 — `RISK_ALERT_MAILED` 감사 기반) |
| MEDIUM | HIGH 미해당인 unseenDevice 또는 recentFailures (rapidIpChange 동반 여부 무관) | MFA 사용자: 신뢰 기기 스킵 무시하고 MFA 강제. MFA 미등록: 이메일 코드 챌린지(`RISK_CHALLENGE` 게이트, `/login/risk-challenge`) |
| LOW | 그 외 — rapidIpChange 단독 포함(오탐이 잦은 보조 신호라 HIGH 가중으로만 쓴다) | 기존 동작 그대로 |

핵심 불변식 2가지:

1. **평가 시점**: `MfaAwareAuthenticationSuccessHandler` 에서 리스크 평가가
   `AccountLockoutService.recordSuccessfulLogin`(실패 카운터 리셋) **보다 먼저** 실행된다 —
   `recentFailures` 는 리셋 전 값을 읽어야 한다.
2. **EMAIL_VERIFICATION 과 중복 금지**: 미인증 계정은 이메일 인증 게이트가 최우선이고 그 게이트
   자체가 이메일 소유를 증명하므로, 같은 요소(이메일 코드)인 RISK_CHALLENGE 는 발동하지 않는다
   (`requiredGate` 의 when 판정 순서로 보장).

RISK_CHALLENGE 도 다른 게이트와 같은 pending 규칙을 따른다 — SecurityContext 밖(세션 속성)이므로
챌린지 통과 전에는 `/oauth2/authorize` 가 code 를 발급하지 않는다(§7.2, 회귀 테스트
`risk/RiskBasedAuthIntegrationTest`). 코드 발급·검증은 `EmailVerificationService` 재사용 —
단 게이트 진입 발급은 `resend()`(60초 스로틀)를 타 반복 로그인의 메일 폭주·병행 세션 코드
무효화를 막고, 통과 검증은 `verifyRiskChallenge()`(emailVerified 재기록 없음, 감사
`RISK_CHALLENGE_PASSED`)를 쓴다. 코드는 **불일치 5회에 소진(무효화)** 되어(전 게이트 공통,
V11 `failed_attempts`) 6자리 코드 브루트포스가 재발송 스로틀과 함께 시도율 상한에 묶인다.
`taspa.risk.enabled=false` 면 평가를 건너뛰어 기존 동작 그대로다. MEDIUM 이상은 감사
이벤트 `RISK_DETECTED`(level·신호별 값)로 기록되어 관리 콘솔 감사 로그에서 조회된다.

### 8.11 기업 SSO (SAML 2.0 · 조직 OIDC)

taspa 가 **SP/RP** 로서 회사 외부 IdP 로 사용자를 인증한다 — 소셜 로그인의 기업 버전. 관리 콘솔
`/admin/sso` 에서 커넥션(조직 + 프로토콜)을 등록한다. 설정 절차는 [enterprise-sso-setup.md](enterprise-sso-setup.md).

**경로 네임스페이스(충돌 없음)**:
- 조직 OIDC 는 소셜과 공유 — `/oauth2/authorization/{regId}`(진입), `/login/oauth2/code/{regId}`(콜백).
- SAML — `/saml2/authenticate/{regId}`(진입), `/login/saml2/sso/{regId}`(ACS), `/saml2/service-provider-metadata/{regId}`(SP 메타데이터).
- AS 엔드포인트(`/oauth2/authorize` 등, `@Order(1)` 체인)와 겹치지 않는다.

**HRD(Home Realm Discovery)**: `LoginFlowController.submitIdentifier` 가 이메일 정규화 직후 도메인을
`SsoConnectionService.findEnabledConnectionByDomain`(enabled + **verified** 도메인)으로 조회한다. 매칭 +
`enforced` 면 로컬 비밀번호/패스키보다 **먼저** IdP 로 단락 리다이렉트한다(정책 1). 미매칭이면 기존 로컬 흐름.

**도메인 일치 강제(정책 5, 보안 핵심)**: 조직 로그인 성공 시 **공급자 이메일 도메인 == 커넥션 verified 도메인**을
강제한다(불일치 시 실패, `SSO_DOMAIN_REJECTED`). 조직 IdP(또는 침해된 IdP)가 타 도메인 이메일을 주장해
계정을 탈취하는 것을 차단한다. OIDC 는 `FederatedLoginSuccessHandler`(attributes 추출 직후), SAML 은
`Saml2FederatedLoginSuccessHandler` 가 강제한다. verified 도메인이 없는 커넥션으로는 로그인 불가(안전 기본값).

**완전 인증 승격(미러링)**: 소셜과 동일하게, SSO 필터가 세션에 심은 `OAuth2AuthenticationToken`/
`Saml2Authentication` 은 세션에 남기지 않는다. 성공 핸들러가 기존 연결이면 로그인, 없으면 JIT 프로비저닝
(비밀번호 없는 계정 + 연결, provider=`saml:{regId}`/`oidc:{regId}`) 후 `LoginFlowSupport.completeAuthentication`
으로 로컬 `UsernamePasswordAuthenticationToken` 완전 인증으로 덮어쓴다. 게이트 불변식(§7.2)은 그대로 —
pending 은 SecurityContext 밖.

**MFA 정책(정책 2)**: 기본은 로컬 MFA 게이트 **유지**(외부 IdP MFA 불신, 소셜과 일관). 커넥션에
`trust_idp_mfa=true` 면 조직 로그인 후 로컬 MFA 게이트를 건너뛴다.

**레지스트리 배선**:
- OIDC: `CompositeClientRegistrationRepository` = 정적 소셜 3종 + DB 조직 OIDC. `findByRegistrationId` 가
  소셜 우선, 없으면 DB(짧은 캐시로 변환)로 폴백해 관리자가 추가한 조직 OIDC 를 재기동 없이 동작시킨다.
  `iterator` 는 소셜만 노출(로그인 버튼은 소셜 한정, 조직 OIDC 는 HRD 진입). 빈 노출 조건은 기존과 동일하게
  "소셜 하나라도 설정됨"이다.
- SAML: `DbRelyingPartyRegistrationRepository`(`IterableRelyingPartyRegistrationRepository`)가
  sso_connections(SAML)→`RelyingPartyRegistration`(SP entityId/ACS 템플릿, asserting party: IdP entityId/
  SSO URL/서명 검증 x509)로 변환한다. `saml2Login` 은 상시 등록되지만 SAML 커넥션 0건이면 어떤 조회도
  null 이라 사실상 비활성 — 관리자가 첫 SAML 커넥션을 추가하면 재기동 없이 동작한다.

**CSRF/permitAll**: ACS(`/login/saml2/sso/**`)는 IdP 의 cross-site form POST 라 CSRF 면제 목록에 있고,
진위는 응답 서명 검증(`OpenSaml4AuthenticationProvider`)이 보장한다. SAML 진입·ACS·SP 메타데이터는 permitAll.

### 8.12 자기서비스 계정 관리 · 연결된 앱 (Stage 2·3)

계정 페이지(`/account`)에서 사용자가 스스로 프로필·이메일·비밀번호·탈퇴·연결앱을 관리한다.
API 는 `selfservice/` 모듈의 `SelfServiceController`(`/api/account/**`). 파괴적/민감 작업은 전부
`@RequireRecentAuth`(step-up §8.8)로 보호하고 각 이벤트를 감사(`audit_events`)에 남긴다.

| 기능 | 엔드포인트 | step-up | 서비스 · 핵심 동작 |
|------|-----------|:------:|------|
| 프로필(표시 이름) | `PATCH /profile` | — | `ProfileService` — 비파괴라 step-up 제외 |
| 이메일 변경(요청) | `POST /email/change` | ✓ | `EmailChangeService.requestChange` — 새 주소로 인증코드 발송, 대상은 세션(`PendingEmailChange`)에 보관, 계정 이메일은 아직 불변 |
| 이메일 변경(확인) | `POST /email/change/confirm` | —¹ | `confirmChange` — 코드 소진 성공 시에만 전환 |
| 비밀번호 변경/설정 | `POST /password` | ✓ | `PasswordChangeService` — 보유 계정은 현재 비번 확인, 소셜 전용(`password_hash` NULL)은 최초 설정 허용. 성공 시 **전 세션·전 신뢰기기 폐기** |
| 계정 탈퇴 | `DELETE /` | ✓ | `AccountDeletionService` — 이메일 재입력 확인 후 하드삭제 |
| 연결된 앱 목록 | `GET /authorized-clients` | — | `ConnectedAppService.list` — `principal_name`(=이메일)으로 조회, `client_name` 복원 |
| 연결된 앱 철회 | `DELETE /authorized-clients/{id}` | ✓ | `revoke` — authorization·consent 행 삭제 → 활성 토큰 무효화(`invalid_grant`) |

¹ 확인 단계의 보안 통제는 **새 주소로 발송된 코드 소유**다. 초기화(요청)가 이미 step-up 을 통과했고,
코드 TTL 과 step-up max-age 가 겹쳐 재인증을 다시 강제하면 데드락 위험이 있어 확인엔 step-up 을 걸지 않는다.

**이메일 변경 시 제3자 권한부여 폐기(보안 핵심)**: `oauth2_authorization`/`oauth2_authorization_consent` 는
users FK 없이 `principal_name`(=이메일)으로만 키잉된다(§4, SAS 표준 스키마). 이메일을 바꾸면서 이 행들을
그대로 두면 옛 이메일에 **고아 grant** 로 남아 ① 연결앱 목록/철회 불가(조회가 새 이메일 기준), ② 이후 탈퇴
시에도 옛 이메일 행이 삭제되지 않아 PII·활성 refresh_token 잔존, ③ 옛 이메일 재가입 시 교차계정 토큰 발급
위험이 생긴다. `principal_name` 만 새 이메일로 옮겨도 refresh 그랜트는 저장된 principal(`attributes` 직렬화)의
옛 이메일을 복원해 `TokenCustomizerConfig` 의 `findByEmail(옛 이메일)=null` 로 sub 이 회귀한다. 그래서
`confirmChange` 는 이메일 스왑과 **같은 트랜잭션**에서 해당 사용자의 authorization·consent 를 **폐기**해
재동의를 요구한다(회귀 테스트: `AuthorizedClientIntegrationTest`, `SelfServiceAccountIntegrationTest`).

**하드삭제(soft delete 대신) 채택 근거**: 소프트삭제(status 플래그)는 PII(이메일 등)를 계속 보존해 최소
수집·삭제권(GDPR) 요구와 어긋난다. `AccountDeletionService` 는 탈퇴 즉시 (1) `oauth2_authorization`/consent
(principal_name=이메일 키, FK 없음 → 명시 삭제), (2) `users` 행 물리 삭제 → FK `ON DELETE CASCADE` 로
backup_codes/email_verification_codes/password_reset_tokens/webauthn·federated_identities/trusted_devices/
login_events/magic_link_tokens 동반 제거, (3) 세션 저장소(FK 없음) 커밋 후 폐기, (4) 선행 `audit_events` 의
detail 을 익명화 마커로 덮어써 평문 이메일 제거(user_id·type·created_at 만 잔존)를 수행한다.
`ACCOUNT_DELETED` 감사는 이메일 **SHA-256 해시**만 담아 재식별을 막는다. 결과: 하드삭제 후 남는 감사는
전부 재식별 불가능하고, 동일 이메일 재가입이 가능하다(회귀 테스트: `SelfServiceAccountIntegrationTest`).

## 9. 관리자 콘솔 (권한 모델)

### 9.1 역할 모델과 수명주기

`users.role`(V10) — `USER | ADMIN`. `LoginUserDetailsService` 가 모든 사용자에게 ROLE_USER 를,
ADMIN 에게 ROLE_ADMIN 을 추가로 부여한다(ADMIN ⊃ USER — 관리자도 일반 화면을 그대로 쓴다).
`/admin/**`·`/api/admin/**` 는 `hasRole("ADMIN")` 로 보호된다(일반 사용자는 403).

권한은 **로그인 시점의 세션에 굳는다**는 점이 운영 규칙을 결정한다:

- 승격(USER→ADMIN): 다음 로그인부터 유효 — 콘솔 UI 도 이를 안내한다.
- 강등(ADMIN→USER): 세션에 남은 ROLE_ADMIN 이 세션 수명만큼 살아남으면 권한 회수가 지연되므로,
  `AdminUserService.changeRole` 이 대상의 **전 세션을 즉시 폐기**한다.
- 정지(SUSPENDED): 전 세션 + 신뢰 기기 즉시 폐기. 이후 로그인은 기존 SUSPENDED 차단 경로
  (`UserDetails.disabled` / `completeAuthentication` 상태 백스톱)가 막는다.

**자기 보호 가드**: 자신을 정지하거나 자신의 ADMIN 을 해제하는 요청은 409(`ADMIN_SELF_ACTION`) —
마지막 관리자가 스스로를 잠그는 사고를 막는다. 첫 관리자는 `taspa.admin.emails` 부트스트랩
(존재하는 계정만 승격, 감사 기록) 또는 SQL(`UPDATE users SET role='ADMIN' ...`)로 지정한다.

### 9.2 admin CSRF (면제 제외)

`/api/**` CSRF 면제에서 `/api/admin/**` 는 `/api/sessions/**` 와 같은 이유로 제외한다 —
관리자 변경 작업(계정 정지, 클라이언트 삭제 등)은 파괴적이라 SameSite=Lax 단일 계층에 맡기지
않는다. 관리 페이지 JS 는 meta 태그의 토큰을 `X-CSRF-TOKEN` 헤더로 전송한다(계정 페이지 패턴).
모든 변경 API 는 추가로 `@RequireRecentAuth`(step-up) 대상이며 `ADMIN_*` 감사 이벤트를 남긴다.

### 9.3 클라이언트 관리

`JdbcRegisteredClientRepository` 에는 findAll 이 없어 목록은 `SELECT id` 후 `findById` 로 복원한다
(SAS 역직렬화 재사용). 등록 정책: PKCE·동의 화면 항상 강제, access 15분/refresh 30일(reuse=false).

- **기밀 클라이언트**: 32바이트 랜덤 secret 을 응답에서 1회만 노출하고 `{bcrypt}`+bcrypt 로 저장한다.
  접두사가 필요한 이유: SAS 토큰 엔드포인트의 `ClientSecretAuthenticationProvider` 기본 인코더는
  `DelegatingPasswordEncoder` 라 접두사 없는 해시는 매칭이 아예 실패한다.
- **공개 클라이언트**(SPA·모바일): `token_endpoint_auth_method=none`, secret 없음, PKCE 가 유일한
  방어층. client_credentials grant 는 인증 수단이 없어 등록 시 제거된다.
- 삭제 시 `oauth2_authorization`·`oauth2_authorization_consent` 행을 함께 지운다(고아 행 방지).
- 수정은 client_name/redirect URI/post-logout URI/scope 만 — client_id·유형·grant 는 불변.

### 9.4 Single Logout (RP-Initiated Logout)

taspa 는 OIDC **RP-Initiated Logout** 을 지원한다 — `.oidc(Customizer.withDefaults())` 로 활성화된
`end_session_endpoint`(`/connect/logout`)가 디스커버리에 노출된다(`SecurityConfig` §1 AS 체인). RP 가
`id_token_hint` + `post_logout_redirect_uri` 로 요청하면 OP 의 SSO 세션까지 종료한 뒤 등록된 복귀 URI
(정확 일치 검증)로 리다이렉트한다. 클라이언트 등록의 `post_logout_redirect_uris`(관리 콘솔 폼·
`AdminClientService`)가 허용 목록이며, demo-app 은 dev 시더가 `http://localhost:8080/` 를 등록한다.
`examples/demo-client` 는 `OidcClientInitiatedLogoutSuccessHandler` 로 이를 배선하고 `/me` 에 **로컬
로그아웃**과 **SLO** 두 버튼을 제공한다(왕복 시나리오는 e2e `sso-flow.spec.ts` 로 고정).

**한계 — OP-Initiated Back-Channel Logout 미지원**: SAS 1.4.2 에는 OP 가 각 RP 로 `logout_token`(JWT)을
push 하는 Back-Channel Logout **송신부가 없다**(로컬 jar 실측 — `backchannel_logout_supported`/
`backchannel_logout_uri` 메타데이터·`logout_token` 생성 로직 전무). 따라서 "한 곳에서 로그아웃 →
참여 RP 전부 자동 무효화"인 완전한 SLO 는 기성으로 제공되지 않으며 커스텀 구현(클라이언트 메타데이터
`backchannel_logout_uri` 저장 · OP 세션↔RP 매핑 레지스트리 · 서명 `logout_token` POST · 디스커버리
광고)이 필요하다. RP 수신부(`http.oidcLogout().backChannel()`)는 Spring Security 6.4 가 기성 지원하나
OP 송신부가 없으면 실효가 없어 배선하지 않았다. 현재 범위는 **RP-Initiated Logout** 으로, SSO 세션
종료 + 클라이언트 복귀는 충족한다(docs/integration-guide.md "로그아웃" 절).

## 10. Phase 0 — 조직 테넌시 · scope 설정화 · org 클레임 · iCalendar 연동

결제·예측 두 후속 설계가 공유하는 공통 기반이다(제품·규제 결정 불필요 범위만). 마이그레이션 헤드는
V14 → **V15(조직 테넌시)·V16(캘린더)** 로 전진했다. 소비 클라이언트(예측·결제 서비스)가 참조할 계약을
아래에 고정한다.

### 10.1 조직 테넌시 (`organizations` · `org_memberships`)

- **V15** — `organizations(id, slug, name, status[ACTIVE|SUSPENDED], …)`, `org_memberships(id, org_id,
  user_id, role[MEMBER|ORG_ADMIN], department, status, …, UNIQUE(org_id, user_id))`. `sso_connections`
  에 `org_id`(nullable) 추가. 도메인은 `domain/org/`, 서비스는 `org/OrganizationService`(slug 정규화·
  유니크, 멤버십 upsert/역할변경/제거, **마지막 ORG_ADMIN 자기보호**).
- **JIT 멤버십**: 조직 IdP 로그인 성공 시 사용한 `sso_connection.org_id` 가 있으면 `(user, org, MEMBER)`
  를 upsert 한다(`org_id` 가 있을 때만 — 잘못된 조직 자동가입 금지).
- **SUSPENDED 강제(불변식)**: 조직 status 는 인가에 실효적으로 반영된다.
  `OrganizationService.isActiveMember`/`isOrgAdmin` 은 **조직이 ACTIVE 일 때만** true 를 반환하고,
  `TokenCustomizer` 는 SUSPENDED 조직의 멤버십에 대해 org 클레임을 발급하지 않으며,
  `CalendarService.listEvents` 는 SUSPENDED 조직 조회를 403 으로 차단한다. (관리자 피드 관리 경로는
  존재 검사만 하므로 정지 조직도 관리·복구 가능.)

### 10.2 Scope 화이트리스트 설정화 (`taspa.oauth.allowed-scopes`)

- 관리 콘솔에서 클라이언트에 부여 가능한 scope 를 하드코딩에서 `@ConfigurationProperties("taspa.oauth")`
  (`OAuthProperties.allowedScopes`)로 이관했다(`AdminClientService.resolveScopes` 가 참조). 기본값은
  OIDC 표준 3개 + 플랫폼 scope(`org.read`, `meal.*`, `merchant.*`, `settlement.*`, `calendar.read`,
  `calendar.read.all`). **미설정 시 표준 3개로 폴백**(안전 기본값) — 설정 실수로 전 scope 등록 불가가
  되는 상황을 막는다.

### 10.3 org 클레임 계약 (`org.read` scope)

- **발급 조건**: `authorizedScopes` 에 `org.read` 가 포함될 때만(최소권한). 활성 멤버십이 없거나 조직이
  SUSPENDED 면 미발급. `client_credentials`(사용자 없음)는 org 클레임을 싣지 않는다.
- **형태**: 단일 활성 멤버십이면 `org_id`(UUID) + `org_role`; 복수면 대표 `org_id`(첫 활성) + `orgs:
  [{id, role}]` 배열. org 정보는 PII 가 아니므로 **access_token·id_token 양쪽**에 싣는다(리소스 서버가
  인가에 사용).
- **sub=UUID 하위호환**: 발급 토큰 `sub` 은 이메일이 아니라 `users.id`(UUID)로 고정된다(§3.2). org 클레임
  소비 시 사용자 식별은 `sub`(UUID)을 기준으로 한다 — 리소스 서버는 `sub` 이 UUID 면 사용자 토큰,
  아니면 서비스(client_credentials) 토큰으로 구분할 수 있다.

### 10.4 조직 API 리소스 서버 체인 (`@Order(2)`)

- `SecurityConfig.orgApiSecurityFilterChain` 이 `/api/orgs/**` 전용으로 배치된다(@Order: AS 체인 §1 <
  **org API 체인** < default 체인). 베어러 JWT(BearerTokenAuthenticationFilter)와 로그인 세션을 함께
  수용한다. `JwtDecoder` 는 AS 와 동일한 `JWKSource`(RS256)로 서명을 검증하고 issuer 를
  `taspa.issuer-uri` 로 강제한다(자체 발급 토큰만 수용).

### 10.5 iCalendar 연동 (RFC 5545, `calendar/`)

- **V16** — `calendar_feeds`(구독형 `source_url` / 업로드형), `calendar_events`(정규화 이벤트,
  `UNIQUE(feed_id, uid, starts_at)`, `idx_calendar_events_org_time`). 의존성 `net.sf.biweekly:biweekly`.
- **파싱·정규화**: `IcalendarParser` 가 VEVENT(UID·SUMMARY·DTSTART·DTEND·CATEGORIES·RRULE)를 읽는다.
  RRULE 반복은 조회 윈도우(`now-1d`~`now+expansionWindowDays`, 기본 400일) + 개수 상한
  (`maxOccurrencesPerEvent`, 기본 1000)으로만 확장한다. **타임존 불변식**: DATE(all-day)·floating(TZID·Z
  없음) 값은 biweekly 가 JVM 기본 TZ 로 파싱하므로, 벽시계 성분을 **UTC 로 재해석**해 서버 TZ 와 무관하게
  고정한다(단일·반복 경로 동일). 명시 UTC(Z)·TZID 값은 절대 instant 라 그대로 둔다.
- **동기화·고아 정리**: `CalendarEventWriter.upsertFeedEvents` 가 **feed 단위 mark-and-sweep** 으로
  업서트한다 — `(uid, starts_at)` 키로 갱신/삽입하고, **이번 실행에서 보지 못한** feed 소속 행(소스 삭제·
  DTSTART 변경·RRULE 변경으로 사라진 occurrence)을 삭제해 고아·중복 누적을 막는다.
- **트랜잭션 경계**: 느린 외부 fetch·파싱은 트랜잭션 **밖**(`CalendarService.doSync`)에서 수행하고, DB
  업서트/상태갱신만 `CalendarEventWriter` 의 짧은 **독립 트랜잭션**으로 위임한다 — fetch 동안 DB 커넥션을
  점유하지 않고(풀 고갈 방지), 업서트 경합(UNIQUE 위반)은 자기완결 트랜잭션에서 깨끗이 롤백된 뒤 상위에서
  ERROR 상태를 별도 트랜잭션으로 기록한다(false OK·rollback-only 오염 없음, 한 피드 실패가 다른 피드
  동기화를 막지 않음).
- **★SSRF 방어(`IcsUrlSecurity`)**: `source_url` 은 **https 만** 허용, 호스트 해석 IP 가 루프백·사설·
  링크로컬·유니크로컬·CGNAT·멀티캐스트·클라우드 메타데이터(169.254.169.254)면 거부. `IcsSubscriptionFetcher`
  는 자동 리다이렉트를 끄고 **매 홉 재검증**하며, 타임아웃·응답 크기 상한(기본 5s·5MB)을 강제한다. 피드
  등록은 ADMIN(`/api/admin/orgs/{orgId}/calendar` — `@RequireRecentAuth` + CSRF)만 가능하다.

### 10.6 캘린더 조회 API 와 테넌시 (`GET /api/orgs/{orgId}/calendar/events`)

- **파라미터**: `from`·`to`(ISO-8601 Instant, 기본 `[now, now+30d]`), `page`·`size`. 윈도우 폭은
  `expansionWindowDays` 로 상한, 행 수는 `size`(기본 `defaultPageSize`, 최대 `maxPageSize`)로 페이징 상한.
  응답은 `{items, page, size, total, hasNext}` 로 페이지 메타를 노출한다(무제한 리스트·메모리 폭증 방지).
- **인가(스펙 E '본인 org 만')**: 세 호출자를 구분한다.
  1. **세션 사용자** — 전역 ADMIN 또는 해당 org 활성 멤버(org 격리).
  2. **사용자 베어러 JWT**(`sub`=UUID→실사용자) — 그 사용자의 활성 멤버십으로 org 를 강제한다.
     `calendar.read` 만으로 임의 org 를 읽던 테넌시 공백을 닫는다.
  3. **서비스 베어러 JWT**(client_credentials) — `calendar.read.all`(신뢰 플랫폼 전조회)이거나, 토큰의
     org 결속 클레임(`org_id`/`orgs`)이 경로 orgId 를 포함해야 한다. **결속 없는 `calendar.read` M2M 은
     거부**(fail-closed). 따라서 예측 서비스는 전조회용 `calendar.read.all` 을 명시적으로 부여받거나
     org 별 결속 토큰을 사용해야 한다 — 일반 발급형 `calendar.read` 로는 임의 org 를 읽을 수 없다.

## 11. 조직 자율 콘솔 · 초대 · 소비 이벤트 seam

Phase 0 공통 기반(§10) 위에, 개인이 조직에 합류하는 **온보딩(초대)** 과 ORG_ADMIN 이 플랫폼 관리자 없이
자기 조직을 운영하는 **자율 콘솔**, 그리고 예측의 정답데이터가 될 **소비 이벤트 수집 seam**(Phase 0ب)을
얹었다. 마이그레이션 헤드는 V16 → **V17(소비)·V18(org 타임존)·V19(초대)·V20(audit org_id)** 로 전진했다.

### 11.1 조직 초대 (`org/OrgInvitationService` · `org_invitations`)

- **V19** — `org_invitations(id, org_id, email, role[MEMBER|ORG_ADMIN], department, token_hash UNIQUE,
  status[PENDING|ACCEPTED|REVOKED|EXPIRED], invited_by, created_at, expires_at, accepted_at, accepted_by)`.
  **토큰은 해시만 저장**(`SecureTokenGenerator` 256bit 원문 → SHA-256 hex, 원문은 초대 메일 링크로만 노출),
  단일 사용(수락 시 ACCEPTED), 만료(`expiryDays` 기본 7d). `(org_id, email)` 은 **PENDING 상태에서만 1건**
  (부분 유니크 인덱스 `uq_org_invitation_pending` — 중복 초대 폭주 방지, 재초대는 기존 PENDING 갱신·재사용).
- **오퍼레이션**: `invite`(생성/재사용) · `accept`(수락) · `revoke`(취소) · `resend`(재발송) · `listPending`.
  설정은 `@ConfigurationProperties("taspa.org-invitation")`(`OrgInvitationProperties`).
- **★이메일 일치 강제(하이재킹 차단)**: `accept` 는 `currentUser.email`(소문자) == `invitation.email` 이 아니면
  `INVITATION_EMAIL_MISMATCH` 로 거부하고, 이메일 미검증 세션도 거부한다(수락 페이지 표시 + 서비스 재확인 이중화).
- **계정 열거 저항**: 초대 생성은 대상 이메일의 taspa 계정 존재와 **무관하게** 동일하게 초대를 만들고 메일을
  보낸다(수신자가 이후 가입 후 수락). org-로컬 정보인 "이미 이 조직의 활성 멤버"만 거부한다(전역 계정 존재 비노출).
- **남용 방지 2계층**: ① org·시간당 신규 초대 상한(`maxPerHour` 기본 20 — 행 무한 증식 차단) ② 동일 `(org,email)`
  재발송 쿨다운(`resendCooldownSeconds` 기본 60s — 재초대는 PENDING 1건을 재사용하므로 행 카운트만으론 막지
  못하는 단일 주소 이메일 폭탄을 `createdAt`(마지막 발송 시각) 기준으로 상한).
- **동시성**: `accept`/`resend` 는 초대 행을 비관적 쓰기 잠금으로 조회한다(`findByTokenHashForUpdate` /
  `findByIdAndOrgIdForUpdate`) — 동시 수락(더블클릭)·재발송↔수락 lost-update 를 직렬화해 단일 사용 불변식과
  `org_memberships` UNIQUE 충돌(→500)을 막는다. 만료 초대의 EXPIRED **전이는 REQUIRES_NEW 별도 트랜잭션**에서
  확정한다 — `accept` 가 만료 거부 예외로 롤백돼도 전이는 남는다(만료 초대가 PENDING 으로 잔존하지 않게).
- **메일은 커밋 이후(afterCommit) 발송**: SMTP I/O 동안 DB 커넥션을 점유하지 않고(풀 고갈 방지), 롤백된
  초대(경합·커밋 오류)에 대해 '죽은 링크' 메일이 나가지 않는다(영속 커밋된 초대만 발송).
- **컨트롤러 분리**: 초대 **관리**(`OrgInvitationController`, `/api/orgs/{orgId}/invitations`)는 **세션 전용**
  (베어러 거부 — 아래 11.2 인가와 동일). 초대 **수락**(`OrgInvitationAcceptController`, `/orgs/invite/accept`)은
  기본 체인 `authenticated()` 로 보호되며 **GET 은 소비 없는 미리보기**(사용자 이메일 대조로 표시 상태 계산),
  **POST 가 소비**한다(하이재킹 이중 차단·상태 재렌더).

### 11.2 ORG_ADMIN 자율 콘솔 (`/console/orgs`)

- **인가 패턴(공용)**: 콘솔 API(`org/OrgMemberController`·`OrgProfileController`·`OrgAuditController`·
  `OrgConsoleController`)의 `authorize` 는 동일하다 — **위임 베어러(`JwtAuthenticationToken`) 거부** 후 세션의
  **플랫폼 ADMIN(`users.role=ADMIN`) ∨ 해당 org 활성 `isOrgAdmin`** 만 통과시키고, 그 외(타 org ORG_ADMIN·
  일반 멤버)는 403 이다. 베어러 거부 근거: 전용 리소스 서버 체인은 동의 scope 와 무관하게 유효 서명 access_token
  을 인증시키므로, under-consented 토큰 재사용에 의한 confused-deputy(대기 초대의 대상 이메일·역할 PII 열람)를
  닫는다(초대 관리에 전용 위임 scope·3rd-party 유스케이스가 없어 fail-closed).

| 메서드·경로 | 동작 | step-up |
| --- | --- | --- |
| `GET /api/orgs/mine` | 로그인 사용자가 ORG_ADMIN 인 **활성** 조직 목록(정지 org·멤버십 제외, 멤버 수 포함) | — |
| `GET /api/orgs/{orgId}/members` | 멤버 목록 | — |
| `PUT /api/orgs/{orgId}/members/{userId}/role` | 역할 변경 | `@RequireRecentAuth` |
| `DELETE /api/orgs/{orgId}/members/{userId}` | 멤버 제거 | `@RequireRecentAuth` |
| `POST /api/orgs/{orgId}/invitations` | 초대 생성 | `@RequireRecentAuth` |
| `GET /api/orgs/{orgId}/invitations` | PENDING 초대 목록(만료분 lazy 전이) | — |
| `POST /api/orgs/{orgId}/invitations/{id}/resend` | 재발송 | `@RequireRecentAuth` |
| `DELETE /api/orgs/{orgId}/invitations/{id}` | 취소 | `@RequireRecentAuth` |
| `GET /api/orgs/{orgId}/audit` | 조직 스코프 활동로그(읽기 전용) | — |
| `PUT /api/orgs/{orgId}` | 조직 프로필(name·timezone) 편집 | `@RequireRecentAuth` |

- **불변식 ① 마지막 관리자 잠금 방지**: 역할 강등·제거·초대 강등은 `OrganizationService.guardLastAdmin` 을
  거친다. `countEffectiveAdmins` 는 **로그인 가능한 유효 관리자**(멤버십 `ACTIVE` **AND** `users.status=ACTIVE`)만
  카운트한다 — 정지된 공동관리자가 있어도 실효 관리자가 1명이면 마지막으로 취급해 조직 락아웃을 막는다.
  `lockByOrgIdAndRoleForUpdate` 로 ORG_ADMIN 멤버십 행을 먼저 `PESSIMISTIC_WRITE` 잠가 count-check-write 를
  직렬화한다(동시 강등/제거로 0명이 되는 write-skew·TOCTOU 차단).
- **불변식 ② 프로필 편집은 name·timezone 만**(status·slug **불변**): 정지 해제·slug 탈취는 플랫폼 관리자
  전용(`update`)이다. 이중 차단 — `OrgProfileRequest` DTO 에 status·slug **필드 자체가 없어** 본문에 실어도
  역직렬화에서 무시되고, `OrganizationService.updateProfile` 도 name·timezone 만 반영한다. `updateProfile` 은
  SUSPENDED 조직 편집을 거부한다(정지 실효성).
- **진입 페이지**(`OrgConsolePageController`, `/console/orgs`)는 인증만으로 접근 가능하고, 데이터는 페이지 JS 가
  위 API 로 채운다 — ORG_ADMIN 이 아니면 `/api/orgs/mine` 이 빈 목록을 반환해 "관리 권한 없음" 빈 상태를 보인다.

### 11.3 `/api/orgs` 세션 체인 CSRF (`@Order(2)` `orgApiSecurityFilterChain`)

- 이 체인은 GET 조회뿐 아니라 **세션 인증 상태변경**(멤버 역할/제거·초대 생성/재발송/취소·프로필 편집)도
  호스팅하므로, 기본 체인의 `/api/sessions`·`/api/admin` 과 같은 2계층 표준대로 **CSRF 를 강제**한다(SameSite=Lax
  단일 계층 의존 금지). 콘솔 JS 는 meta 토큰을 `X-CSRF-TOKEN` 헤더로 실어 보낸다.
- **유일한 면제는 M2M 전용 쓰기** `POST /api/orgs/*/consumption-events`(`ignoringRequestMatchers`) — Authorization
  헤더 기반이라 ambient 쿠키가 없어 CSRF 위조가 성립하지 않고, 프로그램적 생산자는 CSRF 토큰을 가질 수 없다.
  GET 조회는 `CsrfFilter` 기본 제외라 영향 없다.
- **테스트 주의**: 미인증 상태변경 요청은 `CsrfFilter` 가 인증 계층보다 **먼저 403** 으로 막는다 — 인증/인가
  격리를 검증하려면 유효 CSRF 토큰을 동반해 필터를 통과시켜야 한다(그래도 세션이 없으면 401 — 토큰만으론 불충분).

### 11.4 감사 org 결속 (`audit_events.org_id`)

- **V20** — `audit_events` 에 `org_id`(nullable) 컬럼 + `idx_audit_events_org_time(org_id, created_at)`.
  `detail` 은 TEXT(JSON 문자열)라 JSON 연산자로 org 를 거를 수 없어 전용 컬럼을 둔다. 전역 이벤트(로그인·MFA
  등)는 NULL.
- **오버로드로 하위호환**: `AuditEventService.record` 에 4-arg(`type, userId, orgId, detail`) 오버로드를 추가하고
  기존 3-arg 는 `orgId=null` 로 위임한다 — 전역 이벤트 콜러는 무변경, org 결속 이벤트만 컬럼을 채운다.
- **격리 조회**: `OrgAuditService.listForOrg` 는 `findByOrgIdOrderByCreatedAtDesc` 로 **org_id 정확 일치**만
  조회한다 — 타 org·전역(null) 이벤트를 절대 반환하지 않는다. 행위자가 플랫폼 운영자(`role=ADMIN`)인 org 결속
  이벤트(`ADMIN_ORG_*`)는 신원을 **마스킹**한다(`userId`/`email` null, `platformActor=true`, 콘솔은 역할 라벨만
  표시) — 내부 스태프 이메일을 테넌트 ORG_ADMIN 에게 노출하지 않는다.

### 11.5 소비 이벤트 seam (Phase 0ب, `consumption/` · `domain/consumption/`)

- **V17** — `consumption_events(source, external_id, org_id, user_sub?, merchant_id?, meal_window, menu_ref?,
  quantity, status[CONFIRMED|VOIDED], occurred_at, …)`. **멱등키 `UNIQUE(org_id, source, external_id)`** — org
  범위라 두 조직이 같은 external_id 를 써도 간섭하지 않고, 교차-테넌트 하이재킹은 lookup 자체가 org 스코프라
  원천 차단된다. 부분 인덱스 `idx_consumption_org_time_confirmed(org_id, occurred_at) WHERE status='CONFIRMED'`.
- **적재는 M2M 전용**(`ConsumptionEventController.authorizeWrite`): `meal.consumption.write` scope **AND** 토큰의
  org 결속 클레임(`org_id`/`orgs`)이 경로 org 를 포함해야 한다. 세션 쿠키 인증은 CSRF-off 체인에서 거부하고,
  **사용자 대면 토큰**(subject 가 실제 `users.id` 로 해석되는 authorization_code 베어러)은 write scope 가 있어도
  거부한다 — 정답데이터는 프로그램적 생산자(결제·POS 등 client_credentials)만 주입할 수 있다(일반 멤버의 위조
  주입 차단, 비결속은 fail-closed). 본문에 org 를 두지 않아(경로만 권위) 타 org 적재를 원천 차단한다.
- **갱신은 full-replace**: 기존 멱등키 행이 있으면 요청 본문으로 전체 필드를 덮어쓴다(선택 필드 생략 시 비움 —
  부분 병합의 의미 모호성 제거). 동시 재전송의 UNIQUE 위반은 `GlobalExceptionHandler` 가 409(재시도 안전)로 매핑.
- **조회는 집계만**: `GET .../aggregate?from&to&groupBy=date,meal_window[,menu]` — date × meal_window[× menu]
  카운트만 반환하고 **개별 이벤트·`user_sub` 는 절대 노출하지 않는다**. VOIDED 제외. 조회 창 폭 상한
  (`MAX_AGGREGATE_WINDOW_DAYS`=400, 컨트롤러가 to 절단)·결과 행 수 상한(`MAX_AGGREGATE_GROUPS`=5000, 고카디널리티
  menu_ref 폭발 방지)으로 자원 고갈을 막는다. 읽기 인가는 캘린더 조회와 동일한 3계층(세션 멤버 / 사용자 베어러
  활성 멤버십 / 서비스 베어러 `meal.consumption.read.all` 또는 org 결속 `meal.consumption.read`).
- **org 타임존 앵커(V18)**: 집계 date 버킷은
  `CAST((occurred_at AT TIME ZONE 'UTC') AT TIME ZONE org.timezone AS date)` 로 org-로컬 달력에 앵커링한다 —
  UTC 절단이 KST 아침(00:00~08:59)을 전날 버킷으로 오귀속시키는 정답데이터 오염을 막는다(§10 timezone 검증과 정합).
- **배치 provenance audit**: 적재 성공 배치마다 요청 1건당 1건 `CONSUMPTION_INGESTED` 를 org 결속으로 남긴다
  (`userId=null`, detail 은 `clientId`·집계 카운트·source 집합만 — user_sub·개별 이벤트 내용 미포함). 커밋 후
  성공 배치에만 기록된다(멱등 충돌 409·검증 400 은 예외로 audit 줄에 도달하지 않음).

### 11.6 마이그레이션 요약 (V15–V20)

| 버전 | 내용 |
| --- | --- |
| **V15** | 조직 테넌시 — `organizations` + `org_memberships`(`UNIQUE(org_id, user_id)`) + `sso_connections.org_id`(JIT 앵커) |
| **V16** | iCalendar — `calendar_feeds`(구독/업로드) + `calendar_events`(`UNIQUE(feed_id, uid, starts_at)`) |
| **V17** | 소비 이벤트 — `consumption_events`, 멱등키 `UNIQUE(org_id, source, external_id)`(org 범위) |
| **V18** | org 타임존 — `organizations.timezone`(기본 UTC, 집계 date 버킷 앵커) |
| **V19** | 조직 초대 — `org_invitations`(token 해시·단일사용·만료, `(org_id, email)` PENDING 부분 유니크) |
| **V20** | 감사 org 결속 — `audit_events.org_id` + `idx_audit_events_org_time` |

- **회귀 테스트**: `org/OrgInvitationApiIntegrationTest`·`OrgInvitationServiceIntegrationTest`(이메일 일치·계정
  열거·쿨다운·경합)·`OrgConsoleApiIntegrationTest`(인가 격리·guardLastAdmin·프로필 불변식·step-up)·
  `OrgAuditApiIntegrationTest`·`OrganizationServiceIntegrationTest`, `consumption/ConsumptionEventApiIntegrationTest`·
  `ConsumptionEventServiceTest`.
