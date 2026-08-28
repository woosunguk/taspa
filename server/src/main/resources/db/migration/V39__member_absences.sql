-- 하루 단위 부재(연차·반차·출장·병가) — **식수 예측의 재실 모수를 하루 단위로 정확하게** 만든다.
--
-- 왜 새 테이블인가: 장기 휴직은 이미 `org_memberships.employment_status = ON_LEAVE` 로 표현되고
-- 예측의 재실 집계(EMPLOYED 필터)가 그걸 이미 제외한다. 없는 것은 **날짜 범위를 갖는 하루짜리 부재**다 —
-- "내일 개발팀 10명이 연차"는 멤버십 상태로 표현할 수 없다(그 사람은 여전히 재직 중이다).
--
-- ★행은 **하루 한 건**이다(범위가 아니라). 예측은 "그 날짜에 몇 명이 빠졌나"만 물으므로 날짜별 행이
--   질의를 한 번으로 끝낸다. 범위로 저장하면 조회할 때마다 구간을 펼쳐야 하고, 겹치는 범위·부분 취소를
--   다루는 코드가 예측 경로 안으로 들어온다.
--
-- ★`weight` 는 반차를 위한 것이다(1.0 = 종일, 0.5 = 반차). 반차인 사람은 한 끼를 먹으므로 모수에서
--   통째로 빼면 과소예측이 된다. **끼니별 정밀도는 아직 없다**(오전/오후 반차 구분 없음) — 근사임을
--   `type` 에 남겨, 나중에 끼니 축으로 정밀화할 여지를 남긴다.
CREATE TABLE org_member_absences (
    id           uuid PRIMARY KEY,
    org_id       uuid NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    absence_date date NOT NULL,
    -- ANNUAL_LEAVE | HALF_DAY | BUSINESS_TRIP | SICK | OTHER
    type         varchar(32)   NOT NULL,
    -- MANUAL(콘솔) | BULK(CSV) | HR(외부 연동) — 어디서 들어온 값인지가 신뢰도 판단의 근거다.
    source       varchar(32)   NOT NULL DEFAULT 'MANUAL',
    weight       numeric(3, 2) NOT NULL DEFAULT 1.00,
    created_at   timestamp     NOT NULL DEFAULT now(),
    -- 같은 사람의 같은 날짜는 한 건이다. 재전송(HR 연동·CSV 재업로드)이 모수를 두 번 깎지 않게 한다.
    CONSTRAINT uq_absence_member_date UNIQUE (org_id, user_id, absence_date),
    CONSTRAINT ck_absence_weight CHECK (weight > 0 AND weight <= 1)
);

-- 예측이 쓰는 유일한 질의 형태: org 의 날짜 구간을 날짜별로 집계. 신규 빈 테이블이라 일반 CREATE INDEX
-- 로 충분하다(CONCURRENTLY 규약은 기존 쓰기 경로 테이블용 — V21 선례).
CREATE INDEX idx_absence_org_date ON org_member_absences (org_id, absence_date);
