package com.banting.payment;

import com.banting.participation.Participation;
import com.banting.participation.ParticipationRepository;
import com.banting.payment.TossPaymentsClient.TossRejectedException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 환불 한 건 처리 (T12). 참여 취소·개설자 취소가 쓰고, 마감 실패 환불·재시도(T14 스케줄러)도 이걸 쓴다.
 *
 * <p>호출 전에 참여는 이미 REFUND_PENDING이고 자리도 반환된 상태다 (짧은 트랜잭션에서 처리).
 * 여기서는 토스 취소(트랜잭션 밖) → 성공하면 짧은 트랜잭션으로 REFUNDED·결제 CANCELED.
 * 토스 취소가 실패하면 REFUND_PENDING 그대로 두어 다시 부르면 이어서 처리한다 (멱등: 토스에도 Idempotency-Key).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private static final String ALREADY_CANCELED = "ALREADY_CANCELED_PAYMENT";

    private final ParticipationRepository participationRepository;
    private final PaymentRepository paymentRepository;
    private final TossPaymentsClient toss;
    private final TransactionTemplate tx;
    private final Clock clock;

    /** @return 환불까지 끝났으면 true, 토스 취소가 실패해 환불 대기로 남았으면 false */
    public boolean refund(Long participationId, String reason) {
        Participation participation = participationRepository.findById(participationId).orElseThrow();
        if (!participation.isRefundPending()) {
            return participation.getStatus() == com.banting.participation.ParticipationStatus.REFUNDED;
        }
        List<Payment> done = paymentRepository.findByParticipationIdAndStatus(participationId, PaymentStatus.DONE);
        for (Payment payment : done) {   // 성공 결제는 최대 한 건
            try {
                toss.cancel(payment.getPaymentKey(), payment.getOrderId(), reason);
            } catch (TossRejectedException e) {
                if (!ALREADY_CANCELED.equals(e.getCode())) {
                    log.error("환불 거절, 환불 대기로 둠 participationId={} orderId={} code={}",
                            participationId, payment.getOrderId(), e.getCode());
                    return false;
                }
                // 이전 시도에서 토스는 취소됐지만 우리 기록 전에 끊긴 경우 → 이어서 기록
            } catch (RuntimeException e) {
                log.warn("환불 실패, 환불 대기로 둠 participationId={} orderId={}: {}",
                        participationId, payment.getOrderId(), e.getMessage());
                return false;
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        tx.executeWithoutResult(status -> {
            Participation locked = participationRepository.findByIdForUpdate(participationId).orElseThrow();
            if (!locked.isRefundPending()) {
                return;   // 동시에 다른 요청이 먼저 마무리함
            }
            paymentRepository.findByParticipationIdAndStatusForUpdate(participationId, PaymentStatus.DONE)
                    .forEach(payment -> payment.refunded(reason, now));
            locked.refunded();
        });
        return true;
    }
}
