package com.banting.settlement;

import java.math.BigDecimal;

/**
 * 정산 수수료 (T16, PRD "거래당 수수료"). 개설자 정산 총액에서 떼고, 비율은 설정값 {@code banting.settlement.fee-bps}.
 *
 * <p>비율을 만분율 정수(bps, 300 = 3%)로 받아 long 곱셈·나눗셈만 쓴다. 0.03 같은 double로 계산하면
 * 30000 × 0.03 = 899.9999… 처럼 오차가 생겨 내림하면 1원이 틀어질 수 있다.
 * 수수료는 원 단위 내림(개설자에게 유리), 정산액 = 총액 − 수수료라 합계는 항상 총액과 같다 (DB CHECK도 같은 식).
 */
public record FeePolicy(int bps) {

    private static final int BPS_DENOMINATOR = 10_000;

    public FeePolicy {
        if (bps < 0 || bps > BPS_DENOMINATOR) {
            throw new IllegalArgumentException("수수료율은 0~10000bps: " + bps);
        }
    }

    public record Fee(long gross, long fee, long net) {
    }

    public Fee calculate(long gross) {
        if (gross < 0) {
            throw new IllegalArgumentException("정산 총액은 0 이상: " + gross);
        }
        long fee = Math.multiplyExact(gross, bps) / BPS_DENOMINATOR;   // 양수 정수 나눗셈 = 내림
        return new Fee(gross, fee, gross - fee);
    }

    /** settlements.fee_rate(DECIMAL(5,4)) 기록용. 300bps → 0.0300 */
    public BigDecimal rate() {
        return BigDecimal.valueOf(bps, 4);
    }
}
