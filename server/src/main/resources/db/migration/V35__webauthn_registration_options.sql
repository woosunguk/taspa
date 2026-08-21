-- 패스키 등록 옵션 영속화 — **다중 인스턴스 전제 위반 해소**.
--
-- 그전까지 등록 옵션은 발급한 인스턴스의 **인메모리 맵**에만 있었다. 로드밸런서 뒤에서는
-- "옵션은 A 가 발급했는데 브라우저의 자격증명 POST 가 B 로 간다" → B 에는 옵션이 없다 →
-- 패스키 등록이 실패한다. 사용자에게는 지문을 찍었는데 아무 일도 안 일어나는 것으로 보이고,
-- 다시 시도해도 절반의 확률로 같은 일이 반복된다(원인을 짐작할 단서가 화면에 없다).
--
-- 세션에 넣지 못한 이유는 `PublicKeyCredentialCreationOptions` 가 Serializable 이 아니어서다
-- (Spring Security 6.4.4 실측). Jackson 왕복도 불가능하다 — `WebauthnJackson2Module` 의 믹스인은
-- **직렬화 전용**이고 역직렬화 creator 가 없다(테스트로 확인). 그래서 검증에 필요한 필드만
-- 컬럼으로 풀어 저장하고 로드 시 재구성한다.
--
-- ★재구성이 안전한 근거(바이트코드로 확인): `Webauthn4JRelyingPartyOperations.registerCredential` 이
--   옵션에서 읽는 것은 **rp · challenge · authenticatorSelection.userVerification · pubKeyCredParams ·
--   user** 다섯 뿐이고, `WebAuthnRegistrationFilter` 는 옵션 필드를 직접 읽지 않는다(통째로 넘긴다).
--   excludeCredentials·extensions 는 브라우저가 **생성 시점에** 소비하는 값이라 검증에 관여하지 않는다.
CREATE TABLE webauthn_registration_options (
    -- 세션에 저장되는 불투명 핸들. 세션에는 이 문자열만 들어가므로 직렬화 문제가 없다.
    token VARCHAR(64) PRIMARY KEY,

    challenge BYTEA NOT NULL,
    user_handle BYTEA NOT NULL,
    user_name VARCHAR(255) NOT NULL,
    user_display_name VARCHAR(255) NOT NULL,
    rp_id VARCHAR(255) NOT NULL,
    rp_name VARCHAR(255) NOT NULL,

    -- 검증 강도를 결정하는 값들. 설정에서 다시 읽지 않고 **발급 시점 값을 그대로 저장**한다 —
    -- 발급과 검증 사이에 배포가 끼어 설정이 바뀌어도 그 등록은 약속한 강도로 검증된다.
    user_verification VARCHAR(32),
    algorithms VARCHAR(255) NOT NULL,        -- COSE alg 값 CSV(예: "-7,-257")

    timeout_millis BIGINT,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

-- 만료 정리(RetentionCleanupJob)용. 신규 빈 테이블이라 일반 CREATE INDEX(V21·V31 선례).
CREATE INDEX idx_webauthn_reg_options_expires ON webauthn_registration_options (expires_at);
