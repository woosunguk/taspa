# 기업 SSO 연동 (Stage E)

taspa 가 **SP/RP**(Service Provider / Relying Party)로서 회사의 외부 IdP(SAML 2.0 또는 조직 OIDC)로
사용자를 인증한다. 소셜 로그인의 기업 버전 — 조직 구성원이 회사 IdP 계정으로 taspa 에 로그인한다.

관리 콘솔 **`/admin/sso`** 에서 커넥션을 등록·관리한다. 이 문서는 상대 IdP 등록 절차와 정책 플래그를 설명한다.

## 개념

- **커넥션(connection)**: 조직 하나 + 프로토콜 하나(OIDC | SAML). `registration_id` 는 인증 경로에 쓰이는
  안정 식별자(소문자·숫자·하이픈, 27자 이내)다.
- **도메인 매핑(HRD, Home Realm Discovery)**: 이메일 도메인 → 커넥션. `verified` 도메인만 로그인 라우팅과
  도메인 일치 강제에 쓰인다. 관리자가 콘솔에서 수동으로 verified 로 표시한다.
- **enforced**: 도메인이 매칭되면 로컬 비밀번호/패스키보다 **먼저** IdP 로 단락 리다이렉트(기본 true).
  false 면 identifier-first 로컬 흐름을 허용하고 로그인 페이지에 "회사 계정으로 로그인" 보조 버튼만 제시한다.
- **trust_idp_mfa**: true 면 조직 로그인 성공 후 로컬 MFA 게이트를 건너뛴다(외부 IdP 의 MFA 신뢰). 기본
  false — taspa 는 기본적으로 외부 IdP MFA 를 신뢰하지 않고 로컬 MFA 를 유지한다(소셜 로그인 정책과 일관).

## 보안: 도메인 일치 강제 (핵심)

조직 로그인 성공 시 **공급자가 주장하는 이메일의 도메인 == 커넥션의 verified 도메인**을 강제한다(불일치 시
로그인 실패, `SSO_DOMAIN_REJECTED` 감사). 이는 조직 IdP(또는 침해된 IdP)가 타 도메인 이메일을 주장해
남의 taspa 계정을 탈취하는 것을 차단한다. verified 도메인이 하나도 없는 커넥션으로는 어떤 이메일도 로그인할 수
없다(안전 기본값).

## 조직 OIDC 등록

조직 OIDC 는 소셜 로그인과 경로를 공유한다: 진입 `/oauth2/authorization/{registration_id}`,
콜백 `/login/oauth2/code/{registration_id}`.

1. 상대 IdP(예: Okta/Azure AD/Keycloak)에 **OIDC 클라이언트(Web/Confidential)**를 만든다.
   - **Redirect URI**: `https://<taspa-issuer>/login/oauth2/code/{registration_id}` (콘솔이 표시).
   - 스코프에 `openid` 포함(taspa 가 자동 포함). `email`·`profile` 권장.
2. taspa 콘솔 `/admin/sso` 에서 커넥션을 만든다(protocol=OIDC):
   - `authorization_uri`, `token_uri`, `jwks_uri`(필수), `userinfo_uri`(선택), `issuer`(선택), `client_id`,
     `client_secret`, `scopes`, `user_name_attribute`(기본 `sub`).
   - `client_secret` 은 AES-GCM 으로 암호화 저장되고 콘솔에 다시 노출되지 않는다. 암호화 키는
     **`MFA_ENCRYPTION_KEY`**(`mfaEncryptionService`)라, 그 키를 교체하면 이 시크릿도 복호화 불가가 되어
     재입력이 필요하다.
3. 도메인 매핑을 추가하고 소유를 확인한 뒤 **verified** 로 토글한다.

> **주의(제약)**: OIDC 커넥션은 OAuth2 클라이언트 레포지토리가 활성일 때 동작한다. 이 레포지토리는 소셜
> 공급자가 하나라도 환경변수로 설정돼 있으면 활성이며, 그 경우 새 OIDC 커넥션은 **재기동 없이** 즉시 동작한다.
> 소셜 공급자가 전무한 배포에서 첫 OIDC 커넥션을 활성화하려면 한 번의 재기동이 필요하다(Spring 필터체인이
> 기동 시 고정되는 제약 — SAML 은 이 제약이 없다).

