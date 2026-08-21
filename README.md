# taspa

**모든 프로젝트가 공용으로 사용하는 중앙 인증 시스템(IdP).**

taspa는 표준 OAuth2 / OpenID Connect(OIDC) Provider로서, 여러 서비스가 각자 인증을 구현하지 않고
하나의 신뢰 지점(Identity Provider)에 위임하도록 설계한 중앙 인증 서버다.
검증된 학습 프로젝트 [auth-playground](../auth-playground)의 코드 컨벤션과 보안 패턴
(비밀번호 정책, bcrypt 타이밍 방어, 계정 잠금, Testcontainers 기반 통합 테스트)을 계승한다.

## 왜 중앙 IdP인가

- **단일 계정, 다중 서비스**: 사용자는 taspa 계정 하나로 모든 사내/제품 서비스에 로그인(SSO)한다.
- **표준 준수**: OAuth2 Authorization Code + PKCE, OIDC를 사용하므로 어떤 언어/프레임워크의 클라이언트도 붙일 수 있다.
- **보안 일원화**: 토큰 발급/검증, 키 관리, 감사(audit), 계정 정책을 한 곳에서 통제한다.

## 인증 경험 (구글 계정 로그인과 동일한 흐름)

브랜딩은 taspa 고유(텍스트 워드마크)지만, 사용자 경험은 구글 계정 로그인과 동일하게 설계했다.

- **identifier-first 2단계 로그인**: `/login`에서 이메일 입력 → "다음" → 비밀번호 페이지(상단에 이메일 칩 표시).
- **패스키(WebAuthn) 로그인**: 패스키 보유 계정은 이메일 입력 후 "본인임을 확인" 화면(지문/얼굴/화면 잠금)으로 우선 유도. `/login`에서 이메일 없이 "패스키로 로그인"(usernameless)도 지원. 패스키는 소유+생체 요소를 충족하므로 MFA·이메일 게이트를 생략한다.
- **소셜 로그인(구글·카카오·네이버)**: 환경변수로 자격 증명이 설정된 공급자만 버튼 노출. 검증된 이메일끼리만 자동 연결(그 외에는 이메일 코드로 본인 확인 후 연결), 소셜 로그인도 로컬 MFA 게이트 적용. 계정 페이지에서 연결/해제(마지막 로그인 수단은 해제 차단). 설정 방법은 [docs/social-login-setup.md](docs/social-login-setup.md).
- **기업 SSO(SAML 2.0 · 조직 OIDC)**: 회사 IdP 로 로그인. 이메일 도메인 매칭 시 IdP 로 자동 라우팅(HRD, `enforced`), 로그인 성공 시 **공급자 이메일 도메인 == 커넥션 verified 도메인** 강제(계정 탈취 차단), JIT 프로비저닝. 로컬 MFA 게이트 유지(커넥션별 `trust_idp_mfa` 로 스킵 가능). 관리 콘솔 `/admin/sso` 에서 커넥션·도메인 관리. 설정 방법은 [docs/enterprise-sso-setup.md](docs/enterprise-sso-setup.md).
- **TOTP 2단계 인증(MFA)**: 활성 계정은 비밀번호 통과 후 6자리 코드 페이지. "다른 방법 시도"로 백업 코드 입력 전환.
- **신뢰 기기(MFA 30일 스킵)**: MFA 화면의 "이 기기에서 30일 동안 묻지 않음" 체크 시 신뢰 기기 쿠키 발급(해시 저장·사용 시 회전·만료 고정 30일). 비밀번호 재설정/MFA 재설정 시 전체 폐기, 계정 페이지에서 개별/전체 해제.
- **로그인 알림**: 최근 30일 이력에 없는 새 (IP, 브라우저/OS) 로그인은 "새 로그인이 감지되었습니다" 메일 발송(같은 기기 재로그인은 재발송 억제).
- **Step-up 재인증**: MFA/패스키/소셜 연결/신뢰 기기 관리 같은 민감 작업은 최근 10분 내 재인증(auth_time)이 없으면 `/reauth`(비밀번호 또는 패스키)로 본인 확인 후 진행.
- **매직 링크 로그인**: 비밀번호 페이지의 "이메일로 로그인 링크 받기" → 15분 단일 사용 링크 → 랜딩 페이지에서 "로그인" 버튼 확정(스캐너 선클릭 방지). MFA 게이트는 유지.
- **이메일 인증**: 가입 직후 6자리 코드 발송 → 인증 완료 시 자동 로그인. 미인증 계정은 로그인 시에도 인증 게이트를 통과해야 한다.
- **비밀번호 재설정**: "비밀번호를 잊으셨나요?" → 이메일 링크 → 새 비밀번호 설정.
- **계정 잠금**: 5회 실패 시 30분 잠금(auth-playground 이식).
- **OAuth 동의 화면**: 클라이언트가 scope를 요청하면 구글식 동의 화면("demo-app이(가) 다음을 요청합니다: 이메일 주소 보기 …").
- **계정 페이지 `/account`**: 이메일/인증 상태 표시, MFA 설정(QR → 코드 확인 → 백업 코드 10개 1회 표시)/해제, 패스키 관리(만들기/이름 변경/삭제), 활성 세션 관리.
- **자기서비스 계정 관리**: 표시 이름 편집, 이메일 변경(새 주소로 인증코드 발송 → 확인 시 전환, 옛 주소 통지), 비밀번호 변경/설정(소셜 전용 계정은 최초 설정 — 변경 시 전 세션·신뢰기기 폐기), 계정 탈퇴(하드삭제로 PII 즉시 제거·동일 이메일 재가입 가능). 민감 작업은 step-up 재인증으로 보호. 이메일 변경 시 옛 이메일에 묶인 제3자 권한부여는 폐기되어 재동의를 요구한다.
- **연결된 앱(제3자 접근) 관리**: OAuth2 로 권한을 준 클라이언트 목록(부여 scope·최근 사용 시각)을 보고 개별 철회(활성 토큰 무효화). step-up 적용.
- **활성 세션 관리(원격 로그아웃)**: 세션을 DB(Spring Session JDBC)에 저장해 서버를 재시작해도 로그인이 유지된다. 계정 페이지 "활성 세션"에서 로그인된 브라우저/기기 목록(브라우저·IP·마지막 활동·현재 세션 뱃지)을 보고 개별 로그아웃 또는 "다른 모든 세션 로그아웃"을 할 수 있다. 비밀번호 재설정 시 모든 세션·신뢰 기기가 즉시 폐기된다.

