# Stage E — 기업 SSO 연동 구현 스펙 (리서치 확정본)

taspa(SP/RP)가 회사 외부 IdP(SAML 2.0 / 조직 OIDC)로 taspa 사용자를 인증. 소셜 로그인의 기업 버전.
리서치(w4jyds91n: SAML jar/POM 실측 + 기존 코드 분석)로 확정된 사실 기반 — 재조사 없이 신뢰하되, SAML DSL 실제 시그니처는 구현 중 6.4.4 문서/실물로 대조.

## 확정 사실 (리서치)
- **의존성**: `implementation("org.springframework.security:spring-security-saml2-service-provider")` (Boot 3.4.4 BOM → 6.4.4). OpenSAML 4.3.2가 transitive(compile)로 딸려옴.
- **★빌드 함정(필수)**: OpenSAML 4.3.2는 Maven Central에 없음(404 실측). `build.gradle.kts`의 subprojects `repositories`에 Shibboleth 저장소 추가 필수:
  ```kotlin
  repositories { mavenCentral(); maven { url = uri("https://build.shibboleth.net/maven/releases/") } }
  ```
  누락 시 SAML 의존 추가 순간 빌드 실패. 6.4.4 기본 클래스패스는 OpenSAML 4 → `OpenSaml4AuthenticationProvider` 자동 선택.
