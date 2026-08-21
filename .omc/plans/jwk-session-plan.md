# JWK 영속화·회전 + 감사 DB + 원격 세션 관리 구현 스펙

리서치(로컬 jar javap 실측 + 공식 소스)로 확정. "확정 사실"은 재조사 없이 신뢰할 것.
2단계: **Stage A(JWK 회전 + 감사 DB)** → **Stage B(Spring Session JDBC + 세션 관리)**.

## 공통

- 빌드는 반드시 **포그라운드로 실행하고 완료를 확인한 뒤** 보고하라 (백그라운드로 걸어두고 종료 금지).
- 완료 기준: `cd /Users/woosunguk/workspace/taspa && ./gradlew build` 전체 통과 (Docker 켜져 있음).
- auth-playground 수정 금지, git init 금지. 기존 컨벤션 유지.

---

# Stage A — JWK DB 영속화 + 키 회전 + 감사 로그 DB

## A-1. 확정 사실 (javap 실측 — spring-security-oauth2-jose 6.4.4, SAS 1.4.2, nimbus 9.47)

- `NimbusJwtEncoder.encode()`는 매번 `jwkSource.get(selector)`를 호출 (인코더 키 캐시 없음) → **DB 기반 동적 JWKSource 완전 지원**. `/oauth2/jwks` 필터도 매 요청 조회.
- **치명 규칙**: RSA 서명키가 2개 이상 매칭되면 `JwtEncodingException("Found multiple JWK signing keys...")` — 첫 키 선택이 아니라 **예외**다. 회전 유예 구간(active+retired 공존)에는 반드시 `OAuth2TokenCustomizer<JwtEncodingContext>`에서 `context.jwsHeader.keyId(activeKid)`로 고정할 것 (기존 token/TokenCustomizerConfig.kt에 추가).
- `/oauth2/jwks`는 `JWKSet.toString()`(publicOnly) 경로라 개인키 포함 RSAKey를 반환해도 공개 부분만 노출된다. retired 키도 게시됨 → 유예기간 검증의 근간.
- 직렬화: `rsaKey.toJSONString()`이 개인키(d,p,q,dp,dq,qi) 포함 완전 직렬화, 복원은 `RSAKey.parse(String)`. kid는 JWT 헤더에 자동 포함(addKeyIdentifierHeadersIfNecessary), 검증 측(JwtDecoder/외부 RS)은 kid 매칭으로 retired 키도 조회.

## A-2. 구현

- **V8__jwk_and_audit.sql**:
  ```sql
  CREATE TABLE jwk_keys (
      kid VARCHAR(64) PRIMARY KEY,
      key_json_encrypted TEXT NOT NULL,      -- RSAKey.toJSONString() 을 AES-GCM 암호화
      status VARCHAR(16) NOT NULL,           -- ACTIVE | RETIRED
      created_at TIMESTAMP NOT NULL DEFAULT now(),
      activated_at TIMESTAMP,
      retired_at TIMESTAMP
  );
  CREATE TABLE audit_events (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      type VARCHAR(64) NOT NULL,
      user_id UUID,
      detail TEXT,                           -- JSON 직렬화
      created_at TIMESTAMP NOT NULL DEFAULT now()
  );
  CREATE INDEX idx_audit_events_user_time ON audit_events(user_id, created_at);
  CREATE INDEX idx_audit_events_type_time ON audit_events(type, created_at);
  ```
- **암호화**: `common/crypto/AesEncryptionService`를 키 문자열을 생성자 인자로 받게 리팩터 → 빈 2개: `mfaEncryptionService`(기존 taspa.mfa.encryption-key), `jwkEncryptionService`(`taspa.jwk.encryption-key`, 미설정 시 mfa 키로 폴백). 기존 사용처는 mfa 빈을 주입받도록 정리(@Qualifier).
- **token/JwkStorageService**: 부트스트랩(ACTIVE 없으면 RSA 2048 생성·암호화 저장, kid=UUID), `currentKeys(): List<RSAKey>`(ACTIVE+RETIRED 복호화, **60초 인메모리 캐시**), `activeKid(): String`, `rotate()`(트랜잭션 + `SELECT ... FOR UPDATE`로 중복 회전 방지: ACTIVE→RETIRED(retired_at), 새 ACTIVE 생성, 캐시 무효화), `purgeExpired()`(retired_at + grace 경과 행 삭제).
- **token/JwkConfig**: 기존 인메모리 생성 제거 → `JWKSource { selector, _ -> selector.select(JWKSet(storage.currentKeys())) }`.
- **TokenCustomizerConfig**: `context.jwsHeader.keyId(storage.activeKid())` 추가 (**필수** — A-1).
- **회전 스케줄**: 기존 scheduler 패턴으로 일 1회 `rotateIfDue()`: `taspa.jwk.rotation-period`(기본 30d) 경과 시 rotate, `taspa.jwk.retirement-grace`(기본 7d) 경과 retired 삭제. Duration 타입 프로퍼티.
- **audit**: `AuditEventService.record`가 로그 + `domain/audit/AuditEvent` DB 저장(detail은 ObjectMapper JSON). 기존 RetentionCleanupJob에 audit 보존(`taspa.retention.audit-days` 기본 365) 추가.
- **문서**: architecture.md에 키 수명주기(ACTIVE→RETIRED→삭제, 유예 하한 = 토큰 최대 수명 + JWKS 캐시 TTL), 유출 시 즉시 회전 runbook(retired 즉시 삭제 = 해당 키 토큰 전체 무효화).

