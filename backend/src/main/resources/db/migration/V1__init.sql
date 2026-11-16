-- 반띵 초기 스키마 (docs/tech-design.md 2장 ERD)
-- 규칙
--  * 금액은 원 단위 BIGINT, 비율만 DECIMAL
--  * 상태값은 VARCHAR + JPA @Enumerated(STRING) (ENUM 타입은 값 추가 시 테이블 변경이 필요해 쓰지 않음)
--  * 시각은 DATETIME(6), 앱이 Asia/Seoul 기준으로 채움 (DB 기본값을 두지 않아 DB와 앱의 시간대가 섞이지 않게)
--  * 좌표는 POINT SRID 4326. MySQL의 4326 축 순서는 (위도, 경도) → ST_PointFromText('POINT(위도 경도)', 4326)
--  * FK는 기본 RESTRICT, 공구 이미지만 공구와 함께 삭제(CASCADE)

CREATE TABLE users (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    email             VARCHAR(100) NOT NULL,
    password_hash     VARCHAR(100) NOT NULL,
    nickname          VARCHAR(20)  NOT NULL,
    role              VARCHAR(10)  NOT NULL,
    location          POINT SRID 4326 NULL,          -- 동네 기준 좌표 (검색은 group_buys 쪽에서 하므로 인덱스 없음)
    neighborhood_name VARCHAR(50)  NULL,             -- 화면에는 좌표 대신 이 이름만
    search_radius_m   INT          NOT NULL DEFAULT 1000,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    deleted_at        DATETIME(6)  NULL,             -- 탈퇴(소프트 삭제)
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email),
    UNIQUE KEY uk_users_nickname (nickname),
    CONSTRAINT ck_users_radius CHECK (search_radius_m IN (500, 1000, 3000))
);

CREATE TABLE group_buys (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    host_id              BIGINT       NOT NULL,
    title                VARCHAR(100) NOT NULL,
    description          TEXT         NOT NULL,
    category             VARCHAR(20)  NOT NULL,
    product_url          VARCHAR(500) NOT NULL,
    price_type           VARCHAR(15)  NOT NULL,      -- EQUAL / BY_QUANTITY
    total_price          BIGINT       NOT NULL,
    total_quantity       INT          NOT NULL,
    unit_label           VARCHAR(10)  NOT NULL,
    capacity_units       INT          NOT NULL,      -- EQUAL = 인원 수, BY_QUANTITY = total_quantity
    min_units_per_person INT          NOT NULL DEFAULT 1,
    reserved_units       INT          NOT NULL DEFAULT 0,  -- 결제 대기 + 결제 완료 (동시성 카운터)
    paid_units           INT          NOT NULL DEFAULT 0,
    status               VARCHAR(15)  NOT NULL,      -- DRAFT / OPEN / CONFIRMED / PICKUP / COMPLETED / FAILED
    fail_reason          VARCHAR(20)  NULL,          -- DEADLINE_UNMET / HOST_CANCELED
    location             POINT SRID 4326 NOT NULL,   -- SPATIAL INDEX는 NOT NULL + SRID 고정이 필요
    neighborhood_name    VARCHAR(50)  NOT NULL,
    deadline_at          DATETIME(6)  NOT NULL,
    confirmed_at         DATETIME(6)  NULL,
    completed_at         DATETIME(6)  NULL,
    version              BIGINT       NOT NULL DEFAULT 0,  -- 낙관적 락 (상태 전이)
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_group_buys_status_deadline (status, deadline_at),   -- 스케줄러: 마감 지난 OPEN 공구
    SPATIAL KEY sp_group_buys_location (location),               -- 반경 검색
    CONSTRAINT fk_group_buys_host FOREIGN KEY (host_id) REFERENCES users (id),
    CONSTRAINT ck_group_buys_price CHECK (total_price > 0 AND total_quantity > 0 AND capacity_units > 0),
    -- 정원 초과의 마지막 방어선: 애플리케이션 버그나 동시성 실수가 있어도 DB가 거부 (T13)
    CONSTRAINT ck_group_buys_units CHECK (reserved_units BETWEEN 0 AND capacity_units
                                          AND paid_units BETWEEN 0 AND reserved_units)
);

CREATE TABLE group_buy_images (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    group_buy_id BIGINT       NOT NULL,
    url          VARCHAR(500) NOT NULL,
    sort_order   INT          NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_group_buy_images_order (group_buy_id, sort_order),
    CONSTRAINT fk_group_buy_images_group_buy FOREIGN KEY (group_buy_id) REFERENCES group_buys (id) ON DELETE CASCADE
);

