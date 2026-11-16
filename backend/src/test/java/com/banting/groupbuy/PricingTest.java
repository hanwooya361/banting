package com.banting.groupbuy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 금액 계산 (tech-design 4장, T10). 규칙:
 * - 모든 금액은 원 단위 long, 나눗셈은 내림
 * - 나누어떨어지지 않아 남는 원은 개설자 몫에 더한다 → 정원이 다 차면 합계가 총가와 정확히 같다
 */
class PricingTest {

    @Nested
    class 균등 {

        @Test
        void 나누어떨어지면_모두_같은_금액() {
            Pricing pricing = Pricing.equal(30_000, 3);   // 휴지 30롤 3명 (PRD 대표 시나리오)

            assertThat(pricing.participantAmount(1)).isEqualTo(10_000);
            assertThat(pricing.hostAmount(1)).isEqualTo(10_000);
            assertThat(pricing.capacityUnits()).isEqualTo(3);
        }

        @Test
        void 남는_원은_개설자가_낸다() {
            Pricing pricing = Pricing.equal(10_000, 3);   // tech-design 예시

            assertThat(pricing.participantAmount(1)).isEqualTo(3_333);
            assertThat(pricing.hostAmount(1)).isEqualTo(3_334);
            assertThat(pricing.remainder()).isEqualTo(1);
        }

        @Test
        void 균등은_한_사람이_한_자리만_차지한다() {
            Pricing pricing = Pricing.equal(10_000, 4);
            assertThatThrownBy(() -> pricing.participantAmount(2)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> pricing.hostAmount(2)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 인원은_2명_이상() {
            assertThatThrownBy(() -> Pricing.equal(10_000, 1)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 일인_금액이_1원_미만이면_거부() {
            assertThatThrownBy(() -> Pricing.equal(2, 3)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class 수량별 {

        @Test
        void 단가_곱하기_수량() {
            Pricing pricing = Pricing.byQuantity(30_000, 30);   // 단가 1,000원

            assertThat(pricing.unitPrice()).isEqualTo(1_000);
            assertThat(pricing.participantAmount(7)).isEqualTo(7_000);
            assertThat(pricing.hostAmount(10)).isEqualTo(10_000);
            assertThat(pricing.capacityUnits()).isEqualTo(30);
        }

        @Test
        void 나누어떨어지지_않으면_단가는_내림하고_남는_원은_개설자() {
            Pricing pricing = Pricing.byQuantity(25_900, 30);   // 단가 863.33…원

            assertThat(pricing.unitPrice()).isEqualTo(863);
            assertThat(pricing.remainder()).isEqualTo(10);      // 25,900 - 863 × 30
            assertThat(pricing.hostAmount(10)).isEqualTo(8_640);        // 8,630 + 10
            assertThat(pricing.participantAmount(10)).isEqualTo(8_630);
            // 10개씩 셋이 나누면 합계가 총가와 같다
            assertThat(pricing.hostAmount(10) + pricing.participantAmount(10) * 2).isEqualTo(25_900);
        }

        @Test
        void 수량은_1개_이상_전체_수량_이하() {
            Pricing pricing = Pricing.byQuantity(30_000, 30);
            assertThatThrownBy(() -> pricing.participantAmount(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> pricing.participantAmount(31)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void 단가가_1원_미만이면_거부() {
            assertThatThrownBy(() -> Pricing.byQuantity(29, 30)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 총가는_양수() {
        assertThatThrownBy(() -> Pricing.equal(0, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pricing.byQuantity(-1, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 어떤 조합이든 정원이 다 차면 개설자 몫 + 참여자 몫 합계 = 총가 (돈이 새거나 남지 않는다). */
    @ParameterizedTest(name = "균등 {0}원 / {1}명")
    @CsvSource({"10000, 3", "9999, 7", "1000003, 20", "10000000, 13", "101, 100"})
    void 균등_정원이_차면_합계가_총가와_같다(long total, int people) {
        Pricing pricing = Pricing.equal(total, people);
        long sum = pricing.hostAmount(1) + pricing.participantAmount(1) * (people - 1);
        assertThat(sum).isEqualTo(total);
    }

    @ParameterizedTest(name = "수량별 {0}원 / {1}개, 개설자 {2}개, 참여자 {3}")
    @CsvSource({
            "25900, 30, 10, '10;10'",
            "10000000, 9999, 1, '4999;4999'",
            "12345, 7, 3, '1;1;2'",
            "1000, 1000, 500, '250;250'"})
    void 수량별_정원이_차면_합계가_총가와_같다(long total, int quantity, int hostUnits, String others) {
        Pricing pricing = Pricing.byQuantity(total, quantity);
        long sum = pricing.hostAmount(hostUnits);
        int units = hostUnits;
        for (String u : others.split(";")) {
            sum += pricing.participantAmount(Integer.parseInt(u));
            units += Integer.parseInt(u);
        }
        assertThat(units).isEqualTo(quantity);
        assertThat(sum).isEqualTo(total);
    }
}
