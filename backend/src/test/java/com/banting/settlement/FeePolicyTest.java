package com.banting.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 정산 수수료 (T16). 규칙:
 * - 수수료율은 만분율 정수(bps, 300 = 3%)로 받아 long으로만 계산한다 (부동소수점 금지)
 * - 수수료는 원 단위 내림 (개설자에게 유리하게), 정산액 = 총액 − 수수료 → 합계가 항상 총액과 같다
 */
class FeePolicyTest {

    @ParameterizedTest(name = "{0}원 × {1}bps → 수수료 {2}원, 정산 {3}원")
    @CsvSource({
            "30000, 300, 900, 29100",     // 휴지 30롤 3만 원, 3% (PRD 대표 시나리오)
            "10001, 300, 300, 9701",      // 300.03원 → 300원 (내림)
            "33, 300, 0, 33",             // 0.99원 → 0원
            "99999, 300, 2999, 97000",    // 2999.97원 → 2999원
            "30000, 0, 0, 30000",         // 수수료 없음
            "30000, 10000, 30000, 0",     // 100% (상한)
    })
    void 수수료는_내림이고_수수료와_정산액의_합은_총액(long gross, int bps, long fee, long net) {
        FeePolicy.Fee result = new FeePolicy(bps).calculate(gross);

        assertThat(result.gross()).isEqualTo(gross);
        assertThat(result.fee()).isEqualTo(fee);
        assertThat(result.net()).isEqualTo(net);
        assertThat(result.fee() + result.net()).isEqualTo(gross);
    }

    @Test
    void 기록용_수수료율은_소수_넷째_자리까지() {
        assertThat(new FeePolicy(300).rate()).isEqualByComparingTo(new BigDecimal("0.0300"));
        assertThat(new FeePolicy(300).rate().scale()).isEqualTo(4);   // DECIMAL(5,4)
        assertThat(new FeePolicy(25).rate()).isEqualByComparingTo(new BigDecimal("0.0025"));
    }

    @Test
    void 큰_금액도_넘치지_않는다() {
        // 1천억 원 × 300: long 범위 안. 곱셈 넘침은 Math.multiplyExact가 예외로 막는다
        assertThat(new FeePolicy(300).calculate(100_000_000_000L).fee()).isEqualTo(3_000_000_000L);
    }

    @Test
    void 수수료율은_0에서_100퍼센트_사이만() {
        assertThatThrownBy(() -> new FeePolicy(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FeePolicy(10_001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 음수_총액은_계산하지_않는다() {
        assertThatThrownBy(() -> new FeePolicy(300).calculate(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
