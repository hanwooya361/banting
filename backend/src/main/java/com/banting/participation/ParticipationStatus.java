package com.banting.participation;

public enum ParticipationStatus {
    PENDING_PAYMENT,  // 자리를 잡고 결제 대기 (10분)
    PAID,
    CANCELED,         // 결제 대기 만료·결제 실패 → 같은 행으로 재참여 가능
    REFUND_PENDING,   // 환불 대기: 자리는 이미 반환, 토스 결제 취소가 끝나면 REFUNDED (실패하면 재시도, T12)
    REFUNDED,         // 결제 후 취소·공구 실패
    RECEIVED          // 수령 확인
}
