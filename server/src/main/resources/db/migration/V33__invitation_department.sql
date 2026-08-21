-- 초대에 **구조적 부서 배정**을 실을 수 있게 한다.
--
-- 그전까지 초대가 나르던 `department` 는 자유 텍스트 라벨이었다(HR 이 적어 넣는 이름). 그건 화면에
-- 보이는 문자열일 뿐이라, 초대로 입사한 사람은 `department_id` 가 NULL 인 채로 멤버가 됐다.
-- 부서별 식대 정책이 붙은 지금 그 차이는 눈에 보인다: 개발팀 재정의(1식 18,000원)를 만들어 두고
-- 개발팀 신입을 초대하면, 그 사람만 조직 기본값(12,000원)으로 결제된다. 조직관리자가 나중에
-- 배정 화면에서 손으로 이어 주기 전까지 — 그리고 그 필요를 알려 주는 신호는 아무 데도 없다.
--
-- ON DELETE SET NULL: 부서가 사라져도 초대 자체는 유효해야 한다(사람은 여전히 입사한다).
-- 그 경우 자유 텍스트 라벨만 남고 구조 배정 없이 합류하는데, 그건 부서를 지운 결과로 옳다.
ALTER TABLE org_invitations
    ADD COLUMN department_id UUID REFERENCES departments(id) ON DELETE SET NULL;

-- 초대 목록 화면이 부서별로 묶어 보여줄 때 쓴다. 신규 컬럼이라 채워진 행이 없어 일반 CREATE INDEX
-- 로 충분하다(V30 이후 CONCURRENTLY 규약은 대량 데이터가 이미 있는 컬럼에 대한 것이다).
CREATE INDEX idx_org_invitations_department ON org_invitations (department_id)
    WHERE department_id IS NOT NULL;
