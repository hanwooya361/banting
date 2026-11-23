-- T16.5 체험 모드 (tech-design 4장 "시드 데이터와 체험 모드")

-- 결제 방식: TOSS = 토스 결제(방문자·실제 공구), DEMO = 체험 봇 결제(토스를 부르지 않음, 체험 공구에서만)
-- 환불·보상 취소는 DEMO면 토스를 부르지 않는다. 기존 행은 모두 토스 결제라 기본값 TOSS
ALTER TABLE payments
    ADD COLUMN method VARCHAR(10) NOT NULL DEFAULT 'TOSS' AFTER participation_id,
    ADD CONSTRAINT ck_payments_method CHECK (method IN ('TOSS', 'DEMO'));

-- 체험 공구를 만든 방문자. 체험 공구(TRIAL)만 값이 있고, 그 방문자만 보고 참여할 수 있다
-- 회원당 진행 중 체험 1개·하루 횟수 제한도 이 칸으로 센다 (trial_user_id, created_at)
ALTER TABLE group_buys
    ADD COLUMN trial_user_id BIGINT NULL AFTER demo_type,
    ADD KEY idx_group_buys_trial_user (trial_user_id, created_at),
    ADD CONSTRAINT fk_group_buys_trial_user FOREIGN KEY (trial_user_id) REFERENCES users (id),
    -- demo_type이 NULL이면 비교 결과가 NULL(=통과)이 되므로 COALESCE로 막는다
    ADD CONSTRAINT ck_group_buys_trial_user CHECK (
        (COALESCE(demo_type, '') = 'TRIAL') = (trial_user_id IS NOT NULL));
