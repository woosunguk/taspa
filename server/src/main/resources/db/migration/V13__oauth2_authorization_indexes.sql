-- oauth2_authorization 핫패스 조회 인덱스.
-- Spring Authorization Server 의 JdbcOAuth2AuthorizationService 는 토큰 조회 시 컬럼 동등비교를 한다.
-- 지금까지 PRIMARY KEY(id) 외 2차 인덱스가 전무해 인가코드 교환·토큰 리프레시가 풀스캔이었다.
--
-- 인덱스 대상은 opaque(고정 길이) 토큰 컬럼으로 한정한다:
--   authorization_code_value / refresh_token_value / state — 인가코드 교환·리프레시·state 조회 핫패스.
-- access_token_value / oidc_id_token_value 는 JWT(대용량 가변)라 btree 인덱스 시
-- "index row size exceeds maximum(2704 bytes)" 로 삽입이 실패할 위험이 있어 의도적으로 제외한다
-- (Resource Server 는 JWKS 로 로컬 검증하므로 이 두 컬럼의 DB 조회는 드물다).
CREATE INDEX idx_oauth2_authorization_code
    ON oauth2_authorization (authorization_code_value);
CREATE INDEX idx_oauth2_authorization_refresh
    ON oauth2_authorization (refresh_token_value);
CREATE INDEX idx_oauth2_authorization_state
    ON oauth2_authorization (state);
CREATE INDEX idx_oauth2_authorization_principal_client
    ON oauth2_authorization (principal_name, registered_client_id);
