-- T23 카카오·구글 간편 가입
-- 간편 가입 회원은 비밀번호가 없고, 카카오는 이메일을 주지 않을 수 있다 → 둘 다 NULL 허용
-- (MySQL UNIQUE는 NULL을 여러 개 허용하므로 uk_users_email 그대로)
ALTER TABLE users
    MODIFY email VARCHAR(100) NULL,
    MODIFY password_hash VARCHAR(100) NULL;

-- 회원 ↔ 소셜 계정 연결. 같은 소셜 계정은 한 회원에게만, 한 회원은 공급자마다 하나만
CREATE TABLE user_social_accounts (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    user_id          BIGINT       NOT NULL,
    provider         VARCHAR(10)  NOT NULL,
    provider_user_id VARCHAR(100) NOT NULL,   -- 카카오 회원번호 / 구글 sub
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_social_provider_user (provider, provider_user_id),
    UNIQUE KEY uk_social_user_provider (user_id, provider),
    CONSTRAINT fk_social_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_social_provider CHECK (provider IN ('KAKAO', 'GOOGLE'))
);

-- 처음 온 소셜 사용자의 가입 대기표 (10분). 동의·닉네임 화면을 거친 뒤에만 회원을 만든다.
-- refresh 토큰처럼 쿠키에는 무작위 값, DB에는 SHA-256 해시만 저장. 한 번 쓰면 used_at
CREATE TABLE oauth_signup_tickets (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    token_hash         CHAR(64)     NOT NULL,
    provider           VARCHAR(10)  NOT NULL,
    provider_user_id   VARCHAR(100) NOT NULL,
    email              VARCHAR(100) NULL,      -- 공급자가 확인한 이메일만
    suggested_nickname VARCHAR(20)  NULL,
    expires_at         DATETIME(6)  NOT NULL,
    used_at            DATETIME(6)  NULL,
    created_at         DATETIME(6)  NOT NULL,
    updated_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_oauth_ticket_hash (token_hash),
    KEY idx_oauth_ticket_expires (expires_at)
);
