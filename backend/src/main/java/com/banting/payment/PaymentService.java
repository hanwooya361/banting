package com.banting.payment;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import com.banting.groupbuy.GroupBuy;
import com.banting.groupbuy.GroupBuyRepository;
import com.banting.groupbuy.GroupBuyStatus;
import com.banting.participation.Participation;
import com.banting.participation.ParticipationRepository;
import com.banting.notification.NotificationService;
import com.banting.participation.ParticipationStatus;
import com.banting.payment.TossPaymentsClient.Approval;
import com.banting.pickup.PickupOption;
import com.banting.pickup.PickupService;
import com.banting.payment.TossPaymentsClient.TossRejectedException;
import com.banting.payment.TossPaymentsClient.TossUnavailableException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 승인 (tech-design 4장 "결제 승인 (T11)").
 *
 * <ol>
 *   <li>검증 (트랜잭션 밖): 내 주문인지, 금액이 서버 금액과 같은지, 결제 대기 시간 안인지 → 아니면 토스를 부르지 않는다</li>
 *   <li>토스 승인 호출 (트랜잭션 밖): 느린 외부 호출 동안 DB 잠금·커넥션을 잡고 있지 않게</li>
 *   <li>짧은 트랜잭션: 결제 DONE, 참여 PAID, paid_units 증가, 공구 OPEN/CONFIRMED</li>
 *   <li>3이 실패하면 토스 결제를 취소(보상)하고 기록. 취소마저 실패하면 수동 확인 대상으로 남긴다</li>
 * </ol>
 * 같은 주문을 다시 승인 요청하면 이미 DONE이면 그대로 성공 (새로고침·중복 클릭에 안전, 멱등).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String ALREADY_PROCESSED = "ALREADY_PROCESSED_PAYMENT";

    private final PaymentRepository paymentRepository;
    private final ParticipationRepository participationRepository;
    private final GroupBuyRepository groupBuyRepository;
    private final TossPaymentsClient toss;
    private final PickupService pickupService;
    private final NotificationService notificationService;
    private final TransactionTemplate tx;
    private final Clock clock;

    public record ConfirmResponse(Long groupBuyId, GroupBuyStatus groupBuyStatus,
                                  ParticipationStatus participationStatus, int units, long amount) {
    }

    public ConfirmResponse confirm(Long userId, String paymentKey, String orderId, long amount) {
        LocalDateTime now = LocalDateTime.now(clock);

        // 1) 검증 — 여기서 걸리면 돈이 나가지 않는다
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        Participation participation = participationRepository.findById(payment.getParticipationId()).orElseThrow();
        if (!participation.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);   // 남의 주문은 없는 주문처럼
        }
        if (payment.isDone()) {
            return alreadyDone(payment, paymentKey);
        }
        if (!payment.isReady()) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_PENDING);
        }
        if (payment.getAmount() != amount) {
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        GroupBuy groupBuy = groupBuyRepository.findById(participation.getGroupBuyId()).orElseThrow();
        if (!participation.isPending() || participation.isExpired(now) || !groupBuy.getDeadlineAt().isAfter(now)) {
            throw new BusinessException(ErrorCode.PAYMENT_EXPIRED);
        }

        // 2) 토스 승인 (트랜잭션 밖)
        Approval approval;
        try {
            approval = toss.confirm(paymentKey, orderId, amount);
        } catch (TossRejectedException e) {
            if (ALREADY_PROCESSED.equals(e.getCode())) {
                // 같은 주문을 동시에 두 번 승인 요청: 먼저 온 요청이 처리 중이거나 이미 끝남
                Payment latest = paymentRepository.findByOrderId(orderId).orElseThrow();
                if (latest.isDone()) {
                    return alreadyDone(latest, paymentKey);
                }
                throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
            }
            log.info("토스 승인 거절 orderId={} code={}", orderId, e.getCode());
            recordFailure(orderId, paymentKey, e.getCode());
            throw new BusinessException(ErrorCode.PAYMENT_REJECTED);
        } catch (TossUnavailableException e) {
            // 결과를 모름: 승인됐을 수도 있으니 취소를 시도해 "돈은 나갔는데 참여는 안 된" 상태를 없앤다
            log.warn("토스 승인 응답 없음 orderId={}: {}", orderId, e.getMessage());
            compensate(orderId, paymentKey, false, "승인 응답 없음");
            throw new BusinessException(ErrorCode.PAYMENT_UNAVAILABLE);
        }
        if (!"DONE".equals(approval.status()) || approval.totalAmount() != amount) {
            log.error("토스 승인 응답 이상 orderId={} status={} amount={}", orderId, approval.status(), approval.totalAmount());
            throw compensate(orderId, paymentKey, true, "승인 응답 이상");
        }

        // 3) 우리 DB 반영 (짧은 트랜잭션)
        LocalDateTime approvedAt = approval.approvedAt() == null
                ? now
                : approval.approvedAt().atZoneSameInstant(clock.getZone()).toLocalDateTime();
        try {
            return tx.execute(status -> apply(groupBuy.getId(), participation.getId(), orderId, paymentKey, approvedAt));
        } catch (RuntimeException e) {
            // 4) 보상: 토스에서는 결제됐지만 우리 쪽 반영이 실패 → 결제 취소
            log.warn("결제 반영 실패, 토스 결제 취소 orderId={}: {}", orderId, e.toString());
            throw compensate(orderId, paymentKey, true, "결제 반영 실패: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 잠금 순서는 공구 → 참여 → 결제로 고정 (참여 요청도 같은 순서라 교착을 줄인다).
     * 공구 행을 잠가 같은 공구의 승인 반영끼리는 한 줄로 세운다 (paid_units·확정 판단이 엇갈리지 않게).
     * 트랜잭션 안에서는 잠금 조회로 처음 읽는다. 먼저 일반 조회로 읽어 두면 잠금 조회가 캐시된 옛 값을 돌려준다.
     */
    private ConfirmResponse apply(Long groupBuyId, Long participationId, String orderId, String paymentKey,
                                  LocalDateTime approvedAt) {
        GroupBuy groupBuy = groupBuyRepository.findByIdForUpdate(groupBuyId).orElseThrow();
        Participation participation = participationRepository.findByIdForUpdate(participationId).orElseThrow();
        Payment payment = paymentRepository.findByOrderIdForUpdate(orderId).orElseThrow();

        if (payment.isDone() && paymentKey.equals(payment.getPaymentKey())) {
            return response(groupBuy, participation, payment);   // 동시에 온 같은 요청이 먼저 반영함
        }
        payment.done(paymentKey, approvedAt);
        participation.pay();
        groupBuy.markPaid(participation.getUnits(), participation.isHost(), LocalDateTime.now(clock));
        if (groupBuy.getStatus() == GroupBuyStatus.CONFIRMED) {
            onConfirmed(groupBuy);
        }
        return response(groupBuy, participation, payment);
    }

    /**
     * 정원이 다 차서 확정된 순간 (T15): 투표 1위 후보를 수령 일정으로 저장하고 결제한 사람 전원에게 알린다.
     * 같은 트랜잭션이라 확정·수령 일정·알림이 함께 커밋되거나 함께 롤백된다 (롤백되면 토스 결제는 보상 취소).
     */
    private void onConfirmed(GroupBuy groupBuy) {
        Optional<PickupOption> pickup = pickupService.selectWinner(groupBuy.getId());
        notificationService.confirmed(pickupService.paidUserIds(groupBuy.getId()), groupBuy.getId(),
                groupBuy.getTitle(),
                pickup.map(PickupOption::getPickupAt).orElse(null),
                pickup.map(PickupOption::getPlaceName).orElse(null));
    }

    private ConfirmResponse alreadyDone(Payment payment, String paymentKey) {
        if (!payment.getPaymentKey().equals(paymentKey)) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_PENDING);
        }
        Participation participation = participationRepository.findById(payment.getParticipationId()).orElseThrow();
        GroupBuy groupBuy = groupBuyRepository.findById(participation.getGroupBuyId()).orElseThrow();
        return response(groupBuy, participation, payment);
    }

    private static ConfirmResponse response(GroupBuy groupBuy, Participation participation, Payment payment) {
        return new ConfirmResponse(groupBuy.getId(), groupBuy.getStatus(), participation.getStatus(),
                participation.getUnits(), payment.getAmount());
    }

    /** 승인 거절: 결제 기록만 FAILED. 참여는 결제 대기로 남아 대기 시간 안에 다시 결제할 수 있다. */
    private void recordFailure(String orderId, String paymentKey, String reason) {
        tx.executeWithoutResult(status -> paymentRepository.findByOrderIdForUpdate(orderId)
                .filter(Payment::isReady)
                .ifPresent(payment -> payment.fail(paymentKey, reason)));
    }

    /**
     * 토스 결제 취소 후 결과를 기록한다.
     *
     * @param approved 토스 승인이 확실히 됐는지. 결과를 모르는 경우(false)엔 취소가 거절돼도(승인된 적 없음) 정상
     * @return 호출 쪽이 던질 예외 (취소 성공: PAYMENT_CANCELED, 취소 실패: PAYMENT_NEEDS_REVIEW)
     */
    private BusinessException compensate(String orderId, String paymentKey, boolean approved, String reason) {
        boolean safe;
        try {
            toss.cancel(paymentKey, orderId, reason);
            safe = true;
        } catch (TossRejectedException e) {
            // 승인이 확실하지 않았다면 "취소할 결제 없음"이 정상 응답
            safe = !approved;
            if (approved) {
                log.error("보상 취소 거절, 수동 확인 필요 orderId={} paymentKey={} code={}", orderId, paymentKey, e.getCode());
            }
        } catch (RuntimeException e) {
            safe = false;
            log.error("보상 취소 실패, 수동 확인 필요 orderId={} paymentKey={}", orderId, paymentKey, e);
        }

        boolean canceled = safe;
        LocalDateTime now = LocalDateTime.now(clock);
        tx.executeWithoutResult(status -> paymentRepository.findByOrderIdForUpdate(orderId)
                .filter(Payment::isReady)
                .ifPresent(payment -> {
                    if (!canceled) {
                        payment.fail(paymentKey, "자동 취소 실패, 수동 확인 필요: " + reason);
                    } else if (approved) {
                        payment.compensated(paymentKey, reason, now);
                    } else {
                        payment.fail(paymentKey, reason);
                    }
                }));
        return new BusinessException(canceled ? ErrorCode.PAYMENT_CANCELED : ErrorCode.PAYMENT_NEEDS_REVIEW);
    }
}
