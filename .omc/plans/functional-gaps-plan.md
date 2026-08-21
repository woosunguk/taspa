# 기능 공백 보완 계획 (프로덕션 하드닝 이후 순차 실행)

taspa 는 인증(로그인/MFA/패스키/소셜/매직링크/리스크)·세션·관리자 콘솔·JWK 회전까지 완성. 남은 **기능 공백**을 채운다.
프로덕션 준비 워크플로가 SecurityConfig/account.html/templates 를 편집하므로 **그 워크플로 완료 후** 실행한다(파일 충돌 방지).

## 공통 원칙
- 게이트 불변식 유지(pending 은 SecurityContext 밖). 기존 86+ 통합테스트·8 e2e 무손상.
- 파괴적/민감 자기서비스 작업은 `@RequireRecentAuth`(step-up) 적용. 각 이벤트 audit 기록.
- 빌드는 포그라운드 실행·완료 확인 후 보고(백그라운드 금지). auth-playground 수정·git init 금지.
- 단계마다 3방향 적대 리뷰 → 확정 결함 수정 → 빌드/테스트 검증(기존 파이프라인).

---

## Stage 1 — sub 안정화 (선행 필수)
현재 id_token 의 `sub` = 이메일. OIDC 규격상 sub 은 **불변·재사용 금지**여야 하고, 이메일 변경(Stage 2)을 안전하게 하려면 sub 을 이메일과 분리해야 한다.
- 토큰 커스터마이저(token/TokenCustomizerConfig)에서 `sub` 을 **users.id(UUID)** 로 변경, `email` 은 email 클레임으로 유지, `preferred_username`=이메일 추가.
- `LoginUserDetailsService`/principal name 은 이메일 유지(로그인 식별자)하되, 토큰 sub 매핑만 UUID 로.
- **영향 분석 필수**: demo-client 등 기존 클라이언트가 sub 을 키로 쓰면 계정 매핑이 바뀐다 → docs/integration-guide 와 architecture 에 "sub=안정적 UUID, 이메일은 email 클레임" 명시. 마이그레이션 주의 문서화.
- 테스트: id_token/userinfo 의 sub 이 UUID·이메일 변경에도 불변임을 검증.

## Stage 2 — 자기서비스 계정 관리 (최우선 공백)
계정 페이지(/account)에 섹션 추가 + `selfservice/` 모듈.
1. **프로필 편집**: displayName 변경(PATCH /api/account/profile, @Size). 간단.
2. **이메일 변경**: step-up → 새 이메일로 인증코드 발송(EmailVerificationService 재사용, 새 이메일 대상) → 코드 확인 시에만 전환. 전환 시 중복 이메일 검사, 이전 이메일로 "이메일이 변경되었습니다" 통지, 전 세션 유지(sub 불변이므로 안전), audit EMAIL_CHANGED. 미검증 상태로 두지 말 것.
3. **인세션 비밀번호 변경/설정**: 비밀번호 보유 계정은 현재 비밀번호 확인(또는 step-up)+새 비밀번호(정책 검증) → 변경 시 전 세션·신뢰기기 폐기(기존 PasswordResetService 훅 재사용). 소셜 전용(password_hash NULL) 계정은 step-up 후 "비밀번호 설정" 허용. audit PASSWORD_CHANGED.
4. **계정 탈퇴/삭제**: step-up + 확인 화면(이메일 재입력 등) → 사용자의 oauth2_authorization/consent, federated_identities, 패스키, 신뢰기기, 세션 전부 폐기 후 users 행 삭제(FK CASCADE 확인). audit ACCOUNT_DELETED(detail 은 이메일 SHA-256 해시만 — PII 미보존). 삭제 후 로그아웃·쿠키 정리. 소프트삭제 대신 하드삭제(탈퇴 즉시 PII 제거) 채택, 근거 문서화.
- 테스트: 각 플로우 정상+step-up 강제+세션 폐기+audit. 탈퇴 후 재로그인 불가·동일 이메일 재가입 가능.

## Stage 3 — 연결된 앱(제3자 접근) 관리
사용자가 자신이 권한을 준 클라이언트를 보고 철회.
- oauth2_authorization/oauth2_authorization_consent 를 principal_name(=이메일)으로 조회해 client 목록·부여 scope·최근 사용 시각 표시(JdbcTemplate; registeredClientRepository 로 client_name 복원).
- 철회: 해당 client+user 의 authorization·consent 행 삭제(활성 토큰 무효화 효과) + audit THIRDPARTY_ACCESS_REVOKED. step-up 적용.
- 계정 페이지 "연결된 앱" 섹션 + /api/account/authorized-clients (GET/DELETE).
- 테스트: 동의 후 목록 노출, 철회 시 refresh_token 재사용 불가.

