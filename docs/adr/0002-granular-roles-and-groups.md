# ADR 0002. 세분화 역할/그룹 모델 설계 노트

- 상태: 제안(Proposed) — **설계 노트 전용, 미구현**
- 날짜: 2026-07-18
- 관련: Stage 7(기능 공백 보완). 요건이 확정되지 않아 구현하지 않고 선택지·트레이드오프만 기록한다.

## 맥락(Context)

현재 taspa 의 권한 모델은 **전역 2단계(`USER` / `ADMIN`)** 다.

- 저장: `users.role`(VARCHAR 16, 단일 값) — `UserRole.USER` 또는 `UserRole.ADMIN`.
- 인가: `LoginUserDetailsService` 가 `ROLE_{role}` 하나를 authority 로 부여하고,
  `SecurityConfig` 가 `/admin/**`·`/api/admin/**`·`/actuator/prometheus` 를 `hasRole("ADMIN")` 으로 막는다.
- 토큰: `TokenCustomizerConfig` 는 **역할을 토큰에 싣지 않는다**. access_token 에는 `sub`·`scope` 만,
  id_token 에는 scope 기반 PII 클레임(email/profile)만 들어간다. 즉 **역할은 taspa 내부(관리 콘솔) 인가에만
  쓰이고, 연동된 리소스 서버로 전파되지 않는다.**

이 모델의 한계:

1. **전역 단일 역할**: 사용자는 taspa 전체에서 하나의 역할만 가진다. "A 서비스에서는 편집자, B 서비스에서는
   뷰어" 같은 **클라이언트(서비스)별 역할**을 표현할 수 없다.
2. **역할 전파 부재**: 리소스 서버가 사용자 권한으로 인가를 하려면 스스로 매핑 테이블을 두어야 한다. 중앙
   IdP 의 장점(정책 단일화)이 인가 영역에는 적용되지 않는다.
3. **그룹/조직 개념 없음**: 부서·팀·조직(기업 SSO, Stage E 의 `sso_connections`) 단위로 권한을 묶어 관리할
   수 없다. 개별 사용자마다 역할을 직접 지정해야 한다.
4. **확장 시 스키마 경직**: 단일 컬럼이라 역할을 하나 더 추가하려면 값 컨벤션(문자열)에 의존하게 되고,
   다대다(사용자↔역할)로 가는 순간 컬럼 모델이 깨진다.

요건(어떤 서비스가, 어떤 역할 세분도를, 어떤 전파 형태로 필요로 하는지)이 아직 불명확하므로 **지금 구현하지
않는다.** 대신 확장이 필요해질 때의 선택지를 아래에 정리한다.

## 대안(Options)

### A. per-client 역할 클레임(RegisteredClient scope ↔ role 매핑)

각 클라이언트가 요청하는 scope 에 따라 토큰에 역할 클레임을 넣는다.

- 모델: `client_roles(registered_client_id FK, user_id FK, role)` 다대다 테이블. 또는 그룹을 경유(대안 C).
- 발급: `TokenCustomizerConfig` 에서 `context.registeredClient` + `authorizedScopes` 를 보고, 예컨대
  `roles` scope 가 승인됐을 때만 `roles`(또는 `resource_access` 형태) 클레임을 access_token 에 추가.
  클라이언트별로 다른 역할 집합을 내려줄 수 있다.
- 리소스 서버: `spring-boot-starter`(`TaspaResourceServerAutoConfiguration`)에 authorities 변환기를 추가해
  `roles` 클레임 → `ROLE_*` 로 매핑, `@PreAuthorize("hasRole(...)")` 사용.
- 장점: 표준 OAuth2 흐름 그대로, 서비스별 역할 표현 가능, 중앙에서 통제·감사.
- 단점: 토큰 비대(역할 많을수록), 역할 변경이 **토큰 수명 동안 반영 안 됨**(단수명 access_token + 회전으로
  완화), scope↔role 매핑 관리 UI(관리 콘솔) 필요.
- scope↔role 매핑 접근: (1) 정적 — 클라이언트 등록 시 "이 클라이언트가 받을 수 있는 역할 화이트리스트"를
  `RegisteredClient` 설정(ClientSettings 커스텀 or 별도 테이블)에 둔다. (2) 동적 — 사용자별 `client_roles`
  행에서 실제 부여 역할을 조회. 둘을 교집합해 **"클라이언트가 허용된 역할 ∩ 사용자가 가진 역할"** 만 토큰에
  싣는 것이 최소권한 원칙에 부합한다.

### B. 그룹 기반(사용자→그룹→역할)

역할을 직접 부여하지 않고 **그룹** 멤버십으로 간접 부여한다.