## SAML 2.0 등록

SAML 경로: 진입 `/saml2/authenticate/{registration_id}`, ACS(Assertion Consumer Service)
`/login/saml2/sso/{registration_id}`, SP 메타데이터 `/saml2/service-provider-metadata/{registration_id}`.

1. taspa 콘솔 `/admin/sso` 에서 커넥션을 만든다(protocol=SAML):
   - IdP `entity_id`, IdP `SSO URL`(HTTP-Redirect 바인딩), IdP **서명 검증 인증서(PEM)**.
   - `email`/`name` 어트리뷰트 이름(기본 `email`/`name`). NameID 가 이메일이면 email 어트리뷰트가 없어도 된다.
2. 콘솔이 표시하는 **SP 값**을 상대 IdP 에 등록한다:
   - **SP entity_id / metadata**: `https://<taspa-issuer>/saml2/service-provider-metadata/{registration_id}`
   - **ACS URL**(HTTP-POST 바인딩): `https://<taspa-issuer>/login/saml2/sso/{registration_id}`
   - IdP 가 메타데이터 URL 임포트를 지원하면 위 metadata URL 을 그대로 넣으면 된다.
3. 도메인 매핑을 추가하고 verified 로 토글한다.

taspa 는 IdP 응답(Assertion)의 **서명을 등록된 인증서로 검증**한다(OpenSAML 4 / `OpenSaml4AuthenticationProvider`).
ACS 는 IdP 의 cross-site form POST 지점이라 CSRF 토큰이 없다 — 서명 검증이 진위를 보장하므로 ACS 는 CSRF
면제 목록(`/login/saml2/sso/**`)에 있다.

> SP 가 AuthnRequest 를 서명하거나 암호화된 Assertion 을 복호하는 것(SP 키페어)은 v1 범위 밖이다. IdP 가
> 서명된 요청을 요구하면(`saml_want_authn_signed`) 별도 SP 키페어 구성이 필요하다(후순위).

> **보안 한계(로그인 CSRF)**: v1 은 Spring Security 기본값을 따라 IdP-initiated(unsolicited) SAML 응답을
> `InResponseTo` 상관 없이 수용하고, SP 는 AuthnRequest 를 서명하지 않는다. 이 조합에서는 자신의 조직 계정에
> 대한 유효 서명 어서션을 가진 공격자가 피해자 브라우저로 `SAMLResponse` 를 ACS 에 form POST 해 피해자를
> **공격자 계정으로 강제 로그인**시킬 수 있다(고전적 SAML 로그인 CSRF — 세션 스와핑). 어서션 서명 검증
> 자체는 정상 동작하므로 **타인 계정 탈취(다른 사람으로 로그인)는 불가**하며, 영향은 세션 스와핑에 한정된다.
> 완화(후순위): SP-initiated 강제(`RelayState`/`InResponseTo` 상관 검증) 또는 AuthnRequest 서명 도입.

## 로그인 흐름 (HRD)

1. 사용자가 `/login` 에서 이메일을 입력한다.
2. 도메인이 **enabled + verified + enforced** 커넥션과 매칭되면 IdP 로 단락 리다이렉트한다(OIDC 또는 SAML).
3. IdP 인증 후 taspa 로 복귀 → 도메인 일치 강제 → 기존 연결이면 로그인, 없으면 JIT 프로비저닝(비밀번호 없는
   계정 생성 + 연결) → 로컬 UserDetails 완전 인증으로 승격(로컬 MFA 게이트는 `trust_idp_mfa` 가 아니면 유지).
4. 매칭이 없으면 기존 로컬 흐름(비밀번호/패스키)으로 진행한다.

## 범위 밖 (후순위)

SLO(SAML Single Logout), 암호화 어서션, `login_hint` 전달, 서브도메인 매칭, 도메인 소유 자동 검증,
SP AuthnRequest 서명, IdP-initiated 응답 차단(SP-initiated 강제).