> **핵심 보안 설계**: MFA·이메일 인증 대기(pending) 상태는 절대 `SecurityContext`에 저장하지 않는다.
> 부분 인증이 인증된 것으로 취급되면 `/oauth2/authorize`가 MFA 없이 authorization code를 발급하는 취약점이 생기기 때문이다.
> 자세한 흐름과 근거는 [docs/architecture.md](docs/architecture.md#7-구글-스타일-로그인-플로우)를 참고한다.

## Prerequisites

- JDK 21 — **JAVA_HOME 을 맞출 필요는 없다.** `gradle/gradle-daemon-jvm.properties` 가 데몬 JVM 을 21 로
  고정하므로, 기본 java 가 25든 17이든 Gradle 이 설치된 21 을 찾아 쓴다(없으면 그때 명확히 알려준다).
  이 파일이 없던 동안에는 기본 JDK 가 25 인 환경에서 **새 데몬이 뜨는 순간** `What went wrong: 25.0.2`
  라는 알 수 없는 메시지로 빌드가 깨졌다(Gradle 8.12 는 JDK 25 에서 동작하지 않는다).
- Docker & Docker Compose (로컬 PostgreSQL / 통합 테스트용)

## Quick Start

```bash
# 1. 인프라 실행 (PostgreSQL + Mailpit)
docker compose up -d postgres

# 2. 인증 서버 실행 (데모 클라이언트 시딩을 원하면 dev 프로파일 사용)
./gradlew :server:bootRun --args='--spring.profiles.active=dev'

# 3. (선택) SSO 데모 클라이언트 실행 → http://localhost:8080 에서 "taspa로 로그인"
./gradlew :examples:demo-client:bootRun
```

- OpenAPI 문서: http://localhost:9100/swagger-ui.html
- OIDC 디스커버리: http://localhost:9100/.well-known/openid-configuration
- JWKS: http://localhost:9100/oauth2/jwks

## 포트

| 서비스 | 포트 | 설명 |
|--------|------|------|
| taspa (IdP) | 9100 | 중앙 인증 서버 (OAuth2/OIDC) |
| PostgreSQL | 5433 | 메인 데이터베이스 (DB `taspa`) |
| Mailpit | 1025 (SMTP), 8025 (UI) | 이메일 테스트 서버 (Phase 2 이메일 검증용) |
| 클라이언트 앱 | 8080대 | taspa에 붙는 서비스들 (관례) |

> IdP는 클라이언트 앱(8080대) 및 로컬 공용 인프라(minio 9000, postgres 5432 등)와 겹치지 않도록 9100/5433 포트를 사용한다.

## 모듈 구조

```
taspa/
├── server/                     # 중앙 인증 서버 (Spring Boot 애플리케이션)
├── client/spring-boot-starter/ # Spring 클라이언트가 taspa JWT를 검증하기 위한 스타터(라이브러리)
├── examples/demo-client/       # taspa 를 IdP 로 쓰는 OIDC 로그인 데모 앱 (8080)
└── e2e/                        # Playwright e2e (로그인 플로우 + SSO)
```

- `:server` — 회원가입, 로그인(폼), OAuth2/OIDC 토큰 발급, JWKS 노출.
- `:client:spring-boot-starter` — 다른 Spring 앱이 의존성 하나로 taspa 발급 JWT를 검증(Resource Server)하도록 자동설정 제공.
- `:examples:demo-client` — dev 시딩 클라이언트(`demo-app`)로 로그인·동의·클레임 확인·SSO 를
  시연하는 최소 RP. 실행법·검증 항목은 [examples/demo-client/README.md](examples/demo-client/README.md).

자세한 통합 방법은 [docs/integration-guide.md](docs/integration-guide.md)를 참고한다.

## 관리 콘솔

`/admin` (ADMIN 역할 전용) — 대시보드(사용자·클라이언트·활성 세션 수, 최근 감사 이벤트),
OAuth2 클라이언트 관리(등록/수정/삭제/secret 재발급 — 기밀·공개 유형, secret 은 발급 시 1회만 표시),
사용자 관리(검색/상세/정지·해제/전 세션 종료/역할 변경 — 자기 자신 정지·강등은 차단),
기업 SSO 커넥션 관리(`/admin/sso` — SAML·조직 OIDC 커넥션 CRUD, 도메인 매핑·검증 토글, SP 메타데이터/ACS URL 표시),
조직 관리(`/admin/orgs` — 조직 CRUD·정지·멤버 역할/제거·타임존),
캘린더 관리(`/admin/calendar` — iCalendar 피드 등록/동기화/삭제, SSRF 방어 경유),
감사 로그 조회. 모든 변경 작업은 최근 재인증(step-up)과 CSRF 토큰을 요구하며 감사(`ADMIN_*`)로 기록된다.

**소비 이벤트 seam**(`/api/orgs/{orgId}/consumption-events`) — 결제(생산자)와 예측(소비자)을 분리하는
append-only 정답데이터 로그. 적재는 org 결속 M2M 토큰(`meal.consumption.write`, 멱등키는 org 범위)만,
조회는 **집계 카운트만**(`meal.consumption.read`/`.read.all` — 개별 이벤트·개인 라벨 미노출). 생산자 클라이언트는
등록 시 org 에 결속해 `org_id` 클레임을 발급받는다.

**첫 관리자 지정** — 둘 중 하나:

```yaml
# application.yml (또는 환경변수 TASPA_ADMIN_EMAILS_0)
taspa:
  admin:
    emails:
      - you@example.com   # 기동 시 해당 계정이 존재하면 ADMIN 으로 승격
```

```sql
-- 또는 SQL 로 직접 지정
UPDATE users SET role = 'ADMIN' WHERE email = 'you@example.com';
```

역할은 로그인 시점의 세션에 반영되므로 승격 후에는 다시 로그인해야 한다.

## 리스크 기반 인증

비밀번호 로그인에만 적용되는 적응형 챌린지(패스키·소셜·매직 링크는 수단 특성상 면제 —
`docs/architecture.md` §8.10). 신규 환경(90일 내 이력 없는 ip/기기)·실패 누적·급격한 IP 변화를
조합해 MEDIUM 이상이면 MFA 사용자는 신뢰 기기 스킵을 무시하고 MFA 를, MFA 미등록 사용자는
이메일 코드 본인 확인(`/login/risk-challenge`)을 요구한다. HIGH 는 보안 경고 메일을 추가 발송.
`taspa.risk.enabled=false` 로 끌 수 있다.

## 빌드 / 테스트

```bash
./gradlew build          # 전체 빌드 (통합 테스트는 Docker 필요)
./gradlew build -x test  # 컴파일만 검증 (Docker 없이)
./gradlew :server:test   # 서버 통합 테스트 (Testcontainers → Docker 필요)
```

### CI

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) — push(main/master)·PR 마다 자동 실행.

