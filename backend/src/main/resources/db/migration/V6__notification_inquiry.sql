-- T17 문의 답변 알림. 공구와 상관없는 알림이 생겨 group_buy_id를 NULL 허용으로 바꾸되,
-- 공구 알림(GROUP_BUY_*)은 여전히 공구가 있어야 하고 문의 답변 알림은 공구가 없어야 하도록 CHECK로 묶는다
ALTER TABLE notifications
    MODIFY group_buy_id BIGINT NULL,
    ADD CONSTRAINT ck_notifications_group_buy CHECK ((type = 'INQUIRY_ANSWERED') = (group_buy_id IS NULL));
