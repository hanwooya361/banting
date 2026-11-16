package com.banting.groupbuy;

/** 공구 상태 (tech-design 3장). 전이는 GroupBuy 엔티티 메서드에서만 한다. */
public enum GroupBuyStatus {
    DRAFT,      // 개설 대기: 개설자 몫 결제 전, 목록에 안 보임
    OPEN,       // 모집 중
    CONFIRMED,  // 확정: 정원이 다 참
    PICKUP,     // 수령 대기: 개설자가 구매함
    COMPLETED,  // 완료
    FAILED      // 실패: 마감 미달·개설자 취소 → 전원 환불
}
