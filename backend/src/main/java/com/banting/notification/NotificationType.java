package com.banting.notification;

public enum NotificationType {
    GROUP_BUY_CONFIRMED,   // 다 모여서 확정, 수령 일정 안내
    GROUP_BUY_FAILED,      // 마감 미달 무산, 환불 안내
    GROUP_BUY_CANCELED     // 개설자 취소, 환불 안내
}