- **경로(충돌 없음, 실측)**: 조직 OIDC는 소셜과 공유(`/oauth2/authorization/{regId}`, `/login/oauth2/code/{regId}`). SAML은 `/saml2/authenticate/{regId}`(진입), `/login/saml2/sso/{regId}`(ACS), `/saml2/service-provider-metadata/{regId}`(SP 메타데이터). AS endpointsMatcher(`/oauth2/authorize` 등)와 겹치지 않음.
- **CSRF**: ACS는 IdP의 cross-site form POST → `csrf.ignoringRequestMatchers`에 `/login/saml2/sso/**` 추가 필수(현재 /api/** + /api/sessions·admin 예외 패턴 확장).
- **성공 핸들러**: SAML `Saml2WebSsoAuthenticationFilter`도 oauth2Login처럼 성공 시 세션에 `Saml2Authentication`을 먼저 저장 → 반드시 로컬 UserDetails 완전인증으로 덮어써야(FederatedLoginSuccessHandler 미러링).

## 정책 결정 (자율 기본값 — 문서에 근거 명시)
1. **SSO 강제**: 도메인이 enabled 커넥션과 매칭되면 로컬 password/passkey보다 **먼저 단락(short-circuit)**해 IdP로 리다이렉트. 커넥션에 `enforced` 플래그(기본 true). enforced=false면 로그인 페이지에 "회사 계정으로 로그인" 버튼만 제시하고 로컬 흐름도 허용.
2. **로컬 MFA**: 기본 **유지**(외부 IdP MFA를 신뢰하지 않음 — 기존 소셜 정책 일관). 커넥션에 `trust_idp_mfa` 플래그(기본 false); true면 기업 로그인 후 로컬 MFA 게이트 스킵.
3. **federated_identities 확장**: `connection_id UUID NULL` 컬럼 추가(멀티 커넥션·표시명 확장). provider 값은 `saml:{regId}` / `oidc:{regId}`. V5 UNIQUE(provider, provider_user_id)는 유지.
4. **도메인 매칭**: v1은 **정확 매칭**(서브도메인 미포함). 도메인 검증은 관리자가 콘솔에서 `verified` 수동 표시(소유 증명 절차는 문서 노트, 자동화는 후순위).
5. **도메인 일치 강제(보안 핵심)**: 조직 로그인 성공 시 **공급자 이메일 도메인 == 커넥션의 verified 도메인** 강제(불일치 시 실패) — 조직 IdP가 타 도메인 이메일을 주장해 계정 탈취하는 것 차단.

## DB — V14__enterprise_sso.sql
```sql
CREATE TABLE sso_connections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    registration_id VARCHAR(64) NOT NULL UNIQUE,   -- 경로에 쓰이는 안정 식별자(예 org-acme)
    display_name VARCHAR(100) NOT NULL,
    protocol VARCHAR(8) NOT NULL,                  -- OIDC | SAML
    enabled BOOLEAN NOT NULL DEFAULT true,
    enforced BOOLEAN NOT NULL DEFAULT true,
    trust_idp_mfa BOOLEAN NOT NULL DEFAULT false,
    -- OIDC
    oidc_issuer VARCHAR(512), oidc_authorization_uri VARCHAR(512), oidc_token_uri VARCHAR(512),
    oidc_jwks_uri VARCHAR(512), oidc_user_info_uri VARCHAR(512), oidc_user_name_attr VARCHAR(64),
    oidc_client_id VARCHAR(255), oidc_client_secret_encrypted VARCHAR(1024), oidc_scopes VARCHAR(255),
    -- SAML
    saml_idp_entity_id VARCHAR(512), saml_sso_url VARCHAR(512),
    saml_verification_cert TEXT, saml_want_authn_signed BOOLEAN DEFAULT false,
    saml_email_attr VARCHAR(128) DEFAULT 'email', saml_name_attr VARCHAR(128) DEFAULT 'name',
    created_at TIMESTAMP NOT NULL DEFAULT now(), updated_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE TABLE sso_domains (
    domain VARCHAR(255) PRIMARY KEY,               -- 소문자·trim 정규화
    connection_id UUID NOT NULL REFERENCES sso_connections(id) ON DELETE CASCADE,
    verified BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX idx_sso_domains_connection ON sso_domains(connection_id);
ALTER TABLE federated_identities ADD COLUMN connection_id UUID REFERENCES sso_connections(id) ON DELETE SET NULL;
```
client_secret·verification_cert 중 민감(secret)은 AesEncryptionService로 암호화. Hibernate ddl validate 유지.

## 구현 항목
- `build.gradle.kts`: SAML 의존성 + Shibboleth repo(★).
- `domain/sso/`: `SsoConnection`, `SsoDomain` 엔티티 + repository. 프로토콜 enum.
- `enterprise/`(신규): 
  - `SsoConnectionService`(CRUD, 도메인 매핑 조회 `findEnabledConnectionByDomain`), `SsoConnectionProperties`.
  - `CompositeClientRegistrationRepository`: 기존 SocialClientRegistrations(정적 소셜 3종) + DB 조직 OIDC 커넥션(`findByRegistrationId`가 DB→ClientRegistration 변환, 짧은 캐시). 기존 `clientRegistrationRepository` 빈을 이걸로 교체(0건이어도 소셜만 있으면 동작, 소셜·조직 모두 0이면 기존 조건부 유지).
  - `EnterpriseOidcUserService`: SocialAttributesExtractor에 `oidc:{regId}` 표준 클레임 브랜치 추가(구글 추출과 동일 구조).
  - `Saml2AttributesExtractor` + `Saml2FederatedLoginSuccessHandler`: FederatedLoginSuccessHandler 미러링(Saml2AuthenticatedPrincipal→SocialAttributes 정규화, provider=`saml:{regId}`, emailVerifiedByProvider=true, **도메인 일치 강제** 후 FederationService 링킹/JIT 재사용, LoginFlowSupport로 완전인증 승격).
  - `DbRelyingPartyRegistrationRepository`: `RelyingPartyRegistrationRepository`(Iterable) 구현, sso_connections(SAML)→RelyingPartyRegistration(entityId/ACS 템플릿, assertingPartyMetadata: idp entityId/sso url/verification x509). 0건이면 빈 미노출.
- `federation/FederatedLoginSuccessHandler`: 조직 OIDC 분기 시 도메인 일치 강제 삽입(attributes 추출 직후). isOrgConnection 판별(registrationId prefix 또는 커넥션 조회).
- `login/LoginFlowController.submitIdentifier`: 이메일 정규화 직후 HRD — 도메인→`ssoConnectionService.findEnabledConnectionByDomain`. 매칭+enforced면 `redirect:/oauth2/authorization/{regId}`(OIDC) 또는 `/saml2/authenticate/{regId}`(SAML). 미매칭이면 기존 흐름.
- `config/SecurityConfig`: 
  - permitAll에 `/saml2/authenticate/**`, `/login/saml2/sso/**`, `/saml2/service-provider-metadata/**` 추가.
  - csrf ignoringRequestMatchers에 `/login/saml2/sso/**` 추가.
  - `ObjectProvider<RelyingPartyRegistrationRepository>` 있을 때만 `http.saml2Login { loginPage("/login"); authenticationManager(OpenSaml4 provider + ResponseAuthenticationConverter); successHandler(saml2SuccessHandler); failureHandler(→/login?error=sso) }`.
  - @Order(1) AS 체인은 무접촉.
- `admin/`: `/admin/sso` — SsoConnection CRUD(프로토콜·도메인·엔드포인트/메타데이터·인증서·enforced/trust_idp_mfa·enabled), 도메인 verified 토글, SP 메타데이터/ACS URL을 관리자에게 표시(상대 IdP 등록용). @RequireRecentAuth + audit ADMIN_SSO_*. 관리 콘솔 네비에 "기업 SSO" 추가.
- 로그인 페이지: enforced=false 커넥션용 "회사 계정으로 로그인" 진입점(선택). identifier-first가 주 경로.

## 테스트
- **조직 OIDC**: WireMock으로 조직 IdP token/userinfo 스텁(기존 SocialLoginFlowIntegrationTest 패턴) — HRD 라우팅(도메인 매칭→redirect), JIT 프로비저닝, **도메인 일치 강제**(공급자가 타 도메인 이메일 주장 시 실패).
- **SAML**: 정적 self-signed 키페어로 서명한 `<saml2:Response>` 픽스처 → (a) `OpenSaml4AuthenticationProvider`에 `Saml2AuthenticationToken` 직접 authenticate로 converter/링킹 단위검증, 또는 (b) MockMvc로 `/login/saml2/sso/{id}`에 `SAMLResponse` 폼 POST(CSRF 면제·permitAll도 검증). Spring Security의 TestSaml2/TestOpenSamlObjects 헬퍼 참고. 실 IdP·네트워크 불필요.
- HRD: 도메인 매칭/미매칭 분기, enforced 여부.
- 관리 콘솔: SSO 커넥션 CRUD 권한(403/200), 도메인 매핑.
- 기존 108 테스트·8 e2e 무손상. SecurityConfig 체인 변경이 소셜·패스키·세션 게이트를 안 깨는지.
- e2e(선택): 조직 OIDC를 로컬 2차 IdP 또는 스텁으로 왕복 가능하면 sso-flow 확장, 아니면 통합테스트로 대체하고 보고 명시.

## 문서
- `docs/enterprise-sso-setup.md`(신규): 조직 IdP 등록 절차 — SAML(SP 메타데이터/ACS URL 교환, IdP 인증서·SSO URL 입력), OIDC(issuer 등록), 도메인 매핑·검증, enforced/trust_idp_mfa 의미.
- architecture.md: SP/RP 아키텍처, HRD, 도메인 일치 강제, MFA 정책, 경로 네임스페이스.
- README 기능표, CLAUDE.md 모듈(enterprise/, domain/sso/, V14).

## 제약
- 기존 dev/소셜/패스키/게이트 무손상. auth-playground·git init 금지. 빌드 포그라운드 확인.
- SLO(SAML Single Logout)·암호화 어서션·login_hint 전달·서브도메인 매칭·도메인 소유 자동검증은 **범위 밖(후순위)** — 보고에 명시.
