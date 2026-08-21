-- 이메일 도메인 자동 조직 가입(승인된 정책: 권고안 + DNS TXT 자가검증).
--   org_domains: 조직별 자동가입 도메인. 도메인은 소문자 정규화 저장.
--   충돌 정책은 "검증 선점"(소유를 증명한 조직이 이긴다): 전역 유니크는 verified 행에만 적용된다
--   (부분 유니크 uq_org_domain_verified). 미검증 클레임은 선점 효력이 없다 — 여러 조직이 같은
--   도메인을 미검증 상태로 동시에 보유할 수 있고(스쿼팅으로 타 조직 등록을 막을 수 없다), 검증
--   성공 시점에 타 조직의 미검증 동일 도메인 클레임은 제거된다(검증이 곧 탈환). org 내 중복
--   클레임만 uq_org_domain_org 로 차단한다.
--   verification_token: DNS TXT 기대값(taspa-verify=<token>)의 토큰부. 검증되기 전(verified=false)엔
--   자동 가입에 절대 쓰이지 않는다. 공용 이메일 도메인(gmail 등)은 앱 계층에서 등록 자체를 거부한다.
--   verified_method: 'dns-txt' | 'manual'(플랫폼 ADMIN force-verify). 주기 재검증 잡은 dns-txt 만
--   재확인한다(manual 은 오프라인 소유 확인이 근거 — TXT 부재가 정상이라 재검증 대상이 아니다).
--   reverify_failures: 주기 재검증 연속 실패 수(성공 시 0 리셋) — 일시적 DNS 장애 1회로 철회되지 않게 한다.
CREATE TABLE org_domains (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    domain VARCHAR(255) NOT NULL,                    -- 소문자 정규화
    verified BOOLEAN NOT NULL DEFAULT false,
    verification_token VARCHAR(64) NOT NULL,          -- DNS TXT 기대값(taspa-verify=<token>)
    verified_method VARCHAR(16),                      -- 'dns-txt' | 'manual' (verified=false 면 NULL)
    reverify_failures INT NOT NULL DEFAULT 0,         -- 주기 재검증 연속 실패 수
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    verified_at TIMESTAMP
);
CREATE UNIQUE INDEX uq_org_domain_verified ON org_domains(domain) WHERE verified;  -- 검증 도메인만 전역 1조직(검증 선점)
CREATE UNIQUE INDEX uq_org_domain_org ON org_domains(org_id, domain);              -- org 내 중복 클레임 차단(org 조회 인덱스 겸용)

-- 조직별 opt-in 플래그(기본 OFF) — ORG_ADMIN 이 명시적으로 켤 때만 자동 가입이 동작한다.
ALTER TABLE organizations ADD COLUMN auto_join_enabled BOOLEAN NOT NULL DEFAULT false;
