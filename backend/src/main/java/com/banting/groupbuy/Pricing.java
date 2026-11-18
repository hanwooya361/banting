package com.banting.groupbuy;

/**
 * 공구 금액 계산 (tech-design 4장 "금액 계산", T10).
 *
 * <p>규칙
 * <ul>
 *   <li>금액은 원 단위 long, 부동소수점을 쓰지 않는다 (CLAUDE.md)</li>
 *   <li>1자리당 금액(unitPrice) = floor(총가 / 정원). 균등이면 정원 = 인원, 수량별이면 정원 = 총수량</li>
 *   <li>나누어떨어지지 않아 남는 원(remainder)은 개설자 몫에 더한다 → 정원이 다 차면 합계가 총가와 정확히 같다.
 *       참여자는 항상 같은 단가를 내고, 개설자가 최대 (정원 - 1)원을 더 낸다</li>
 *   <li>서버가 계산한 금액만 신뢰한다. 클라이언트가 보낸 금액은 비교용</li>
 * </ul>
 * 균등과 수량별이 "단가 × 자리 수 (+ 개설자 나머지)"라는 같은 식으로 계산된다. 균등은 한 사람이 한 자리만 차지할 뿐.
 */
public record Pricing(PriceType type, long totalPrice, int capacityUnits, long unitPrice, long remainder) {

    public Pricing {
        if (totalPrice <= 0) {
            throw new IllegalArgumentException("총가는 1원 이상이어야 합니다");
        }
        if (unitPrice < 1) {
            throw new IllegalArgumentException("1자리당 금액이 1원 미만입니다");
        }
        if (unitPrice * capacityUnits + remainder != totalPrice) {
            throw new IllegalStateException("금액 계산 불일치");
        }
    }

    /** 균등: 총가를 인원 수로 나눈다. */
    public static Pricing equal(long totalPrice, int people) {
        if (people < 2) {
            throw new IllegalArgumentException("균등 공구는 2명 이상이어야 합니다");
        }
        return of(PriceType.EQUAL, totalPrice, people);
    }

    /** 수량별: 총가를 총수량으로 나눈 단가. */
    public static Pricing byQuantity(long totalPrice, int totalQuantity) {
        if (totalQuantity < 1) {
            throw new IllegalArgumentException("총수량은 1개 이상이어야 합니다");
        }
        return of(PriceType.BY_QUANTITY, totalPrice, totalQuantity);
    }

    /** 이미 검증된 공구(DB 행)의 금액을 다시 계산할 때. 새 공구는 equal / byQuantity로 검증하며 만든다. */
    static Pricing of(PriceType type, long totalPrice, int capacityUnits) {
        if (totalPrice <= 0) {
            throw new IllegalArgumentException("총가는 1원 이상이어야 합니다");
        }
        long unitPrice = totalPrice / capacityUnits;
        return new Pricing(type, totalPrice, capacityUnits, unitPrice, totalPrice - unitPrice * capacityUnits);
    }

    /** 참여자 결제 금액. */
    public long participantAmount(int units) {
        checkUnits(units);
        return Math.multiplyExact(unitPrice, units);
    }

    /** 개설자 결제 금액 = 단가 × 자리 수 + 남는 원. */
    public long hostAmount(int units) {
        return Math.addExact(participantAmount(units), remainder);
    }

    private void checkUnits(int units) {
        if (type == PriceType.EQUAL && units != 1) {
            throw new IllegalArgumentException("균등 공구는 한 사람이 한 자리만 차지합니다");
        }
        if (units < 1 || units > capacityUnits) {
            throw new IllegalArgumentException("자리 수는 1 이상 " + capacityUnits + " 이하여야 합니다");
        }
    }
}