- **build-and-test**: `./gradlew build` — 서버 통합 테스트(Testcontainers + WireMock, 515개), ktlint
  정적분석, kover 커버리지 리포트, 전 모듈 컴파일을 GitHub 호스팅 러너(Docker 내장)에서 검증. 테스트/
  ktlint/커버리지 리포트는 성공·실패 무관하게 아티팩트로 업로드된다.
- **docker-build**: build-and-test 성공 후에만 `docker build`로 이미지 빌드 검증(레지스트리 push 는
  아직 미구성 — 워크플로 내 주석 참고).
- 정적분석(ktlint)은 현재 **non-blocking**(기존 코드 위반 다수라 빌드를 깨뜨리지 않음)이고, 커버리지
  (kover)는 리포트 생성까지만 하며 임계 게이트는 아직 없다.

## 프로덕션 배포

프로덕션은 `prod` 프로파일로 기동한다. dev 편의 기본값(localhost issuer, dev DB 비밀번호, DEBUG 로깅)은
`prod` 에서 전부 비활성화되고, 시크릿·외부 엔드포인트는 **환경변수 주입이 강제**된다(미주입/안전하지 않은 값 →
`ProductionSafetyValidator` 가 기동을 fail-fast).

**컨테이너 이미지 빌드**

```bash
docker build -t taspa-server:latest .          # 멀티스테이지(JDK21 빌드 → JRE21 런타임, non-root, healthcheck)
# 로컬 풀스택 데모(dev 프로파일 — compose 가 명시 오버라이드): docker compose --profile app up
```

