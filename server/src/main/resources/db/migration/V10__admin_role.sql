-- 관리자 콘솔(Stage A): 사용자 역할. USER | ADMIN (domain/user/UserRole).
-- 첫 관리자 지정은 taspa.admin.emails 부트스트랩 또는 README 의 SQL 참고.
ALTER TABLE users ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'USER';
