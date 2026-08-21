# IAM 정책 운영 — 잠기지 않고 권한 좁히기

플랫폼 관리자 권한을 좁히는 작업은 정당하고 자주 필요하다(담당자별 최소권한, 퇴사 준비, 사고 대응).
문제는 **인가 권위가 정책 엔진 하나뿐**이라는 점이다 — 잘못 저장한 Deny 는 그 정책을 지우는 능력까지
함께 없앤다. 이 문서는 그 상태를 만들지 않는 방법과, 만들어졌을 때 나오는 방법을 적는다.

## 1. 왜 위험한가 — 30초 설명

판정 규칙은 **명시적 Deny 우선**이고 정책 간 순서는 무관하다. 그래서

```json
{ "Effect": "Deny", "Action": "iam:*", "Resource": "*" }
```

한 줄이 붙는 순간, 그 사람은 **그 정책을 지울 수 없다.** 보안 체인의 `hasRole("ADMIN")` 은 심층 방어일
뿐 판정을 되돌리지 못한다. 모든 관리자에게 같은 것이 붙으면 콘솔로는 복구가 불가능하다.

## 2. 서버가 저장 전에 막는다 (`IamLockoutGuard`)

IAM 변경을 적용한 뒤 **커밋 전에**, 활성 플랫폼 관리자 중 아래를 **모두** 할 수 있는 사람이 최소 한 명
남는지 서버가 실제로 평가한다. 아무도 남지 않으면 `409 IAM_LOCKOUT` 으로 저장하지 않는다.

| 능력 | 없으면 |
|---|---|
| `platform:AccessConsole` (IAM 페이지) | 화면에 들어갈 수 없다 |
| `iam:ListPolicies` | 무엇이 문제인지 볼 수 없다 |
| `iam:UpdatePolicy` / `iam:DeletePolicy` | 문서를 고칠 수 없다 |
| `iam:DetachPolicy` | managed 부착을 뗄 수 없다 |
| `iam:RemoveInlinePolicy` | inline 정책을 뗄 수 없다 |

**문서를 정규식으로 검사하지 않는다** — 와일드카드·조건·그룹 경유·중첩 부착으로 반드시 새기 때문이다.
엔진에 실제로 물어본다.

### 알아 둘 성질

- **정지된(SUSPENDED) 관리자는 세지 않는다.** 로그인할 수 없으므로 복구 경로가 아니다.
- **한 명만 막는 것은 통과한다.** 다른 관리자가 되돌릴 수 있으면 정당한 운영이다.
- **자기를 먼저 막으면 다음 요청이 403 이다.** 가드가 아니라 인가 인터셉터가 막는다 — 이것도 정상이다.
  실제 사고는 반대 순서(남들 먼저, 자기 마지막)로 일어나고, 그 마지막 요청을 가드가 잡는다.
- **create 만 하는 것은 검사하지 않는다.** 부착·소속 전에는 아무 효과가 없다.

## 3. 안전하게 좁히는 순서

1. `/admin/iam` 의 **시뮬레이터**로 먼저 확인한다. 대상 사용자 + action + resource 를 넣고 ALLOW/DENY 를
   본다. 저장 전에 판정을 볼 수 있는 유일한 지점이다.
2. **자기 계정을 마지막에** 손댄다. 그래야 중간에 잘못돼도 되돌릴 손이 남는다.
3. Deny 는 **범위를 좁혀** 쓴다. `iam:*` 대신 실제로 막아야 할 action 만, `Resource:"*"` 대신 해당 TRN 만.
4. 복구 담당 관리자를 **한 명 정해 두고 그 계정은 건드리지 않는다**(break-glass 계정). 그 계정의 자격증명은
   평소 사용하지 않고 보관한다.

## 4. 이미 잠겼다면 — DB 복구

가드는 두 경우에 통과시킨다: (a) 활성 플랫폼 관리자가 아직 없을 때(부트스트랩 전), (b) **저장된 정책이
손상돼 아무도 평가할 수 없을 때**. (b) 는 의도된 탈출구다 — 그때 편집까지 막으면 손상된 정책을 지우는
것조차 불가능해진다. 그래서 잠긴 상태가 이론적으로 남을 수 있다.