이미지는 `SPRING_PROFILES_ACTIVE=prod` 를 **기본값으로 내장**한다(Dockerfile). 프로파일이 빠지면 CSP·HSTS·
rate limit·graceful shutdown·SMTP 타임아웃·XFF 신뢰·issuer https 검증이 **전부 꺼진 채로 정상 기동**하고
`ProductionSafetyValidator` 조차 생성되지 않으므로, 이미지에 고정해 그 실수를 구조적으로 차단한다.

**실행 예시**

```bash
docker run -d --name taspa -p 9100:9100 \
  -e DB_URL='jdbc:postgresql://db:5432/taspa' -e DB_USERNAME=taspa -e DB_PASSWORD="$DB_PASSWORD" \
  -e TASPA_ISSUER_URI='https://id.example.com' \
  -e TASPA_TRUSTED_PROXIES='10\.0\.0\.\d+' \
  -e TASPA_WEBAUTHN_RP_ID='id.example.com' \
  -e TASPA_WEBAUTHN_ALLOWED_ORIGINS='https://id.example.com' \
  -e MFA_ENCRYPTION_KEY="$MFA_ENCRYPTION_KEY" \
  -e TASPA_JWK_ENCRYPTION_KEY="$TASPA_JWK_ENCRYPTION_KEY" \
  -e MAIL_HOST='smtp.example.com' \
  -e TASPA_METRICS_SCRAPE_PASSWORD="$METRICS_PASSWORD" \
  taspa-server:latest
```

**필수 환경변수(prod)** — 미설정 시 기동 실패:

| 변수 | 예시 | 설명 |
|------|------|------|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://db:5432/taspa` | 외부 관리형 Postgres. 비밀번호가 dev 기본값 `taspa` 면 기동 중단 |
| `TASPA_TRUSTED_PROXIES` | `10\.0\.0\.\d+\|172\.1[6-9]\..*` | 신뢰 프록시(LB/인그레스) IP 대역 정규식. `forward-headers-strategy=native` 의 `internal-proxies` — 이 대역의 XFF 만 remoteAddr 재작성에 반영(그 외 출발지 XFF 는 무시) |
| `TASPA_ISSUER_URI` | `https://id.example.com` | OIDC issuer(발급 토큰 iss·디스커버리에 노출). https·비-localhost 강제 |
| `TASPA_WEBAUTHN_RP_ID` | `id.example.com` | 패스키 RP ID(접속 도메인과 일치해야 함) |
| `TASPA_WEBAUTHN_ALLOWED_ORIGINS` | `https://id.example.com` | 패스키 허용 오리진(쉼표 구분, https 강제) |
| `MFA_ENCRYPTION_KEY` | `openssl rand -base64 32` | TOTP 시크릿(`users.mfa_secret_encrypted`) + 기업 SSO 커넥션 시크릿(`sso_connections.oidc_client_secret_encrypted`) 암호화 키 |
| `TASPA_JWK_ENCRYPTION_KEY` | `openssl rand -base64 32` (MFA 키와 **다른 값**) | JWT 서명 개인키 암호화 키 |
| `MAIL_HOST` | `smtp.example.com` | 실제 SMTP(포트/계정/STARTTLS·타임아웃은 `MAIL_*` 로 조정) |

선택: `TASPA_PUBLIC_BASE_URL`(기본=issuer), `DB_POOL_SIZE`(기본 20), `MAIL_PORT`/`MAIL_USERNAME`/
`MAIL_PASSWORD`/`MAIL_SMTP_*`.

위 표의 변수가 **하나라도 비어 있으면 기동 최초 단계에서 멈추고, 빠진 것을 한 번에 전부** 알려준다
(`RequiredProdEnvValidator`). 그전에는 스프링의 플레이스홀더 해석이 먼저 터져 배포자가 받는 것이
`Error processing condition on MailSenderAutoConfiguration` 로 시작하는 스택트레이스였다 — 진짜 원인은
맨 아래 `Caused by` 한 줄에 묻히고, 나머지 누락 변수는 알려주지 않아 **하나 고칠 때마다 다음 변수에서
다시 죽었다**. 값의 안전성(https·비-localhost·키 강도·두 키 분리)은 그 다음 단계에서
`ProductionSafetyValidator` / `EncryptionConfig` 가 이어서 검사한다.

**암호화 키 생성** — 두 키는 각각 따로 뽑는다:

```bash
export MFA_ENCRYPTION_KEY="$(openssl rand -base64 32)"
export TASPA_JWK_ENCRYPTION_KEY="$(openssl rand -base64 32)"
```

키는 salt 없는 SHA-256 1회로 AES 키가 되므로(`AesEncryptionService`) **원문 문자열 자체가 곧 공격 대상**이다.
그래서 prod 기동 시 `EncryptionConfig` 가 빈 값·dev 기본값·32자 미만·반복 패턴(서로 다른 문자 10종 미만)을
거부하고, **두 키가 같은 값이면(= JWK 키 미설정으로 MFA 키에 폴백한 경우 포함) 기동을 중단**한다 —
키를 나눈 목적이 한쪽 유출의 파급 차단인데 같은 값이면 TOTP 시크릿 유출이 곧 서명 개인키 유출이 된다.

