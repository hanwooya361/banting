package com.banting.payment;

/** 결제 방식 (T16.5). */
public enum PaymentMethod {
    TOSS,   // 토스 결제 (실제 공구, 체험 공구의 방문자 몫)
    DEMO    // 체험 봇 결제. 토스를 부르지 않고, 체험 공구에서만 생긴다. 환불도 토스를 부르지 않음
}