-- 한 공구에 한 사람당 한 행. 결제 대기 만료·결제 실패(CANCELED)면 같은 행을 다시 PENDING_PAYMENT로 재사용하고,
-- 결제 후 본인 취소(REFUNDED)면 재참여를 막는다 (tech-design 2장, 2026-11-16 결정)
CREATE TABLE participations (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    group_buy_id BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    is_host      BOOLEAN     NOT NULL,
    units        INT         NOT NULL,
    amount       BIGINT      NOT NULL,
    status       VARCHAR(20) NOT NULL,   -- PENDING_PAYMENT / PAID / CANCELED / REFUNDED / RECEIVED
    expires_at   DATETIME(6) NOT NULL,   -- 결제 대기 만료 (생성·재사용 시각 + 10분)
    received_at  DATETIME(6) NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_participations_group_buy_user (group_buy_id, user_id),
    KEY idx_participations_status_expires (status, expires_at),  -- 스케줄러: 만료된 결제 대기
    KEY idx_participations_user (user_id),
    CONSTRAINT fk_participations_group_buy FOREIGN KEY (group_buy_id) REFERENCES group_buys (id),
    CONSTRAINT fk_participations_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_participations_amount CHECK (units > 0 AND amount > 0)
);

CREATE TABLE payments (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    participation_id BIGINT       NOT NULL,
    order_id         VARCHAR(64)  NOT NULL,   -- 서버 발급, 토스 orderId (멱등 키)
    payment_key      VARCHAR(200) NULL,       -- 토스 승인 후 저장
    amount           BIGINT       NOT NULL,
    status           VARCHAR(15)  NOT NULL,   -- READY / DONE / CANCELED / FAILED
    approved_at      DATETIME(6)  NULL,
    canceled_at      DATETIME(6)  NULL,
    cancel_reason    VARCHAR(100) NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payments_order_id (order_id),
    KEY idx_payments_participation (participation_id),
    CONSTRAINT fk_payments_participation FOREIGN KEY (participation_id) REFERENCES participations (id),
    CONSTRAINT ck_payments_amount CHECK (amount > 0)
);

CREATE TABLE pickup_options (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    group_buy_id BIGINT       NOT NULL,
    pickup_at    DATETIME(6)  NOT NULL,
    place_name   VARCHAR(100) NOT NULL,
    selected     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_pickup_options_group_buy (group_buy_id),
    CONSTRAINT fk_pickup_options_group_buy FOREIGN KEY (group_buy_id) REFERENCES group_buys (id)
);

-- 공구당 1표, 다시 투표하면 pickup_option_id만 변경
CREATE TABLE pickup_votes (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    group_buy_id     BIGINT      NOT NULL,
    pickup_option_id BIGINT      NOT NULL,
    user_id          BIGINT      NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pickup_votes_group_buy_user (group_buy_id, user_id),
    KEY idx_pickup_votes_option (pickup_option_id),
    CONSTRAINT fk_pickup_votes_group_buy FOREIGN KEY (group_buy_id) REFERENCES group_buys (id),
    CONSTRAINT fk_pickup_votes_option FOREIGN KEY (pickup_option_id) REFERENCES pickup_options (id),
    CONSTRAINT fk_pickup_votes_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE settlements (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    group_buy_id BIGINT       NOT NULL,
    host_id      BIGINT       NOT NULL,
    gross_amount BIGINT       NOT NULL,
    fee_rate     DECIMAL(5,4) NOT NULL,   -- 예: 0.0300 (계산 결과 금액은 BIGINT로 저장)
    fee_amount   BIGINT       NOT NULL,
    net_amount   BIGINT       NOT NULL,
    status       VARCHAR(10)  NOT NULL,   -- PENDING / DONE
    settled_at   DATETIME(6)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlements_group_buy (group_buy_id),   -- 공구당 정산 1건 (중복 정산 방지)
    KEY idx_settlements_host (host_id),
    CONSTRAINT fk_settlements_group_buy FOREIGN KEY (group_buy_id) REFERENCES group_buys (id),
    CONSTRAINT fk_settlements_host FOREIGN KEY (host_id) REFERENCES users (id),
    CONSTRAINT ck_settlements_amount CHECK (gross_amount = fee_amount + net_amount AND fee_amount >= 0)
);

CREATE TABLE inquiries (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    type        VARCHAR(10)  NOT NULL,   -- BUG / FEATURE / ETC
    title       VARCHAR(100) NOT NULL,
    content     TEXT         NOT NULL,
    status      VARCHAR(10)  NOT NULL,   -- OPEN / ANSWERED
    answer      TEXT         NULL,
    answered_at DATETIME(6)  NULL,
    issue_url   VARCHAR(300) NULL,       -- 연결된 GitHub Issue
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_inquiries_user (user_id),
    KEY idx_inquiries_status (status, created_at),
    CONSTRAINT fk_inquiries_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE notices (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    title      VARCHAR(100) NOT NULL,
    content    TEXT         NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notices_created (created_at)
);