## Stage 4 — Single Logout 완성 (Back-Channel Logout)
- **선행 검증**: Spring Authorization Server 1.4.x 가 OIDC Back-Channel Logout 을 지원하는지 로컬 jar(oauth2-authorization-server 1.4.2) javap/문서로 확인. 지원 시 진행, 미지원 시 RP-Initiated 만 문서화하고 보류(보고에 명시).
- 지원 시: 관리 콘솔 클라이언트 폼/AdminClientService 에 `backchannel_logout_uri`(+ session_required 여부) 설정 추가, ClientSettings 반영. `/connect/logout` 로그아웃 시 등록된 RP 에 logout token 전송 동작 확인.
- RP-Initiated Logout(/connect/logout + post_logout_redirect_uri)은 이미 동작 — e2e 로 왕복(로그인→RP 로그아웃→post_logout redirect) 검증 추가.
- demo-client 에 로그아웃 시 taspa /connect/logout 호출(RP-initiated) 옵션 추가로 SLO 시연.

## Stage 5 — Device Authorization Grant UI
엔드포인트(/oauth2/device_authorization, /oauth2/token device grant)는 존재. 사용자용 화면만 없음.
- `/activate` (또는 /device) 페이지: user_code 입력 → 검증 → 동의(허용/거부) 화면. SAS 의 device verification 커스텀 페이지 패턴(`deviceVerificationEndpoint`) 사용.
- demo-app/데모에 device_code grant 허용 클라이언트 예시. 관리 콘솔 grant type 선택에 device_code 추가 검토.
- 테스트: user_code 입력→승인→토큰 발급 플로우(통합).

## Stage E — 기업 SSO 연동 (사용자 명시 요청, 높은 우선순위 — Stage 1 이후)
taspa 사용자가 **소속 회사의 외부 IdP**로 로그인(taspa 가 SP/RP). 소셜 로그인(소비자)의 기업 버전. 기존 federation/ 모듈·FederatedLoginSuccessHandler·계정 연결 로직을 최대한 재사용. sub 안정화(Stage 1) 선행 권장.
- **E-0 리서치(선행 필수)**: Spring Security 6.4 의 `spring-security-saml2-service-provider` 실물(로컬 jar javap) — `RelyingPartyRegistration`/`Saml2LoginConfigurer`/ACS 경로(`/login/saml2/sso/{registrationId}`)/메타데이터 엔드포인트/서명·복호화 인증서 처리/assertion 속성 매핑. 기업 OIDC 는 기존 oauth2Login 재사용 가능 여부. HRD(홈 렐름 디스커버리) 패턴. 실측 후 스펙 확정.
- **E-1 조직 커넥션 모델**: `V13__enterprise_sso.sql` — `sso_connections`(id, org_name, protocol: SAML|OIDC, enabled, jit_provisioning, created_at), `sso_domains`(domain UNIQUE, connection_id FK) 도메인→커넥션 매핑, 프로토콜별 설정(OIDC: issuer/endpoints/client, SAML: idp_entity_id/sso_url/x509_cert/속성매핑)을 커넥션에 저장. 시크릿·인증서는 암호화(AesEncryptionService 재사용).
- **E-2 기업 OIDC 연동**: 조건부 `ClientRegistrationRepository`(SocialClientRegistrations 확장)에 DB 기반 조직 OIDC 커넥션 추가. 콜백은 기존 `/login/oauth2/code/{registrationId}`. FederatedLoginSuccessHandler 분기에 "기업 도메인 검증 이메일 → 자동 프로비저닝/연결(JIT)" 추가(도메인 소유가 조직에 귀속되므로 소셜보다 신뢰↑, 단 도메인 일치 강제).
- **E-3 SAML 2.0 SP**(순수 신규): 의존성 `spring-security-saml2-service-provider`. `RelyingPartyRegistrationRepository` 를 DB(sso_connections SAML) 기반으로 구현, `saml2Login` 설정(기본 체인 @Order(2)). ACS 성공 → assertion 속성(email/name) 정규화 → federation 링킹/JIT(기존 핸들러 패턴 재사용, `SocialAttributes` 에 SAML 소스 추가). SP 메타데이터 노출 엔드포인트. 서명 검증·(선택) 암호화.
- **E-4 홈 렐름 디스커버리(HRD)**: identifier-first 확장 — `/login/identifier` 제출 시 이메일 도메인으로 `sso_domains` 조회. 매칭 시 비밀번호 페이지 대신 해당 조직 IdP 로 리다이렉트(OIDC: `/oauth2/authorization/{id}`, SAML: `/saml2/authenticate/{id}`). 미매칭이면 기존 흐름(비밀번호/패스키/소셜). 로그인 페이지에 "회사 계정으로 로그인" 진입점도 제공.
- **E-5 관리자 콘솔 — 기업 커넥션 관리**: `/admin/sso` — 커넥션 CRUD(프로토콜·도메인·엔드포인트/메타데이터·인증서·JIT 정책), 도메인 검증 표시, 테스트 로그인 링크. @RequireRecentAuth + audit(ADMIN_SSO_*). SP 메타데이터/ACS URL 을 관리자에게 노출(상대 IdP 등록용).
- **E-6 계정/보안 상호작용**: 기업 SSO 로그인도 로컬 MFA 게이트 정책 재검토(조직 IdP 가 MFA 를 했다고 가정하지 말 것 — 기존 소셜 정책과 동일하게 로컬 MFA 유지, 단 커넥션별 "IdP MFA 신뢰" 옵션은 설계 노트로). 기업 계정의 unlink/탈퇴는 자기서비스(Stage 2/3)와 정합.
- 테스트: 기업 OIDC 는 WireMock 스텁(소셜 테스트 패턴 재사용), SAML 은 spring-security-test 의 SAML 지원 또는 정적 메타데이터/서명 픽스처로 ACS→프로비저닝→로그인 검증. HRD 라우팅(도메인 매칭/미매칭). JIT 프로비저닝·도메인 강제. e2e 는 기업 OIDC 를 demo 조직 IdP(로컬 2차 taspa 인스턴스 또는 스텁)로 왕복 시연 가능하면 추가, 아니면 통합테스트로 대체하고 보고에 명시.
- 문서: docs/enterprise-sso-setup.md(조직 IdP 등록 절차 — SAML 메타데이터 교환, OIDC issuer 등록, 도메인 매핑), architecture.md 에 SP/RP 아키텍처·HRD·JIT 정책.

