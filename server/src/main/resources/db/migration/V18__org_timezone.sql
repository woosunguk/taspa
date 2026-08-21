-- Phase 0ب-C 리뷰 수정: 조직 로컬 타임존. 소비 이벤트 집계의 date 버킷을 org-로컬 달력으로 앵커링하기 위한
--   기준값이다. occurred_at 은 TIMESTAMP(without tz)로 UTC wall-clock 을 저장하므로, CAST(occurred_at AS date)
--   는 UTC 기준으로 절단되어 KST(UTC+9) 같은 조직의 00:00~08:59 로컬 소비(아침 포함)가 '전날' 버킷으로
--   오귀속된다(예측 정답데이터 오염). 집계 쿼리는 이 컬럼으로
--   CAST((occurred_at AT TIME ZONE 'UTC') AT TIME ZONE timezone AS date) 변환해 org-로컬 날짜로 버킷한다.
--   기본값 'UTC' — 기존 조직 동작(UTC 절단)을 보존한다. 유효한 IANA/Postgres 존 이름만 저장한다(서비스 검증).
ALTER TABLE organizations ADD COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'UTC';
