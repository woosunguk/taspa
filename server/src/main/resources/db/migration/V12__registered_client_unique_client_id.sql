-- SAS 공식 스키마(V2)는 client_id 에 UNIQUE 제약이 없다(알려진 공백).
-- 관리 콘솔 등록(check-then-insert)의 동시 요청 레이스로 동일 client_id 행이 중복 생성되면
-- findByClientId 가 임의 1행을 반환해 secret 재발급/수정이 갈라지는 진단 불가 상태가 된다 —
-- DB 계층에서 차단한다(AdminClientService.register 는 위반을 CLIENT_ID_ALREADY_EXISTS 로 변환).
ALTER TABLE oauth2_registered_client
    ADD CONSTRAINT uq_oauth2_registered_client_client_id UNIQUE (client_id);
