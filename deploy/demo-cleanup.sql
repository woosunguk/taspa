-- 데모 직전 정리: e2e 가 만든 테스트 잔여를 지운다.
--
-- ★실행은 사람의 판단이다 — 이 파일은 준비물이지 자동 실행 대상이 아니다.
--   지우는 것은 **e2e 네이밍 규약을 따르는 행뿐**이다(money-e2e-*, role-e2e-*, signal-e2e-* 등).
--   데모 조직(taspa-demo)·데모 계정(*@taspa.example)·emp* 직원은 건드리지 않는다.
--
-- 왜 필요한가: e2e 는 실행마다 자기 조직·매장·계정을 새로 만든다(격리 — 서로의 상태를 밟지 않기 위해).
-- 그 대가로 관리 대시보드의 "미확정 청구서"와 IAM 스코프 선택지가 잔여로 도배된다 —
-- 심사위원이 처음 여는 화면이 테스트 쓰레기로 어지러우면 제품이 어지러워 보인다.
--
-- 실행:  docker exec -i taspa-postgres-1 psql -U taspa -d taspa < deploy/demo-cleanup.sql
BEGIN;

-- 조직 CASCADE 가 대부분을 정리한다(멤버십·소비이벤트·청구서·캘린더·부재·정책 전부 org FK CASCADE).
DELETE FROM organizations
WHERE slug LIKE 'money-e2e-%'
   OR slug LIKE 'role-e2e-%'
   OR slug LIKE 'signal-e2e-%'
   OR slug LIKE 'device-e2e-%'
   OR slug LIKE 'spa-e2e-%';

-- 가맹은 플랫폼 전역 자원이라 org CASCADE 에 안 걸린다. e2e 매장의 거래·원장·설정을 먼저 지운다.
-- (ledger_postings 는 entry FK CASCADE, meal_refunds 는 transaction FK CASCADE)
DELETE FROM ledger_entries WHERE merchant_id IN (SELECT id FROM merchants WHERE name LIKE '%e2e%');
DELETE FROM meal_transactions WHERE merchant_id IN (SELECT id FROM merchants WHERE name LIKE '%e2e%');
DELETE FROM consumption_events WHERE merchant_id IN (SELECT id FROM merchants WHERE name LIKE '%e2e%');
DELETE FROM merchants WHERE name LIKE '%e2e%';

-- e2e 계정(가입 실경로로 만든 것). 세션·연동·감사 행위자는 FK 규약대로 정리/보존된다.
DELETE FROM users
WHERE email LIKE 'money-%@example.com'
   OR email LIKE 'signal-%@example.com'
   OR email LIKE 'role-%@example.com'
   OR email LIKE 'spa-%@example.com'
   OR email LIKE 'device-%@example.com';

-- e2e 가 등록한 M2M 클라이언트(이름 규약).
DELETE FROM oauth2_registered_client WHERE client_id LIKE 'money-e2e-%' OR client_id LIKE 'role-e2e-%';

COMMIT;

-- 확인:
--   select count(*) from organizations where slug like '%e2e%';  -- 0 이어야 한다
