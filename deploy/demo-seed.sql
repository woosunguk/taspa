-- 데모/심사용 시드 데이터. 재실행 안전(ON CONFLICT DO NOTHING).
--
-- 목적: 심사위원이 **가입·이메일 인증 없이** 세 역할을 바로 체험하게 한다.
--   직원(staff)         : /meal 식권 발급·사용내역
--   회사 관리자(orgadmin): /console/{org} 조직 10탭(구성원·부서·식대정책·예측·청구서…)
--   식당 관리자(merchant): /merchant/{id} 식수로그·정산명세·예측
--
-- 비밀번호는 네 계정 모두 1111 (데모 편의 — 정책(12자·대소문자·숫자·특수문자)을 통과하지 않는
-- 값이라 화면에서 변경할 수는 없다. 해시를 직접 넣기 때문에 로그인만 가능하다.
-- ★공개 노출 상태에서는 위험하다: 플랫폼 관리자까지 같은 값이면 URL 을 아는 누구나 관리 콘솔에
--   들어온다. 심사 종료 후 또는 공개 배포 전에는 반드시 강한 값으로 되돌릴 것.
-- 해시는 bcrypt cost 12 — users.password_hash 는 접두사 없는 원시 bcrypt 다(기존 행과 동일 형식).
--
-- ★가맹점은 **새로 만들지 않고 '본사 구내식당'(6dc75701…)을 재사용**한다. dev 의 POS M2M 클라이언트
--   pos-terminal-dev 가 그 매장 UUID 에 결속돼 있어서(settings.client.merchant-id), 다른 매장을 만들면
--   /pos 단말이 그 매장으로는 승인할 수 없다. 매장을 바꾸려면 클라이언트 결속도 함께 바꿔야 한다.
--
-- 적용: docker compose exec -T postgres psql -U taspa -d taspa -f - < deploy/demo-seed.sql

BEGIN;

-- ── 조직 ────────────────────────────────────────────────────────────────────
INSERT INTO organizations (id, slug, name, status, timezone, auto_join_enabled, created_at, updated_at)
VALUES ('d0000000-0000-4000-8000-000000000001', 'taspa-demo', '타스파 데모 주식회사',
        'ACTIVE', 'Asia/Seoul', false, now() - interval '90 days', now())
ON CONFLICT DO NOTHING;

-- 사업장(가맹 그레인·정책 site 축의 앵커)
INSERT INTO sites (id, org_id, name, address, timezone, created_at, updated_at)
VALUES ('d0000000-0000-4000-8000-000000000002', 'd0000000-0000-4000-8000-000000000001',
        '본사', '서울특별시 강남구 테헤란로 1', 'Asia/Seoul', now() - interval '90 days', now())
ON CONFLICT DO NOTHING;

-- 부서 트리 — 플랫폼파트는 개발팀의 자식이다(식대 정책 '가장 가까운 조상' 상속 시연용).
INSERT INTO departments (id, org_id, parent_id, name, created_at, updated_at) VALUES
  ('d0000000-0000-4000-8000-000000000010', 'd0000000-0000-4000-8000-000000000001', NULL, '개발팀',     now() - interval '90 days', now()),
  ('d0000000-0000-4000-8000-000000000011', 'd0000000-0000-4000-8000-000000000001', NULL, '경영지원팀', now() - interval '90 days', now()),
  ('d0000000-0000-4000-8000-000000000012', 'd0000000-0000-4000-8000-000000000001',
   'd0000000-0000-4000-8000-000000000010', '플랫폼파트', now() - interval '90 days', now())
ON CONFLICT DO NOTHING;

-- ── 계정 3개 (email_verified=true → 이메일 코드 게이트 없음) ──────────────────
INSERT INTO users (id, email, password_hash, email_verified, status, failed_login_attempts,
                   created_at, updated_at, display_name, mfa_enabled, role) VALUES
  ('d0000000-0000-4000-8000-000000000100', 'staff@taspa.example',    '$2a$12$5bHYntKQ9/jP3Te9iYBWieXWG7ybppaazaMXJXy8sFPknfjAKrl02', true, 'ACTIVE', 0, now() - interval '60 days', now(), '김직원', false, 'USER'),
  ('d0000000-0000-4000-8000-000000000101', 'orgadmin@taspa.example', '$2a$12$5bHYntKQ9/jP3Te9iYBWieXWG7ybppaazaMXJXy8sFPknfjAKrl02', true, 'ACTIVE', 0, now() - interval '90 days', now(), '박관리', false, 'USER'),
  ('d0000000-0000-4000-8000-000000000102', 'merchant@taspa.example', '$2a$12$5bHYntKQ9/jP3Te9iYBWieXWG7ybppaazaMXJXy8sFPknfjAKrl02', true, 'ACTIVE', 0, now() - interval '90 days', now(), '이사장', false, 'USER')
ON CONFLICT DO NOTHING;

-- ── 조직 멤버십 ─────────────────────────────────────────────────────────────
INSERT INTO org_memberships (id, org_id, user_id, role, department, status, joined_at,
                             department_id, site_id, employee_id, job_title, employment_type,
                             hire_date, employment_status) VALUES
  ('d0000000-0000-4000-8000-000000000200', 'd0000000-0000-4000-8000-000000000001',
   'd0000000-0000-4000-8000-000000000101', 'ORG_ADMIN', '경영지원팀', 'ACTIVE', now() - interval '90 days',
   'd0000000-0000-4000-8000-000000000011', 'd0000000-0000-4000-8000-000000000002',
   'EMP-0001', '경영지원팀장', 'FULL_TIME', current_date - 500, 'EMPLOYED'),
  ('d0000000-0000-4000-8000-000000000201', 'd0000000-0000-4000-8000-000000000001',
   'd0000000-0000-4000-8000-000000000100', 'MEMBER', '플랫폼파트', 'ACTIVE', now() - interval '60 days',
   'd0000000-0000-4000-8000-000000000012', 'd0000000-0000-4000-8000-000000000002',
   'EMP-0007', '백엔드 개발자', 'FULL_TIME', current_date - 300, 'EMPLOYED')
ON CONFLICT DO NOTHING;

-- 멤버십 SCD 스냅샷. ★recorded_at 을 **과거**로 둔다 — 식수예측의 재실보정
-- (countActiveEmployedAsOf)이 basis 주차의 재직 인원을 이력에서 복원하므로, now() 로 넣으면
-- 지난주 기준 인원이 0 이 되어 보정이 조용히 생략된다.
INSERT INTO org_membership_history (id, org_id, user_id, role, department_id, site_id,
                                    employment_type, employment_status, job_title, change_type,
                                    recorded_at, recorded_by) VALUES
  ('d0000000-0000-4000-8000-000000000210', 'd0000000-0000-4000-8000-000000000001',
   'd0000000-0000-4000-8000-000000000101', 'ORG_ADMIN', 'd0000000-0000-4000-8000-000000000011',
   'd0000000-0000-4000-8000-000000000002', 'FULL_TIME', 'EMPLOYED', '경영지원팀장', 'JOINED',
   now() - interval '90 days', NULL),
  ('d0000000-0000-4000-8000-000000000211', 'd0000000-0000-4000-8000-000000000001',
   'd0000000-0000-4000-8000-000000000100', 'MEMBER', 'd0000000-0000-4000-8000-000000000012',
   'd0000000-0000-4000-8000-000000000002', 'FULL_TIME', 'EMPLOYED', '백엔드 개발자', 'JOINED',
   now() - interval '60 days', NULL)
ON CONFLICT DO NOTHING;

-- ── 식대 정책 ───────────────────────────────────────────────────────────────
-- daily_meal_count 는 운영 현실(1회)이 아니라 **데모 편의로 3회**다 — 시연 중 같은 날 여러 번
-- 승인해 봐야 하는데 1회면 두 번째부터 거절된다. 끼니창은 자정을 넘기지 않는다(넘기면 반개구간
-- 판정이 항상 거짓이 되어 그 창의 결제가 통째로 막힌다).
INSERT INTO meal_policies (org_id, per_meal_limit_minor, daily_meal_count, monthly_cap_minor,
                           breakfast_start, breakfast_end, lunch_start, lunch_end,
                           dinner_start, dinner_end, updated_at)
VALUES ('d0000000-0000-4000-8000-000000000001', 12000, 3, 200000,
        '07:00', '09:30', '11:00', '14:00', '17:00', '20:30', now())
ON CONFLICT (org_id) DO NOTHING;

-- 개발팀만 1식 15,000원으로 상향. **필드 단위 재정의**라 나머지(일 횟수·월 한도·끼니창)는
-- 조직 기본값을 그대로 물려받는다. 플랫폼파트는 자기 재정의가 없어 조상(개발팀) 값을 쓴다.
INSERT INTO meal_policy_overrides (id, org_id, department_id, site_id, per_meal_limit_minor,
                                   reason, created_at, updated_at, updated_by)
VALUES ('d0000000-0000-4000-8000-000000000300', 'd0000000-0000-4000-8000-000000000001',
        'd0000000-0000-4000-8000-000000000010', NULL, 15000,
        '개발팀 야근 식대 상향', now(), now(), 'd0000000-0000-4000-8000-000000000101')
ON CONFLICT DO NOTHING;

-- ── 가맹점 ──────────────────────────────────────────────────────────────────
-- 타임존을 KST 로, site 를 이 조직 사업장으로 맞춘다. site 가 다른 조직 소속이면 redeem 의
-- 소비이벤트 site 귀속이 교차 테넌트 가드에 걸려 비게 된다(예측 site 축이 빈다).
UPDATE merchants
   SET timezone = 'Asia/Seoul', site_id = 'd0000000-0000-4000-8000-000000000002', updated_at = now()
 WHERE id = '6dc75701-36b5-4a3f-8ee5-5b6d2484685f';

-- 식당 관리자(사람). 결제 승인은 여전히 M2M 전용이고 이 멤버십은 조회 권한이다.
-- ★매장이 ACTIVE 여야 콘솔에 진입한다(PENDING 이면 '성공'을 보고받고도 화면엔 매장이 없다).
INSERT INTO merchant_members (id, merchant_id, user_id, role, status, created_at, updated_at)
VALUES ('d0000000-0000-4000-8000-000000000400', '6dc75701-36b5-4a3f-8ee5-5b6d2484685f',
        'd0000000-0000-4000-8000-000000000102', 'MERCHANT_ADMIN', 'ACTIVE', now(), now())
ON CONFLICT DO NOTHING;

-- ── 플랫폼 관리자 ───────────────────────────────────────────────────────────
-- users.role='ADMIN' → /admin/** 전체(조직·사용자·클라이언트·가맹·감사·SSO·캘린더·IAM·
-- 지급현황·정합성대사). ★역할은 **로그인 시점에 세션에 굳는다** — 기존 계정을 나중에
-- 승격하면 재로그인해야 반영되지만, 이 계정은 처음부터 ADMIN 이라 그 함정이 없다.
--
-- ★조직 멤버십을 **일부러 주지 않는다.** 플랫폼 관리자는 org 밖의 역할이고, 멤버십까지
--   주면 "플랫폼 권한으로 열린 화면"과 "조직 멤버라서 열린 화면"이 구분되지 않아 심사
--   중 권한 경계를 설명할 수 없다. 식권 발급(meal:IssueQr)은 플랫폼 관리자 제외 목록에
--   있어 애초에 이 계정으로 할 수 없다(실지출을 만드는 능력이라서).
INSERT INTO users (id, email, password_hash, email_verified, status, failed_login_attempts,
                   created_at, updated_at, display_name, mfa_enabled, role)
VALUES ('d0000000-0000-4000-8000-000000000103', 'admin@taspa.example', '$2a$12$5bHYntKQ9/jP3Te9iYBWieXWG7ybppaazaMXJXy8sFPknfjAKrl02', true, 'ACTIVE', 0,
        now() - interval '90 days', now(), '플랫폼 운영자', false, 'ADMIN')
ON CONFLICT DO NOTHING;

COMMIT;
