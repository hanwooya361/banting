package com.banting.groupbuy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

/** GroupBuy 도메인 규칙 단위 테스트 (DB 없이). */
class GroupBuyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 12, 0);
    private static final String WKT = "POINT(37.567 126.978)";

    private static GroupBuy.Spec equal(int people, String url, LocalDateTime deadline) {
        return new GroupBuy.Spec("휴지 30롤", "반띵해요", Category.LIVING, url, PriceType.EQUAL,
                30_000, 30, "롤", people, null, null, deadline);
    }

    private static GroupBuy.Spec byQuantity(int quantity, Integer min, Integer hostUnits) {
        return new GroupBuy.Spec("계란 30구", "", Category.FOOD, "https://example.com/egg", PriceType.BY_QUANTITY,
                9_000, quantity, "구", null, min, hostUnits, NOW.plusDays(2));
    }

    private static ErrorCode errorOf(Runnable action) {
        try {
            action.run();
        } catch (BusinessException e) {
            return e.getErrorCode();
        }
        throw new AssertionError("예외가 나지 않았음");
    }

    @Test
    void 균등_공구는_DRAFT로_만들고_개설자_한_자리를_미리_잡는다() {
        GroupBuy g = GroupBuy.create(1L, equal(3, "https://example.com/p", NOW.plusDays(3)), WKT, "중구 명동", NOW);

        assertThat(g.getStatus()).isEqualTo(GroupBuyStatus.DRAFT);
        assertThat(g.getCapacityUnits()).isEqualTo(3);
        assertThat(g.getReservedUnits()).isEqualTo(1);
        assertThat(g.getPaidUnits()).isZero();
        assertThat(g.pricing().hostAmount(1)).isEqualTo(10_000);
    }

    @Test
    void 수량별_공구는_개설자_몫만큼_자리를_잡는다() {
        GroupBuy g = GroupBuy.create(1L, byQuantity(30, 5, 10), WKT, "중구 명동", NOW);
        assertThat(g.getCapacityUnits()).isEqualTo(30);
        assertThat(g.getMinUnitsPerPerson()).isEqualTo(5);
        assertThat(g.getReservedUnits()).isEqualTo(10);
        assertThat(g.pricing().hostAmount(10)).isEqualTo(3_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "example.com/p", "javascript:alert(1)", "ftp://example.com/p",
            "https://", "data:text/html,hi", "http//example.com"})
    void 상품_링크는_http_https_주소만_받는다(String url) {
        assertThat(errorOf(() -> GroupBuy.create(1L, equal(3, url, NOW.plusDays(1)), WKT, "동", NOW)))
                .isEqualTo(ErrorCode.INVALID_PRODUCT_URL);
    }

    @Test
    void 상품_링크는_앞뒤_공백을_정리해_저장한다() {
        GroupBuy g = GroupBuy.create(1L, equal(3, "  HTTPS://Coupang.com/vp/1  ", NOW.plusDays(1)), WKT, "동", NOW);
        assertThat(g.getProductUrl()).isEqualTo("HTTPS://Coupang.com/vp/1");
    }

    @Test
    void 마감은_1시간_뒤부터_14일_안() {
        assertThat(errorOf(() -> GroupBuy.create(1L, equal(3, "https://e.com", NOW.plusMinutes(59)), WKT, "동", NOW)))
                .isEqualTo(ErrorCode.INVALID_DEADLINE);
        assertThat(errorOf(() -> GroupBuy.create(1L, equal(3, "https://e.com", NOW.plusDays(14).plusMinutes(1)), WKT, "동", NOW)))
                .isEqualTo(ErrorCode.INVALID_DEADLINE);
        GroupBuy.create(1L, equal(3, "https://e.com", NOW.plusHours(1)), WKT, "동", NOW);
        GroupBuy.create(1L, equal(3, "https://e.com", NOW.plusDays(14)), WKT, "동", NOW);
    }

    @Test
    void 균등은_2명부터_20명까지() {
        assertThat(errorOf(() -> GroupBuy.create(1L, equal(1, "https://e.com", NOW.plusDays(1)), WKT, "동", NOW)))
                .isEqualTo(ErrorCode.INVALID_UNITS);
        assertThat(errorOf(() -> GroupBuy.create(1L, equal(21, "https://e.com", NOW.plusDays(1)), WKT, "동", NOW)))
                .isEqualTo(ErrorCode.INVALID_UNITS);
    }

    @Test
    void 수량별은_개설자_몫을_빼고도_한_명이_참여할_자리가_있어야_한다() {
        // 30개 중 개설자 26개, 최소 5개 → 남은 4개로는 아무도 참여 못 함
        assertThat(errorOf(() -> GroupBuy.create(1L, byQuantity(30, 5, 26), WKT, "동", NOW)))
                .isEqualTo(ErrorCode.INVALID_UNITS);
        // 개설자도 최소 수량 이상
        assertThat(errorOf(() -> GroupBuy.create(1L, byQuantity(30, 5, 4), WKT, "동", NOW)))
                .isEqualTo(ErrorCode.INVALID_UNITS);
        assertThat(errorOf(() -> GroupBuy.create(1L, byQuantity(30, null, 10), WKT, "동", NOW)))
                .isEqualTo(ErrorCode.INVALID_UNITS);
        GroupBuy.create(1L, byQuantity(30, 5, 25), WKT, "동", NOW);
    }

    @Test
    void 한_자리당_1원_미만이_되는_금액은_거부() {
        GroupBuy.Spec spec = new GroupBuy.Spec("쌀", "", Category.FOOD, "https://e.com", PriceType.BY_QUANTITY,
                1_000, 10_000, "g", null, 1, 1, NOW.plusDays(1));
        assertThat(errorOf(() -> GroupBuy.create(1L, spec, WKT, "동", NOW))).isEqualTo(ErrorCode.INVALID_PRICE);
    }

    @Test
    void 마감은_늦추기만_할_수_있다() {
        GroupBuy g = GroupBuy.create(1L, equal(3, "https://e.com", NOW.plusDays(3)), WKT, "동", NOW);
        assertThat(errorOf(() -> g.extendDeadline(NOW.plusDays(2), NOW))).isEqualTo(ErrorCode.DEADLINE_ONLY_EXTEND);
        g.extendDeadline(NOW.plusDays(5), NOW);
        assertThat(g.getDeadlineAt()).isEqualTo(NOW.plusDays(5));
    }

    @Test
    void 개설자가_아니면_수정할_수_없다() {
        GroupBuy g = GroupBuy.create(1L, equal(3, "https://e.com", NOW.plusDays(3)), WKT, "동", NOW);
        assertThatThrownBy(() -> g.checkHost(2L)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_GROUP_BUY_HOST));
        g.checkHost(1L);
    }

    // ───── 결제 반영 (T11) ─────

    @Test
    void 개설자_결제가_반영되면_모집_중이_되고_마지막_자리가_결제되면_확정() {
        GroupBuy g = GroupBuy.create(1L, equal(3, "https://e.com", NOW.plusDays(3)), WKT, "동", NOW);

        g.markPaid(1, true, NOW);
        assertThat(g.getStatus()).isEqualTo(GroupBuyStatus.OPEN);

        ReflectionTestUtils.setField(g, "reservedUnits", 3);   // 두 이웃이 자리를 잡은 상태 (조건부 UPDATE가 하는 일)
        g.markPaid(1, false, NOW);
        assertThat(g.getStatus()).isEqualTo(GroupBuyStatus.OPEN);
        g.markPaid(1, false, NOW.plusHours(1));
        assertThat(g.getStatus()).isEqualTo(GroupBuyStatus.CONFIRMED);
        assertThat(g.getConfirmedAt()).isEqualTo(NOW.plusHours(1));
        assertThat(g.getPaidUnits()).isEqualTo(3);
    }

    @Test
    void 정해진_상태에서만_결제를_반영하고_잡힌_자리보다_많이_결제될_수_없다() {
        GroupBuy g = GroupBuy.create(1L, equal(3, "https://e.com", NOW.plusDays(3)), WKT, "동", NOW);
        assertThatThrownBy(() -> g.markPaid(1, false, NOW)).isInstanceOf(IllegalStateException.class);   // DRAFT에 참여자 결제

        g.markPaid(1, true, NOW);
        assertThatThrownBy(() -> g.markPaid(1, true, NOW)).isInstanceOf(IllegalStateException.class);    // 개설자 두 번
        assertThatThrownBy(() -> g.markPaid(1, false, NOW)).isInstanceOf(IllegalStateException.class);   // 자리 안 잡고 결제
        assertThat(g.getPaidUnits()).isEqualTo(1);
    }

    // ───── 취소 (T12) ─────

    @Test
    void 취소하면_자리를_돌려주고_잡힌_자리보다_많이_돌려줄_수_없다() {
        GroupBuy g = GroupBuy.create(1L, equal(3, "https://e.com", NOW.plusDays(3)), WKT, "동", NOW);
        g.markPaid(1, true, NOW);
        ReflectionTestUtils.setField(g, "reservedUnits", 3);
        g.markPaid(1, false, NOW);   // 결제 2, 대기 1

        g.release(1, true);
        assertThat(g.getReservedUnits()).isEqualTo(2);
        assertThat(g.getPaidUnits()).isEqualTo(1);
        g.release(1, false);
        assertThat(g.getReservedUnits()).isEqualTo(1);
        assertThatThrownBy(() -> g.release(2, false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 확정_뒤나_마감_뒤에는_결제_취소를_할_수_없고_개설자_취소는_모집_중에만() {
        GroupBuy g = GroupBuy.create(1L, equal(2, "https://e.com", NOW.plusDays(3)), WKT, "동", NOW);
        assertThat(errorOf(g::cancelByHost)).isEqualTo(ErrorCode.GROUP_BUY_NOT_CANCELABLE);   // DRAFT는 삭제로

        g.markPaid(1, true, NOW);
        g.checkCancelable(NOW);
        assertThat(errorOf(() -> g.checkCancelable(NOW.plusDays(3)))).isEqualTo(ErrorCode.PARTICIPATION_NOT_CANCELABLE);

        g.cancelByHost();
        assertThat(g.getStatus()).isEqualTo(GroupBuyStatus.FAILED);
        assertThat(g.isCanceledByHost()).isTrue();
        assertThat(errorOf(() -> g.checkCancelable(NOW))).isEqualTo(ErrorCode.PARTICIPATION_NOT_CANCELABLE);
    }
}
