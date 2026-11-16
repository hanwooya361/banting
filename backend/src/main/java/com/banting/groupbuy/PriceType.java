package com.banting.groupbuy;

public enum PriceType {
    /** 균등 n분의 1. 한 사람이 한 자리(unit). capacity = 인원 수 */
    EQUAL,
    /** 수량별. 한 사람이 여러 개(unit)를 가져가고 단가 × 수량을 낸다. capacity = 총수량 */
    BY_QUANTITY
}