> ⚠️ 아래는 애플리케이션을 우회하는 조작이다. 실행 전 스냅샷을 뜨고, 감사 기록이 남지 않으므로
> 변경 사유·시각·실행자를 별도로 남길 것.

### 4-1. 무엇이 막고 있는지 찾기

```sql
-- 특정 사용자에게 직접 붙은 inline 정책
SELECT name, document FROM iam_inline_policies
WHERE principal_type = 'USER' AND principal_id = '<사용자 UUID>';

-- 그 사용자에게 부착된 managed 정책
SELECT p.id, p.name, p.document
FROM iam_policy_attachments a JOIN iam_policies p ON p.id = a.policy_id
WHERE a.principal_type = 'USER' AND a.principal_id = '<사용자 UUID>';

-- 그룹 경유(그룹의 inline·부착도 함께 적용된다)
SELECT g.id, g.name FROM iam_group_members m JOIN iam_principal_groups g ON g.id = m.group_id
WHERE m.user_id = '<사용자 UUID>';
```

`document` 안의 `"Effect":"Deny"` 문장을 찾는다.

### 4-2. 최소한으로 되돌리기

정책을 지우기보다 **그 사람에게서 떼는 것**이 부수효과가 적다.

```sql
-- inline 제거
DELETE FROM iam_inline_policies
WHERE principal_type = 'USER' AND principal_id = '<사용자 UUID>' AND name = '<정책 이름>';

-- managed 부착 해제
DELETE FROM iam_policy_attachments
WHERE principal_type = 'USER' AND principal_id = '<사용자 UUID>' AND policy_id = '<정책 UUID>';

-- 그룹에서 빼기
DELETE FROM iam_group_members WHERE group_id = '<그룹 UUID>' AND user_id = '<사용자 UUID>';
```

### 4-3. 관리자가 아무도 없다면

```sql
UPDATE users SET role = 'ADMIN' WHERE email = '<복구할 이메일>' AND status = 'ACTIVE';
```

★**역할은 로그인 시점에 세션에 굳는다.** 승격 뒤 반드시 **다시 로그인**해야 `/admin/*` 이 열린다.
평소에는 이 SQL 대신 `taspa.admin.emails` 설정을 쓴다(기동 시 `AdminBootstrapConfig` 가 승격하고
`ADMIN_ROLE_GRANTED` 감사를 남긴다).

### 4-4. 복구 후 확인

1. 다시 로그인 → `/admin/iam` 열림
2. 시뮬레이터로 그 계정의 `iam:UpdatePolicy` / `trn:taspa:iam::policy/*` → **ALLOW**
3. `/admin/audit` 에서 잠금을 만든 변경(`ADMIN_IAM_*`)을 찾아 경위를 기록

## 5. 이 문서가 닫지 못하는 것 (정직하게)

- **간접 권한상승 경로**는 그대로다: `platform:RegisterClient`(org/merchant 결속 M2M 발급)·
  `platform:AddOrgMember`·`platform:CreateSsoConnection`. 제외 목록에 넣으면 신규 고객사 온보딩이 막힌다.
  지금 산출물은 그것들이 **이름을 갖게 됐다**는 것이고, "더 안전해졌다"가 아니라 "좁히는 것이 가능해졌다"가
  정확한 표현이다.
- 가드는 **플랫폼 IAM 축**만 본다. 조직 축의 마지막 관리자 락아웃은 별도 가드
  (`OrganizationService.guardLastAdmin`)가 담당한다.
- 손상된 정책 문서로 인한 잠금은 가드가 통과시키므로 4장의 DB 절차가 유일한 복구다.

## 관련

- 인가 모델 전반: CLAUDE.md "AWS IAM 스타일 정책 RBAC"
- 조직 커스텀 역할: CLAUDE.md "조직 커스텀 역할", `docs/integration-roles.md`
- 돈 표면 운영: `docs/billing-operations.md`