> **이미 두 키를 같은 값(또는 JWK 키 미설정)으로 운영 중이라면 이 규칙에 걸려 기동이 막힌다.**
> 키만 바꿔 재기동하면 기존 `jwk_keys` 행이 전부 복호화 불가가 되어 토큰 발급·JWKS 가 전면 장애다 —
> 재암호화 스크립트 또는 다운타임 후 재부트스트랩 중 하나를 반드시 거쳐야 한다.
> 절차: [docs/architecture.md §3.1 "암호화 키 전환 절차"](docs/architecture.md#31-서명-키-수명주기-active--retired--삭제).

**메트릭 스크레이핑** — `TASPA_METRICS_SCRAPE_PASSWORD`(+선택 `TASPA_METRICS_SCRAPE_USERNAME`, 기본 `metrics`)를
설정하면 `/actuator/prometheus` 전용 HTTP Basic 체인이 켜진다(`MetricsSecurityConfig`, STATELESS — 스크레이프가
세션을 만들지 않는다). 이 자격증명은 앱 사용자 저장소와 분리돼 `ROLE_METRICS` 만 가지며 로그인·콘솔에는 통하지
않는다. **미설정이면 그 경로는 ADMIN 세션 전용이라 스크레이퍼가 인증할 수단이 없다**(= 메트릭 수집 불가).

**배포 매니페스트** — `deploy/docker-compose.prod.yml`(+ `deploy/.env.prod.example`). 루트
`docker-compose.yml` 은 개발용이다(약한 비밀번호·Mailpit·dev 프로파일); 이쪽은 외부 관리형 Postgres·
고정 이미지 태그·시크릿 주입·`restart: unless-stopped`·로컬호스트 바인딩(앞단 프록시가 TLS 종단)을 전제한다.

```bash
cp deploy/.env.prod.example deploy/.env.prod     # 값 채우기 (커밋 금지 — .gitignore)
docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml up -d
```

> ★**web 이미지는 `TASPA_ORIGIN` 을 빌드 인자로 받아야 한다.** Next 의 rewrites 목적지는 빌드 시각에
> `.next/routes-manifest.json` 으로 굳어 **런타임 환경변수로는 바뀌지 않는다**. 기본값으로 빌드된
> 이미지를 배포하면 프록시가 `http://localhost:9100`(= web 컨테이너 자신)을 가리켜 화면이 전부 401 이
> 된다 — 로그인은 되는데 아무 것도 안 되는, 원인 추적이 어려운 형태다.
> ```bash
> docker build -f web/Dockerfile --build-arg TASPA_ORIGIN=http://server:9100 -t taspa-web:0.0.1 ./web
> # 확인
> docker run --rm --entrypoint node taspa-web:0.0.1 -e \
>   "console.log(require('/app/.next/routes-manifest.json').rewrites.beforeFiles[0].destination)"
> ```

**배포 리허설** — `deploy/rehearsal/run.sh`. prod 이미지를 실제로 띄워 **빈 DB 마이그레이션 → 가입 →
메일 인증 코드 → 로그인 게이트 → 세션 API → 관리 콘솔 쓰기**까지 밟고, 보안 헤더·메트릭 인증·rate limit·
graceful shutdown·기동 로그 정적(WARN 0) 까지 확인한 뒤 자기 컨테이너만 정리한다. 릴리스 전에 한 번 돌린다.

```bash
deploy/rehearsal/run.sh              # 이미지까지 빌드
SKIP_BUILD=1 deploy/rehearsal/run.sh # 이미 빌드한 이미지로
```

> 왜 e2e 로 대신할 수 없나: Playwright 스위트는 dev 프로파일(데모 클라이언트 시딩·localhost issuer·약한
> 시크릿)을 전제한다. 실제로 이 리허설에서만 드러난 것들이 있다 — 필수 환경변수 하나가 비면 안내 대신
> 자동설정 스택트레이스가 나오던 문제, 공개 JSON 가입 API 가 인증 코드를 보내지 않던 문제,
> `docker compose --profile app` 이 주석과 달리 prod 로 떠서 기동조차 못 하던 문제. 셋 다 서버 통합
> 테스트와 e2e 를 **모두 통과한 상태**였다.

**prod 에서 자동 적용되는 하드닝**(`application-prod.yml`): SMTP 타임아웃, `forward-headers-strategy=native`
(+`TASPA_TRUSTED_PROXIES` 로 지정한 신뢰 프록시의 XFF 만 반영 — 프록시 뒤 Secure 쿠키·HSTS·정상 IP, XFF 스푸핑 차단),
graceful shutdown, 보안 헤더(CSP·HSTS·Referrer-Policy·frame-options),
인증 엔드포인트 rate limit, actuator `health,info,prometheus` 한정 노출 + liveness/readiness 프로브,
구조화(ECS JSON) 콘솔 로깅.

**롤백** — 이미지 태그를 되돌리는 것만으로는 안전하지 않다. Flyway 마이그레이션에는 **down 스크립트가
없고**(설계상 만들지 않는다 — 검증되지 않은 되돌리기 SQL 이 데이터를 잃는 가장 흔한 경로다), 새 버전이
적용한 스키마 위에서 옛 이미지가 돌게 된다.

- **되돌려도 되는 배포**: 마이그레이션이 **덧붙이기만** 한 경우(새 테이블·nullable 컬럼·새 인덱스).
  옛 코드는 새 컬럼을 모르고 지나가므로 그대로 돈다. 지금까지의 V1~V37 은 전부 이 형태다.
- **되돌리면 깨지는 배포**: NOT NULL 추가·컬럼/테이블 삭제·타입 변경·의미가 바뀌는 UPDATE.
  이 경우 롤백은 **이미지가 아니라 데이터 복구**의 문제이므로, 관리형 Postgres 의 PITR 로 돌린다
  (그 사이 발생한 결제·장부는 함께 사라진다 — 그래서 파괴적 변경은 expand-contract 로 두 배포에 나눠
  넣고, 그 사이에는 언제든 이미지만 되돌릴 수 있게 유지한다).
- 그래서 마이그레이션을 추가할 때 **어느 쪽인지 PR 에 적는다.** 덧붙이기면 "이미지 롤백 가능",
  파괴적이면 "롤백 불가 — expand 단계 먼저" 라고. 배포 시각에 판단하면 늦다.
- **web 이미지는 서버와 짝이다**(같은 커밋에서 빌드). 프록시 목적지가 빌드에 굳어 있고 SPA 가 부르는
  API 계약도 그 커밋 기준이므로, 한쪽만 되돌리지 말 것.

**운영 주의**
- **다중 레플리카·무중단 롤아웃에서는 마이그레이션을 앱 부팅과 분리한다(필수).** 배포 파이프라인의 선행
  잡/init 컨테이너에서 `flyway migrate` 를 끝내고, 앱은 `SPRING_FLYWAY_ENABLED=false` 로 뜬다. 이유는 두 가지다 —
  Flyway 를 앱 부팅에 태우면 스키마 변경 시간이 곧 기동 지연이자 무중단 배포의 구멍이 되고, 롤아웃 도중
  구/신 버전이 섞인 채로 마이그레이션이 도는 창이 생긴다. 다중 레플리카 동시 기동의 상호배제 자체는 Flyway 의
  PostgreSQL advisory lock 이 보장하지만, 그것은 "동시에 두 번 돌지 않는다"만 보장할 뿐 위 두 문제를 풀지 않는다.
  ★**단일 인스턴스 단일 호스트(`deploy/docker-compose.prod.yml` 의 전제)에서는 두 문제가 모두 발생하지
  않으므로 앱 부팅 Flyway 를 그대로 쓴다.** 그 매니페스트가 선행 마이그레이션 서비스를 넣지 않은 것은
  누락이 아니라 이 판단이다 — 같은 `compose up` 안에서 도는 선행 컨테이너는 "파이프라인 단계"가 아니라서
  분리했다는 착각만 주고 위 두 문제를 실제로는 풀지 않는다. 레플리카를 늘리는 순간 `.env.prod` 에
  `SPRING_FLYWAY_ENABLED=false` 를 넣고 마이그레이션을 파이프라인으로 옮길 것.
- **인덱스 마이그레이션 규약: 쓰기 경로 테이블에는 반드시 `CREATE INDEX CONCURRENTLY`.** 일반 `CREATE INDEX` 는
  빌드가 끝날 때까지 SHARE 락으로 그 테이블의 INSERT/UPDATE 를 막는다. 결제 장부(`meal_transactions`)나 소비 이벤트
  (`consumption_events`)처럼 실시간 쓰기가 들어오는 테이블에서는 인덱스 빌드 시간이 그대로 승인 정지 시간이 된다
  (2천만 행 실측: 빌드 6.8초 동안 INSERT 5.7초 대기). 지금은 두 테이블이 비어 있어 무해하지만, 규약으로 굳혀
  두지 않으면 다음 인덱스에서 그대로 반복된다. 체크리스트:
  1. 스크립트에 `CREATE INDEX CONCURRENTLY IF NOT EXISTS` 를 쓰고, 같은 이름의 사이드카 `.conf` 에
     `executeInTransaction=false` 를 둔다(`V30__merchant_grain_indexes.sql[.conf]` 가 표준 예시).
     인라인 `-- flyway:executeInTransaction=false` 주석은 **Flyway 10.20.1 이 읽지 않는다** — 사이드카가 유일한
     명시 수단이다(PostgreSQL 파서가 `^(CREATE|DROP)( UNIQUE)? INDEX CONCURRENTLY` 를 자동 감지하기도 하지만,
     그건 드라이버 플러그인 구현 세부라 명시가 안전하다).
  2. 그 스크립트에는 트랜잭션이 필요한 문장을 **섞지 않는다**. 섞으면 `mixed=false`(기본값)에서 마이그레이션이 실패한다.
  3. `spring.flyway.postgresql.transactional-lock: false` 를 유지한다(`application.yml`). 기본값 `true` 는
     마이그레이션 전체를 스키마 히스토리 연결의 트랜잭션(`pg_advisory_xact_lock`)으로 감싸는데, 그 연결이
     idle-in-transaction 으로 스냅샷을 붙들고 있어 `CONCURRENTLY` 빌드가 상대의 가상 xid 를 **영원히** 기다린다
     (신규 DB 전체 적용으로 재현 확인 — V30 에서 무한 정지). `false` 는 세션 스코프 `pg_advisory_lock` 을 쓰므로
     다중 인스턴스 상호배제는 그대로다.
  4. **실패 시 INVALID 인덱스가 남는다.** `CONCURRENTLY` 는 트랜잭션 밖이라 롤백이 없고, `IF NOT EXISTS` 는 이름만
     보므로 재실행이 조용히 건너뛴다 → 영원히 안 쓰이는 인덱스. 재시도 전 반드시 확인하고 지운다:
     ```sql
     SELECT c.relname, i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
      WHERE NOT i.indisvalid;
     DROP INDEX CONCURRENTLY IF EXISTS <해당 인덱스명>;
     ```
- 스케줄 잡은 리더 선출 없이 전 인스턴스에서 돈다. JWK 회전은 행 잠금(`findByStatusForUpdate`)으로, 보존 정리는
  멱등 DELETE 로 안전하다. **단 `OrgDomainReverifyJob` 은 실패 카운터를 잠금 없이 증가시켜(`reverifyFailures += 1`)
  다중 인스턴스에서 "3일 연속 실패" 임계를 하루 만에 넘길 수 있다** — 다중 인스턴스 전환 시 ShedLock/DB advisory
  lock 도입이 필요하다(단일 인스턴스는 무관).
- 다중 인스턴스 전 확인: 패스키 **등록** 옵션은 인메모리 TTL 캐시(`CachedCreationOptionsRepository`)라 sticky
  session 또는 공유 저장소가 필요하고(인증은 무관), rate limit 은 인스턴스별 카운팅이라 실효 한도가 인스턴스 수만큼
  희석된다(게이트웨이/공유 저장소로 승격 권장).
- `/actuator/prometheus` 는 익명 비허용 + ADMIN 역할 제한이 기본이며, 스크레이핑은 위의 전용 Basic 자격증명으로
  연다. 추가로 별도 관리 포트/네트워크 정책 뒤에 두는 것을 권장한다.
- 컨테이너 HEALTHCHECK 는 집계 `/actuator/health` 가 아니라 **`/actuator/health/liveness`** 를 본다. 집계 health 에는
  mail 기여자가 포함돼 SMTP 장애가 컨테이너 재시작으로 번지기 때문이며, prod 에서는 `management.health.mail.enabled=false`
  로 한 번 더 분리한다(메일 가용성은 발송 실패 로그·메트릭으로 관측).
- 백업/복구: `TASPA_JWK_ENCRYPTION_KEY` 를 잃으면 `jwk_keys`(암호화된 서명키)를 복호화할 수 없어 **발급된 전
  토큰 검증이 불가**하고, `MFA_ENCRYPTION_KEY` 를 잃으면 **전 사용자 TOTP 재등록 + 기업 SSO 커넥션 시크릿
  재입력**이 필요하다(토큰 검증은 무관 — 키 용도가 분리돼 있어 영향 범위도 갈린다). 정기 논리 백업(pg_dump)
  +PITR 와 시크릿 매니저 보관을 별도로 구성한다.

**로그 상관관계 · 구조화 로깅**

모든 HTTP 요청은 상관관계 ID 를 하나 갖는다(`CorrelationIdFilter`, MDC 키 `correlationId`). 클라이언트/LB 가
`X-Request-Id`(또는 `X-Correlation-Id`) 헤더를 보내면 **그 값을 이어 쓰고**, 없으면 서버가 발급한다. 어느 쪽이든
응답 헤더 `X-Request-Id` 로 같은 값이 돌아오므로 클라이언트 로그 ↔ 서버 로그를 이어 붙일 수 있다.
인바운드 값은 64자 이하 + `[A-Za-z0-9_-]` 만 통과시키고(로그 인젝션 차단) 위반 시 새로 발급한다. ID 는 무의미한
랜덤 UUID 로, 사용자 식별자를 담지 않는다.

```bash
curl -i -H 'X-Request-Id: 7f3c9a12b4de' https://id.example.com/actuator/health   # 응답에 X-Request-Id 그대로 반향
```

`prod` 프로파일은 콘솔 로그를 **ECS(Elastic Common Schema) JSON 한 줄**로 출력한다
(`logging.structured.format.console=ecs` — Spring Boot 3.4 내장, 추가 의존성 없음). MDC 가 최상위 필드로
실리므로 수집기에서 `correlationId` 로 요청 하나의 전체 로그를 검색할 수 있다. 선택적으로
`TASPA_LOG_SERVICE_ENVIRONMENT` 를 주면 `service.environment` 라벨이 붙는다.
dev/기본 프로파일은 사람이 읽는 평문을 유지하되 `[<correlationId>]` 를 함께 찍는다.

```json
{"@timestamp":"...","log.level":"WARN","service.name":"taspa","correlationId":"7f3c9a12b4de","log.logger":"com.taspa.server...","message":"..."}
```

## Roadmap

| 단계 | 범위 |
|------|------|
| **Phase 1** | 코어 계정(회원가입/로그인) + OAuth2 Authorization Code/PKCE + OIDC + JWKS |
| **Phase 2 (현재)** | ✅ 구글 스타일 로그인 UX(identifier-first), MFA(TOTP)+백업 코드, 이메일 인증, 비밀번호 재설정, 계정 잠금, OAuth 동의 화면, OIDC 클레임 커스터마이저, 패스키(WebAuthn) 로그인·관리, 소셜 로그인(구글·카카오·네이버)+계정 연결 관리, 신뢰 기기(MFA 30일 스킵), 로그인 알림, step-up 재인증, 매직 링크 로그인, JWK DB 영속화+키 회전, 감사 이벤트 DB 영속화, 세션 DB 영속화(Spring Session JDBC)+원격 세션 관리, 관리자 콘솔(클라이언트/사용자/감사 로그) |
| **Phase 3** | 클라이언트 SDK 확충(비 Spring 포함), 서비스 간 인증(client_credentials 정책) — 데모 클라이언트(`examples/demo-client`)·연동 레시피(Node/Next.js/SPA, [docs/integration-guide.md](docs/integration-guide.md)) 선행 완료. ✅ 기업 SSO 연동(SAML 2.0 · 조직 OIDC) — HRD·도메인 일치 강제·JIT([docs/enterprise-sso-setup.md](docs/enterprise-sso-setup.md)) |
| **Phase 4** | ✅ 리스크 기반 인증(Adaptive Auth) — 선행 구현(비밀번호 경로 적응형 챌린지, §리스크 기반 인증) |

## 문서

- [docs/architecture.md](docs/architecture.md) — 시스템 아키텍처, 토큰 전략, 계승 패턴
- [docs/social-login-setup.md](docs/social-login-setup.md) — 소셜 로그인(구글·카카오·네이버) 콘솔 등록·환경변수 설정
- [docs/enterprise-sso-setup.md](docs/enterprise-sso-setup.md) — 기업 SSO(SAML 2.0 · 조직 OIDC) 커넥션 등록·도메인 매핑·정책
- [docs/integration-guide.md](docs/integration-guide.md) — 새 프로젝트를 taspa에 붙이는 방법
- [docs/integration-roles.md](docs/integration-roles.md) — 조직 역할을 연동 서비스 인가에 전파하기(`org.roles` scope + 클라이언트 선언)
- [docs/iam-operations.md](docs/iam-operations.md) — IAM 정책을 안전하게 좁히는 순서, 자기 락아웃 방지 가드, 잠긴 뒤 DB 복구
- [docs/billing-operations.md](docs/billing-operations.md) — 돈 화면 운영(청구서·정산·지급·대사), 숫자가 정당하게 다른 경우
- [docs/adr/0001-central-idp-with-oidc.md](docs/adr/0001-central-idp-with-oidc.md) — 중앙 IdP + OIDC 채택 결정 기록
- [deploy/docker-compose.prod.yml](deploy/docker-compose.prod.yml) — 프로덕션 배포 매니페스트(+ `.env.prod.example`)
- [deploy/rehearsal/run.sh](deploy/rehearsal/run.sh) — 배포 리허설(prod 이미지로 실제 가입·로그인·관리까지 밟는다)

★**품질 게이트는 층이 다르고, 각 층이 잡는 결함도 다르다.** 앞 층이 전부 초록불인 상태에서 다음 층이
결함을 찾아낸 일이 반복해서 있었다 — 새 기능을 붙일 때 어느 층까지 확인했는지 스스로 물을 것.

| 층 | 명령 | 잡는 것 | 못 잡는 것 |
|----|------|---------|-----------|
| 서버 통합 | `./gradlew :server:test` | API 계약·인가·돈 계산 | 화면이 그 값을 어떻게 말하는지 |
| 프런트 단위 | `cd web && npx vitest run` | 순수 함수 불변식 | 렌더된 화면 |
| e2e | `cd e2e && npx playwright test` | 화면이 실제로 그리는 값 | 프로파일 차이(dev 전제) |
| UI 감사 | `UI_AUDIT=1 npx playwright test ui-audit` | 라우트별 넘침·콘솔 오류 | **화면 사이의 끊김** |
| 배포 리허설 | `deploy/rehearsal/run.sh` | prod 프로파일 전제 | 사용자 여정 |
| 여정 감사 | 페르소나가 실제 플로우를 걷는다 | 화면 사이의 끊김·막다른 길 | — |
