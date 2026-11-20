-- 인앱 알림 (T15). 상태가 바뀌는 트랜잭션 안에서 같이 쓴다 → "확정됐는데 알림이 없는" 불일치가 없다
-- message는 만들 때 완성된 문장으로 저장 (나중에 공구 정보가 바뀌어도 받은 알림 내용은 그대로)
CREATE TABLE notifications (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NOT NULL,
    type         VARCHAR(30)  NOT NULL,   -- GROUP_BUY_CONFIRMED / GROUP_BUY_FAILED / GROUP_BUY_CANCELED
    group_buy_id BIGINT       NOT NULL,
    message      VARCHAR(200) NOT NULL,
    read_at      DATETIME(6)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notifications_user (user_id, id),   -- 내 알림 최신순, 안 읽은 수
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_notifications_group_buy FOREIGN KEY (group_buy_id) REFERENCES group_buys (id)
);
