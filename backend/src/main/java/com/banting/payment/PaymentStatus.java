package com.banting.payment;

public enum PaymentStatus {
    READY,     // 주문번호 발급, 결제창 진행 중
    DONE,      // 토스 승인 + 우리 DB 반영 완료
    CANCELED,  // 승인 후 취소됨 (보상 취소, T12 환불)
    FAILED     // 승인 거절·응답 없음·새 주문으로 대체·보상 취소 실패(수동 확인)
}
