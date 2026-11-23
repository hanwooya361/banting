package com.banting.notification;

public enum NotificationType {
    GROUP_BUY_CONFIRMED,   // 다 모여서 확정, 수령 일정 안내
    GROUP_BUY_FAILED,      // 마감 미달 무산, 환불 안내
    GROUP_BUY_CANCELED,    // 개설자 취소, 환불 안내
    GROUP_BUY_PURCHASED,   // 개설자가 상품을 삼, 수령 안내 (T16)
    GROUP_BUY_COMPLETED,   // 완료, 개설자에게 정산 예정 금액 (T16)
    INQUIRY_ANSWERED       // 문의에 답변이 달림 (T17). 공구가 없어 group_buy_id = null, 화면은 /inquiries로
}
