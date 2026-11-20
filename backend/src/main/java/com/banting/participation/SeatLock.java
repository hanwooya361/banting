package com.banting.participation;

/**
 * 참여 시 자리를 잡는 방식 (T13 비교). 설정 {@code banting.participation.seat-lock}, 기본 CONDITIONAL.
 *
 * <p>둘 다 공구 행 잠금으로 직렬화되므로 정원 초과는 없다. 차이는 잠금을 얼마나 오래, 몇 번 왕복하며 쥐는지.
 * 측정 결과는 docs/tech-design.md "마지막 자리 동시성".
 */
public enum SeatLock {
    /** 확인과 증가를 UPDATE 한 문장으로 (WHERE reserved_units + :u <= capacity_units). */
    CONDITIONAL,
    /** SELECT … FOR UPDATE로 공구 행을 잠그고 Java에서 확인 후 증가. */
    PESSIMISTIC
}
