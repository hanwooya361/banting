package com.banting.settlement;

public enum SettlementStatus {
    PENDING,   // 정산 기록만 생성 (실제 송금은 구현하지 않음, PRD "정산은 기록까지만")
    DONE
}
