-- Phase 0ب-C: 소비 이벤트 수집 seam(정답 데이터 · 제네릭). 결제(생산자, 미구현)와 예측(소비자, 미구현)을
--   분리하는 append-only 소비 이벤트 로그. 생산자 무관(payment·POS·manual import 모두 이 API로 적재),
--   예측은 집계로만 읽는다. 프라이버시: user_sub 는 선택(집계 라벨용) — 조회 API 는 집계만 노출한다.
--   멱등 적재: (org_id, source, external_id) UNIQUE — 생산자 재전송이 no-op/갱신이 되게 한다.
--   ★멱등키는 org 범위다: source={payment,pos,manual,import} 는 고정 소집합이고 pos·manual·import 의
--   external_id(영수증번호 등)는 조직-로컬로만 유일하므로, 전역 UNIQUE 면 두 조직이 우연히 같은 external_id
--   를 쓸 때 뒤에 적재한 조직 이벤트가 충돌·거부되어 정답데이터가 유실된다. org_id 를 키에 포함해 조직 간
--   네임스페이스를 분리하고, 교차-테넌트 하이재킹도 lookup 자체가 org 스코프라 원천 차단한다.
--   테넌시: org_id 는 organizations 를 참조하고 조직 삭제 시 CASCADE 로 함께 정리된다.
CREATE TABLE consumption_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source VARCHAR(24) NOT NULL,                     -- payment | pos | manual | import
    external_id VARCHAR(128) NOT NULL,               -- 생산자 멱등키(org 범위)
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_sub UUID,                                   -- 개인 라벨(선택, 집계용 · 조회에 개별 노출 금지)
    merchant_id UUID,                                -- 식당(결제 도메인 생기면 FK)
    meal_window VARCHAR(16) NOT NULL,                -- BREAKFAST | LUNCH | DINNER
    menu_ref VARCHAR(128),                           -- 메뉴 식별(선택)
    quantity INT NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED', -- CONFIRMED | VOIDED
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (org_id, source, external_id)
);

-- 집계 쿼리 패턴에 맞춘 인덱스: WHERE org_id = ? AND status = 'CONFIRMED' AND occurred_at 범위.
--   meal_window 는 등치 필터가 아니라 GROUP BY 축이라 선두 컬럼에서 제외한다. status='CONFIRMED' 부분
--   인덱스로 VOIDED 를 배제해 CONFIRMED 스캔을 좁힌다(집계는 CONFIRMED 만 읽는다).
CREATE INDEX idx_consumption_org_time_confirmed
    ON consumption_events(org_id, occurred_at)
    WHERE status = 'CONFIRMED';