## Stage 6 — i18n (격리 실행 — 모든 템플릿 손댐)
- MessageSource(messages_ko.properties 기본 + messages_en.properties), LocaleResolver(쿠키 기반) + LocaleChangeInterceptor(?lang=), Accept-Language 폴백.
- 모든 Thymeleaf 템플릿의 하드코딩 국문을 `#{키}` 로 외부화(기존 문구 보존, 키 체계 login./account./admin. 등). 이메일 본문(MailService)도 로케일별.
- 로그인/계정 페이지에 언어 전환 링크(ko/en).
- **주의**: 대규모 기계적 변경 — 단독 스테이지로 실행, 회귀(모든 e2e 셀렉터가 텍스트 의존 시 깨질 수 있음)를 반드시 재확인. e2e 는 가능하면 텍스트 대신 구조/URL 로 단언하도록 병행 보정.

## Stage 7 (낮은 우선순위 — 여력 시)
- 유출 비밀번호 검사: HIBP k-익명(SHA-1 prefix range) 조회를 PasswordPolicyService 에 옵션 추가(외부 호출 타임아웃·실패 시 통과, 설정 on/off).
- 세분화 역할/그룹: 현재 USER/ADMIN 2단계 → per-client role 클레임 요구 시 확장(요건 불명확하면 보류하고 설계 노트만).
- 로그인 활동 이력 페이지: login_events 를 계정 페이지에 "최근 로그인 활동"으로 노출(세션 목록과 별개, 읽기 전용).

## 실행 순서 권고
Stage 1(sub) → **E(기업 SSO — 사용자 명시 요청, E-0 리서치 선행)** → 2(자기서비스) → 3(연결된 앱) → 4(SLO) → 5(device) → 6(i18n, 격리) → 7(여력). 기업 SSO 는 federation/ 모듈을 재사용하므로 sub 안정화 직후가 유리. i18n 은 Stage E 가 만든 새 템플릿(HRD·관리자 SSO)까지 외부화하도록 마지막.
