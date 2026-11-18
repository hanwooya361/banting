-- 데모 공구 구분 (T09.5, tech-design 4장 "시드 데이터와 체험 모드")
-- NULL = 실제 공구, SEED = 시드(체험 동네 예시 공구), TRIAL = 체험 모드 공구(T16.5)
-- BOOLEAN 하나로는 "첫 화면에 보일 시드"와 "목록에서 빠질 체험 공구"를 구분할 수 없어 종류를 둔다
ALTER TABLE group_buys
    ADD COLUMN demo_type VARCHAR(10) NULL AFTER fail_reason,
    ADD CONSTRAINT ck_group_buys_demo_type CHECK (demo_type IS NULL OR demo_type IN ('SEED', 'TRIAL'));
