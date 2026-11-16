package com.banting.participation;

public enum ParticipationStatus {
    PENDING_PAYMENT,  // 자리를 잡고 결제 대기 (10분)
    PAID,
    CANCELED,         // 결제 대기 만료·결제 실패 → 같은 행으로 재참여 가능
    REFUNDED,         // 결제 후 취소·공구 실패
    RECEIVED          // 수령 확인
}
