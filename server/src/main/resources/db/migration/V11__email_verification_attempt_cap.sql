-- 이메일 인증 코드 브루트포스 방지: 코드당 불일치 제출 횟수를 기록하고,
-- 상한(EmailVerificationService.MAX_ATTEMPTS_PER_CODE) 도달 시 코드를 소진(무효화)한다.
-- RISK_CHALLENGE(리스크 챌린지)가 이 코드를 2차 인증으로 재사용하면서 필수가 됐다.
ALTER TABLE email_verification_codes ADD COLUMN failed_attempts INT NOT NULL DEFAULT 0;
