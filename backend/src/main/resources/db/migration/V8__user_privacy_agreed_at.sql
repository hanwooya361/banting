-- T21 개인정보처리방침 동의 시각. 가입 때 동의해야 가입되고 그 시각을 남긴다 (언제 어떤 방침에 동의했는지 증빙)
-- 이 기능 전에 가입한 회원은 NULL (화면에서 체크만 받던 기간, 방침 시행일 2026-11-24)
ALTER TABLE users ADD COLUMN privacy_agreed_at DATETIME(6) NULL AFTER search_radius_m;
