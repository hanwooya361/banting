package com.banting.payment;

import java.time.OffsetDateTime;

/**
 * 토스페이먼츠 결제 API. 인터페이스로 분리해 테스트에서는 네트워크 없이 가짜 구현을 쓴다.
 *
 * <p>실패는 두 종류로 나눈다.
 * <ul>
 *   <li>{@link TossRejectedException}: 토스가 오류 코드로 확실히 거절 (돈이 나가지 않음)</li>
 *   <li>{@link TossUnavailableException}: 타임아웃·5xx 등 결과를 모름 (승인됐을 수도 있음 → 호출 쪽이 취소로 정리)</li>
 * </ul>
 */
public interface TossPaymentsClient {

    /** 결제 승인. 같은 orderId로 다시 부르면 토스가 첫 응답을 그대로 돌려준다 (Idempotency-Key). */
    Approval confirm(String paymentKey, String orderId, long amount);

    /** 결제 전액 취소. */
    void cancel(String paymentKey, String orderId, String reason);

    record Approval(String paymentKey, String orderId, String status, long totalAmount, OffsetDateTime approvedAt) {
    }

    class TossRejectedException extends RuntimeException {
        private final String code;

        public TossRejectedException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    class TossUnavailableException extends RuntimeException {
        public TossUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
