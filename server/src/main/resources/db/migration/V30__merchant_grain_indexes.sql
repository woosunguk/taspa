-- 가맹 그레인 조회 인덱스(가맹 관리자 콘솔).
--
-- 지금까지 두 테이블의 인덱스는 전부 org/user 앵커였다(V17·V25) — 가맹점은 기계 신원만 있었고
-- "이 매장의 데이터"를 통째로 훑는 질의가 없었기 때문이다. 가맹 관리자 콘솔은 merchant_id 를 축으로
-- 거래 로그와 예측 실적을 읽으므로, 앵커가 없으면 매 요청이 순차 스캔이 된다.
--
-- consumption_events 쪽은 부분 인덱스다: 예측 실적 질의가 항상 status='CONFIRMED' + merchant_id NOT NULL
-- (VOIDED 는 집계에서 빠지고, 매장 무관 이벤트는 대상이 아님)이라 인덱스 크기를 최소로 유지한다
-- (idx_consumption_org_time_confirmed 와 동일 사상).
--
-- CONCURRENTLY 인 이유: 이 두 테이블은 결제 승인(meal_transactions)과 장부 적재(consumption_events)의
-- 쓰기 경로다. 일반 CREATE INDEX 는 빌드가 끝날 때까지 SHARE 락으로 INSERT 를 막으므로, 행이 쌓인 뒤
-- 배포하면 인덱스 빌드 시간만큼 승인이 정지한다(2천만 행 실측: 빌드 6.8초 동안 INSERT 5.7초 대기).
-- 배포 파이프라인이 마이그레이션을 선행 잡으로 분리해도(README 운영 주의) 그 정지 시간 동안 실서비스의
-- 승인은 막힌다 — 분리는 기동 지연을 없앨 뿐 테이블 락을 없애지 못한다. 락을 없애는 건 CONCURRENTLY 뿐이다.
--
-- CONCURRENTLY 는 트랜잭션 블록 안에서 실행할 수 없다. flyway-database-postgresql 10.20.1 의
-- PostgreSQLParser 가 정규식 ^(CREATE|DROP)( UNIQUE)? INDEX CONCURRENTLY 로 이 문장을 감지해
-- 스크립트 전체를 트랜잭션 밖에서 돌린다. 옆 파일 V30__merchant_grain_indexes.sql.conf 의
-- executeInTransaction=false 는 그 파서 감지에 의존하지 않으려는 명시 선언이다(둘 다 같은 결과).
-- 그래서 이 파일에는 트랜잭션이 필요한 문장을 절대 섞지 마라 — 한 스크립트가 트랜잭션 문장과
-- 비트랜잭션 문장을 섞으면 mixed=false(기본값)에서 마이그레이션 자체가 실패한다.
--
-- 그것만으로는 부족하다: application.yml 의 spring.flyway.postgresql.transactional-lock=false 가
-- 반드시 함께 있어야 한다. 기본값(true)에서는 Flyway 가 스키마 히스토리 연결의 트랜잭션으로
-- 마이그레이션 전체를 감싸는데, 그 idle-in-transaction 스냅샷 때문에 CONCURRENTLY 빌드가 상대의
-- 가상 xid 를 영원히 기다려 부팅이 멈춘다(신규 DB 전체 적용으로 재현 확인). 그 설정을 지우면
-- 이 파일은 곧바로 기동 장애가 된다.
--
-- 실패 복구(중요): CONCURRENTLY 는 실패 시 INVALID 인덱스를 남기고 자동 정리되지 않는다. 트랜잭션
-- 밖이라 Flyway 롤백도 없다. 게다가 IF NOT EXISTS 는 이름만 보므로, INVALID 인덱스를 남긴 채 재실행하면
-- 조용히 건너뛰고 영원히 안 쓰이는 인덱스가 남는다. 재실행 전 반드시 유효성을 확인하고 DROP 할 것:
--   SELECT c.relname, i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
--    WHERE c.relname IN ('idx_consumption_merchant_time_confirmed', 'idx_meal_tx_merchant_time');
--   DROP INDEX CONCURRENTLY IF EXISTS <indisvalid=false 인 인덱스명>;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_consumption_merchant_time_confirmed
    ON consumption_events(merchant_id, occurred_at)
    WHERE status = 'CONFIRMED' AND merchant_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_meal_tx_merchant_time
    ON meal_transactions(merchant_id, approved_at);
