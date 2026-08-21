# ADR 0001. 중앙 IdP + 표준 OIDC 채택

- 상태: 채택(Accepted)
- 날짜: 2026-07-17

## 맥락(Context)

여러 프로젝트가 각자 사용자 인증을 구현하고 있어 다음 문제가 발생한다.

- 계정/비밀번호 정책, 세션, 토큰 검증 로직이 서비스마다 중복 구현된다.
- 보안 수정(예: 해시 강도, 잠금 정책)을 모든 서비스에 개별 반영해야 한다.
- 사용자는 서비스마다 별도 계정을 만들어야 하고 SSO가 불가능하다.
- auth-playground는 단일 앱용 세션 인증(`X-Session-Token`)이라 다중 서비스로 확장하기 어렵다.

인증을 공용화하는 방식으로 세 가지 대안을 검토했다.

## 대안(Options)

### A. 공유 인증 라이브러리 배포
각 서비스가 공용 라이브러리를 의존성으로 포함해 동일한 인증 코드를 사용.

- 장점: 초기 도입이 단순, 네트워크 홉 없음.
- 단점: 언어/프레임워크에 종속, 버전 파편화(각 서비스가 서로 다른 버전 사용), 비밀키/세션 저장소를 공유해야 해 결합도 상승, 보안 패치가 여전히 N개 배포를 요구.

### B. 각 서비스가 자체 구현
현행 유지.

- 장점: 서비스별 자유도.
- 단점: 중복/불일치 최대, 보안 사고 표면 확대, SSO 불가. 사실상 문제의 원인.

### C. 중앙 IdP + 표준 OAuth2/OIDC (채택)
taspa를 표준 OAuth2 Authorization Server / OIDC Provider로 운영하고, 각 서비스는
Resource Server로서 JWT를 검증만 한다.

- 장점:
  - 표준 프로토콜 → 언어/프레임워크 무관하게 어떤 클라이언트도 연동 가능.
  - 인증/키/정책/감사를 단일 지점에서 통제, 보안 패치 1회로 전파.
  - SSO, 토큰 회전, 스코프 기반 인가 등 성숙한 생태계 활용.
  - Spring Authorization Server로 검증된 구현 재사용.
- 단점:
  - IdP가 단일 장애점(SPOF) → 가용성 설계 필요.
  - 초기 구축 복잡도(디스커버리, JWKS, 키 관리)가 라이브러리 방식보다 높음.

## 결정(Decision)

**대안 C(중앙 IdP + 표준 OIDC)를 채택한다.** 구현은 Spring Authorization Server를 사용하고,
Authorization Code + PKCE를 기본 인가 방식으로, JWT access token(단수명) + refresh token
rotation을 토큰 전략으로 삼는다. 클라이언트 등록은 JDBC(`JdbcRegisteredClientRepository`)로
영속화한다. auth-playground의 코드 컨벤션과 보안 패턴(비밀번호 정책, bcrypt, 계정 잠금,
Testcontainers 테스트)은 계승한다.

## 결과(Consequences)

- 신규/기존 서비스는 표준 OIDC 클라이언트 설정만으로 taspa에 연동한다(자체 인증 제거).
- Spring 서비스는 `client/spring-boot-starter`로 검증을 자동 구성한다.
- 서명 키(JWK)와 감사(audit)는 IdP가 소유한다. 단, 현재 키는 메모리 생성이므로
  **DB 영속화 + 회전**을 Phase 2 필수 과제로 둔다(재시작 시 토큰 무효화 방지).
- IdP 가용성/확장성(수평 확장, 세션 저장소 외부화)이 신규 운영 과제로 추가된다.
- SPOF 완화를 위해 향후 다중 인스턴스 + 공유 키 저장소 구성을 검토한다.
