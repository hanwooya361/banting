package com.banting.payment;

import com.banting.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제 한 건 = 토스 주문 한 건. 참여 하나에 여러 건이 생길 수 있다 (거절 후 재시도 등). 성공(DONE)은 최대 한 건.
 * 상태 전이: READY → DONE / FAILED, READY → CANCELED(승인됐지만 DB 반영 실패로 보상 취소)
 */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseTimeEntity {

    private static final int REASON_MAX = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "participation_id", nullable = false)
    private Long participationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentMethod method;

    @Column(name = "order_id", nullable = false, length = 64, unique = true)
    private String orderId;

    @Column(name = "payment_key", length = 200)
    private String paymentKey;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private PaymentStatus status;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    /** 취소·실패 사유 (토스 오류 코드 등). */
    @Column(name = "cancel_reason", length = REASON_MAX)
    private String cancelReason;

    /**
     * 주문번호는 서버가 만든다. 토스 규칙(영문·숫자·-·_ 6~64자)에 맞추고, 추측할 수 없게 무작위.
     * 금액도 서버가 계산한 값만 저장하고, 승인 때 브라우저가 보낸 금액과 비교한다.
     */
    public static Payment ready(Long participationId, long amount) {
        Payment p = new Payment();
        p.participationId = participationId;
        p.orderId = "bt-" + UUID.randomUUID();
        p.amount = amount;
        p.method = PaymentMethod.TOSS;
        p.status = PaymentStatus.READY;
        return p;
    }

    public boolean isDone() {
        return status == PaymentStatus.DONE;
    }

    public boolean isDemo() {
        return method == PaymentMethod.DEMO;
    }

    public boolean isReady() {
        return status == PaymentStatus.READY;
    }

    public void done(String paymentKey, LocalDateTime approvedAt) {
        requireReady();
        this.paymentKey = paymentKey;
        this.approvedAt = approvedAt;
        this.status = PaymentStatus.DONE;
    }

    /**
     * 체험 봇 결제 (T16.5): 토스 승인 없이 DONE. 체험 공구인지는 호출 쪽(PaymentService.confirmDemo)이 확인한다.
     * 결제 방식이 DEMO로 남아 환불 때 토스를 부르지 않는다.
     */
    public void doneByDemo(LocalDateTime approvedAt) {
        requireReady();
        this.method = PaymentMethod.DEMO;
        this.paymentKey = "demo-" + orderId;
        this.approvedAt = approvedAt;
        this.status = PaymentStatus.DONE;
    }

    /** 승인 거절, 응답 없음, 새 주문으로 대체 등. paymentKey는 알면 남긴다 (나중에 토스에서 조회할 수 있게). */
    public void fail(String paymentKey, String reason) {
        requireReady();
        if (paymentKey != null) {
            this.paymentKey = paymentKey;
        }
        this.cancelReason = truncate(reason);
        this.status = PaymentStatus.FAILED;
    }

    /** 토스에서는 승인됐지만 우리 DB 반영이 실패해 취소한 경우 (보상). */
    public void compensated(String paymentKey, String reason, LocalDateTime canceledAt) {
        requireReady();
        this.paymentKey = paymentKey;
        this.cancelReason = truncate(reason);
        this.canceledAt = canceledAt;
        this.status = PaymentStatus.CANCELED;
    }

    /** 승인된 결제를 환불(토스 전액 취소)했음. */
    public void refunded(String reason, LocalDateTime canceledAt) {
        if (status != PaymentStatus.DONE) {
            throw new IllegalStateException("승인된 결제만 환불할 수 있습니다: " + status);
        }
        this.cancelReason = truncate(reason);
        this.canceledAt = canceledAt;
        this.status = PaymentStatus.CANCELED;
    }

    private void requireReady() {
        if (status != PaymentStatus.READY) {
            throw new IllegalStateException("결제 대기 상태가 아닙니다: " + status);
        }
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= REASON_MAX ? reason : reason.substring(0, REASON_MAX);
    }
}