## A-3. 테스트 (Stage A)

1. 부트스트랩 멱등성(재기동 시뮬 = 새 서비스 인스턴스가 같은 DB에서 동일 키 로드)
2. **재시작 생존**: JwtEncoder로 토큰 발급 → 새 JwkStorageService/JWKSource/JwtDecoder 인스턴스(같은 DB)로 검증 성공
3. 회전: rotate 후 JWKS에 두 키 공존, 신규 토큰 kid=새 active, **구 키로 서명된 토큰이 여전히 검증됨**, grace 경과 purge 후 구 키 소멸
4. 회전 유예 구간에서 토큰 발급이 예외 없이 성공 (kid 고정 검증 — 이 테스트가 A-1 치명 규칙의 회귀 방지)
5. audit_events 저장 + 보존 정리

---

# Stage B — Spring Session JDBC + 원격 세션 관리

## B-1. 확정 사실 (spring-session 3.4.2 소스 + SS 6.4.4 javap 실측)

- 의존성 `org.springframework.session:spring-session-jdbc`(Boot BOM 3.4.2), `@EnableJdbcHttpSession` 불필요(자동 구성). `spring.session.jdbc.initialize-schema: never` 명시 — 스키마는 Flyway.
- PRINCIPAL_NAME은 SPRING_SECURITY_CONTEXT의 `authentication?.name`(=이메일)로 자동 인덱싱. pending 단계 세션은 목록에 안 잡힘(원하는 동작). `FindByIndexNameSessionRepository.findByPrincipalName(email)` → Map<sessionId, Session>(속성 포함 로드). `deleteById` 즉시 삭제, **부활 없음**(UPDATE 0행 무시 + 속성 INSERT FK 위반 무시 — 소스 확인). 진행 중 요청만 요청 수명 동안 유효.
- `changeSessionId()` 정상 동작(PRIMARY_ID 불변, 속성 보존) — 기존 세션 고정 방어 그대로 성립.
- **직렬화 전수 실측**: 유일한 블로커 = `PublicKeyCredentialCreationOptions`(패스키 **등록** 옵션, Serializable 아님 — 멤버들도). 로그인 옵션(PublicKeyCredentialRequestOptions)·SecurityContextImpl·UsernamePasswordAuthenticationToken·User·DefaultSavedRequest·CsrfToken·taspa 자체 속성(PendingAuth/SocialLinkIntent/PendingSocialLink/SocialReauthIntent/StepUp 값들)은 **전부 Serializable — 수정 불요**. 미조치 시 패스키 등록이 응답 커밋 시점 SerializationException으로 즉사한다.
- 6.4 DSL에는 creationOptionsRepository 주입 옵션이 없다(7.x와 혼동 금지). `PublicKeyCredentialCreationOptionsFilter`는 setter 없음 → **필터 교체 필요**. `WebAuthnRegistrationFilter`에는 `setCreationOptionsRepository(...)` setter 있음. taspa의 기존 체인 후처리(SecurityConfig.customizeWebAuthnAuthenticationFilter)가 쓰는 `chain.filters`는 가변 ArrayList — in-place 교체 가능(실측).

## B-2. 구현

- **V9__spring_session.sql**: spring-session 3.4.2 공식 postgres 스키마 그대로:
  ```sql
  CREATE TABLE SPRING_SESSION (
      PRIMARY_ID CHAR(36) NOT NULL, SESSION_ID CHAR(36) NOT NULL,
      CREATION_TIME BIGINT NOT NULL, LAST_ACCESS_TIME BIGINT NOT NULL,
      MAX_INACTIVE_INTERVAL INT NOT NULL, EXPIRY_TIME BIGINT NOT NULL,
      PRINCIPAL_NAME VARCHAR(100),
      CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
  );
  CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
  CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
  CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);
  CREATE TABLE SPRING_SESSION_ATTRIBUTES (
      SESSION_PRIMARY_ID CHAR(36) NOT NULL, ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
      ATTRIBUTE_BYTES BYTEA NOT NULL,
      CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
      CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
          REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
  );
  ```
