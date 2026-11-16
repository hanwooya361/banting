-- refresh 토큰 (T06). 원문은 사용자 쿠키에만, DB에는 SHA-256 해시(hex 64자)만 저장
-- 재발급마다 폐기(revoked_at)하고 새로 발급(rotation). 폐기된 토큰이 다시 오면 탈취로 보고 회원의 토큰 전부 폐기
CREATE TABLE refresh_tokens (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    token_hash CHAR(64)    NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_tokens_hash (token_hash),
    KEY idx_refresh_tokens_user (user_id, revoked_at),   -- 탈취 감지 시 회원 토큰 일괄 폐기
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id)
);