- 모델: `groups(id, name, description)`, `user_groups(user_id, group_id)`, `group_roles(group_id, role,
  registered_client_id NULL 가능)`. 조직(기업 SSO)·부서를 그룹으로 매핑.
- 발급: 사용자의 그룹 → (클라이언트 필터) → 역할 집합을 계산해 클레임화. `groups` 클레임을 별도로 내려
  리소스 서버가 자체 매핑하게 할 수도 있다.
- 장점: 대규모 사용자 권한을 그룹 단위로 일괄 관리(입·퇴사, 부서 이동), 기업 SSO JIT 프로비저닝과 자연스럽게
  결합(IdP 그룹/속성 → taspa 그룹). 감사·회수가 그룹 단위로 단순.
- 단점: 간접성으로 "이 사용자의 유효 권한"을 계산하는 로직·디버깅이 복잡, 그룹 폭발·중첩(그룹의 그룹) 관리
  비용, 유효 권한 계산 캐싱 필요.

### C. 조직 역할(멀티테넌시) — 조직 스코프 역할

Stage E 의 `sso_connections`(조직) 을 1급 테넌트로 승격하고 역할을 **조직 스코프**로 둔다.

- 모델: `org_memberships(user_id, org_id, role)`. 사용자는 여러 조직에 각기 다른 역할로 소속.
- 발급: 활성 조직 컨텍스트(로그인 시 선택 or 도메인 기반)에 따라 `org`·`org_roles` 클레임.
- 장점: B2B SaaS 의 표준 멀티테넌시 모델, 기업 SSO 와 정합성 최고, 조직 관리자에게 위임 관리(delegated
  admin) 가능.
- 단점: 가장 큰 스키마·UX 변경(조직 전환 UI, 조직별 관리 콘솔), taspa 를 순수 IdP 이상으로 확장(테넌시
  플랫폼화)하는 방향이라 범위가 크다.

## 트레이드오프 요약

| 축 | A. per-client 역할 | B. 그룹 | C. 조직 역할 |
| --- | --- | --- | --- |
| 서비스별 역할 | O | O(그룹→클라이언트 필터) | △(조직 내 서비스) |
| 대량 관리 | △ | O | O |
| 기업 SSO 정합 | △ | O | ◎ |
| 구현 범위 | 소~중 | 중 | 대 |
| 토큰 크기/전파 | 커질 수 있음 | 중간(그룹만 내리면 작음) | 중간 |
| 즉시 회수 | 토큰 수명 지연 | 토큰 수명 지연 | 토큰 수명 지연 |

공통 제약: **JWT 는 상태가 없어 역할 변경이 토큰 만료까지 반영되지 않는다.** 즉시 회수가 필요하면
단수명 access_token + refresh rotation(현재 채택)에 더해, 민감 리소스는 introspection 또는 서버측 세션
확인을 병행해야 한다. 역할을 토큰에 싣는 순간 이 지연이 인가 전반에 적용됨을 리소스 서버 문서에 명시할 것.

## 권고(비결정)

- **기본 입장: 확정된 요건이 나오기 전까지 현행 2단계 유지.** 관리 콘솔 인가에는 충분하며, 불필요한 복잡도·
  토큰 비대·마이그레이션 리스크를 피한다.
- 첫 확장 트리거가 "특정 서비스가 자기 사용자에게 역할을 주고 싶다"이면 **대안 A(per-client 역할 + scope↔role
  매핑)** 가 최소 침습이다. `roles` scope 를 도입하고, 클라이언트 등록에 역할 화이트리스트를 추가한 뒤,
  `TokenCustomizerConfig` 에서 "클라이언트 허용 역할 ∩ 사용자 보유 역할"만 access_token 에 싣는다.
- 확장 트리거가 "조직/부서 단위 권한 관리"이면 기업 SSO(Stage E) 와 묶어 **대안 B→C** 로 진화시킨다.
  이 경우 `user_groups`/`org_memberships` 를 도입하고 JIT 프로비저닝에서 IdP 속성→그룹 매핑을 채운다.
- 어느 경로든 **역할의 진실 원천은 taspa DB** 로 두고 토큰은 파생물로만 취급한다(리소스 서버가 역할을
  자체 저장·변형하지 않게 하여 중앙 통제·감사를 유지).

## 결과(Consequences)

- 본 ADR 은 **구현을 수반하지 않는다.** 스키마·엔드포인트·토큰 클레임 변경 없음. 기존 인가 동작(2단계)은
  그대로다.
- 향후 세분화 요건이 확정되면 이 문서의 대안 중 하나를 골라 별도 ADR(구현 결정)로 승격하고, 마이그레이션
  (`users.role` → 다대다/그룹)과 리소스 서버 연동 가이드를 함께 작성한다.