- application.yml: `spring.session.jdbc.initialize-schema: never` (timeout은 기본 유지 — 변경하지 말 것).
- **패스키 등록 옵션 우회** (B-1 블로커):
  - `passkey/CachedCreationOptionsRepository`: `PublicKeyCredentialCreationOptionsRepository` 구현 — 세션에는 랜덤 키 문자열만 저장, 옵션 객체는 인메모리 ConcurrentHashMap + TTL 5분(만료 정리는 접근 시 lazy + 기존 스케줄러에 정리 훅). load 시 세션 키로 조회, 소비 후 제거. 단일 인스턴스 전제 주석 명시.
  - SecurityConfig 체인 후처리 확장: `PublicKeyCredentialCreationOptionsFilter`를 동작 동일한 커스텀 필터(원본 로직 이식: POST /webauthn/register/options, 인증 확인, rpOperations.createPublicKeyCredentialCreationOptions, repo.save, JSON 응답)로 교체하고 `WebAuthnRegistrationFilter.setCreationOptionsRepository(커스텀)` 호출.
- **session/ 모듈**: `SessionManagementService`(목록: SessionView(publicId=세션ID SHA-256 hex 앞 16자, ip, browser, createdAt, lastActiveAt, current), revoke(publicId — **본인 세션 목록에서 매칭된 것만** deleteById), revokeOthers, revokeAll), `SessionController`(GET /api/sessions, DELETE /api/sessions/{publicId}, POST /api/sessions/revoke-others — 파괴 계열은 `@RequireRecentAuth`). **세션 ID 원문을 화면/API에 절대 노출하지 말 것**.
- `LoginFlowSupport.establishSecurityContext`: 세션 속성 `TASPA_CLIENT_IP`/`TASPA_USER_AGENT`(String, RequestClientInfo 재사용) 기록 — 로그인/재인증 시점만.
- **비밀번호 재설정 성공 시**: 기존 신뢰 기기 전체 폐기에 더해 해당 사용자 **모든 세션 deleteById** (계정 탈취 대응 완성).
- 계정 페이지 "활성 세션" 섹션: 목록(브라우저/OS 요약, IP, 마지막 활동, "현재 세션" 뱃지) + 개별 로그아웃 + "다른 모든 세션 로그아웃".
- **로그아웃 쿠키**: SecurityConfig logout의 `deleteCookies("JSESSIONID")` → `deleteCookies("SESSION")` 수정 (Spring Session 쿠키명).
- 문서: architecture.md(세션 저장 구조, 재시작에도 세션 생존 — 사고 시 일괄 무효화 runbook: TRUNCATE SPRING_SESSION), README 기능 표, CLAUDE.md.

## B-3. 테스트 이행 (가장 큰 비용 — 반드시 수행)

springSessionRepositoryFilter가 MockMvc에 적용되면 **기존 통합테스트의 MockHttpSession 공유 패턴이 무력화**된다(전달한 세션 무시, 요청마다 새 세션). 대응:
- `IntegrationTestBase`에 쿠키 기반 헬퍼 추가: 응답의 `SESSION` 쿠키를 캡처해 후속 요청에 `.cookie(...)`로 재전송하는 체인 헬퍼 (예: `class WebSession { fun perform(builder): ResultActions }` 래퍼).
- MockHttpSession을 쓰는 **모든 기존 통합테스트**(GoogleLoginFlow/Passkey*/StepUp/TrustedDevice/MagicLink/SocialLoginFlow/AccountLockout/PasswordReset 등 — grep으로 전수 확인)를 쿠키 방식으로 일괄 전환. 이 전환 자체가 세션 직렬화 검증을 겸한다.
- spring-security-test의 `oauth2Login()`/`csrf()` 헬퍼는 그대로 동작.

## B-4. 신규 테스트 (Stage B)

1. 세션 목록: 로그인 2회(쿠키 2벌) → findByPrincipalName 2건, 현재 세션 식별, IP/UA 속성 표시
2. 원격 로그아웃: 세션 A에서 B를 revoke → B 쿠키 요청이 미인증 처리. 타 사용자 publicId revoke 시도 → 404/403
3. revoke-others + 비밀번호 재설정 시 전체 세션·신뢰 기기 소멸
4. **패스키 등록 왕복**: JDBC 세션 하에서 /webauthn/register/options → (커스텀 repo 경유) 등록 성공 — 직렬화 예외 회귀 방지. 패스키 로그인 옵션도 왕복
5. 재시작 생존: 세션 행 유지 확인(새 컨텍스트 불필요 — DB 행 존재와 쿠키 재사용 검증으로 충분)
- e2e: `session-management.spec.ts` — 브라우저 컨텍스트 2개 동일 계정 로그인 → 계정 페이지에 세션 2개 → 하나에서 "다른 모든 세션 로그아웃" → 다른 컨텍스트 다음 이동 시 로그인 페이지로. 기존 e2e(passkey/trusted-device/magic-link) 무손상 확인
