-- 이메일 대소문자 정규화: 조회는 lower(email) 비교(UserRepository), 저장은 소문자.
-- 기존 행도 소문자로 맞추고, lower(email) 유일성을 DB 레벨에서 보장한다.
UPDATE users SET email = lower(email);

CREATE UNIQUE INDEX idx_users_email_lower ON users (lower(email));
