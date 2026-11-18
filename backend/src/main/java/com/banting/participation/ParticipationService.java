package com.banting.participation;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import com.banting.groupbuy.GroupBuy;
import com.banting.groupbuy.GroupBuyRepository;
import com.banting.groupbuy.PriceType;
import com.banting.payment.Payment;
import com.banting.payment.PaymentRepository;
import com.banting.payment.PaymentStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 참여 = 결제 준비 (T11). 자리를 먼저 잡고 결제 대기(10분) + 토스 주문번호를 만든다. 돈은 아직 나가지 않는다.
 *
 * <p>개설자도 같은 API로 내 몫 결제를 시작한다. 개설 때 이미 자리를 잡아 두었으므로(DRAFT) 주문번호만 새로 받는다.
 * 실제 결제 승인은 {@link com.banting.payment.PaymentService}.
 */
@Service
@RequiredArgsConstructor
public class ParticipationService {

    public static final Duration PAYMENT_WINDOW = Duration.ofMinutes(10);

    private final GroupBuyRepository groupBuyRepository;
    private final ParticipationRepository participationRepository;
    private final PaymentRepository paymentRepository;
    private final TransactionTemplate tx;
    private final Clock clock;

    /** 결제창에 넘길 값. 금액은 서버가 계산한 것만 쓴다. */
    public record PaymentOrder(Long participationId, String orderId, long amount, String orderName,
                               LocalDateTime expiresAt) {
    }

    public PaymentOrder participate(Long userId, Long groupBuyId, int units) {
        LocalDateTime now = LocalDateTime.now(clock);
        GroupBuy groupBuy = groupBuyRepository.findById(groupBuyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_BUY_NOT_FOUND));
        boolean host = groupBuy.getHostId().equals(userId);
        if (groupBuy.isDraft() && !host) {
            throw new BusinessException(ErrorCode.GROUP_BUY_NOT_FOUND);   // 결제 전 공구는 남에게 없는 공구
        }
        if (groupBuy.isDemo()) {
            throw new BusinessException(ErrorCode.DEMO_GROUP_BUY);
        }
        try {
            return tx.execute(status -> host
                    ? hostOrder(groupBuy, userId, now)
                    : participantOrder(groupBuy, userId, units, now));
        } catch (DataIntegrityViolationException e) {
            // 같은 사람이 동시에 두 번 눌러 UNIQUE(group_buy_id, user_id)에 걸림 → 앞의 요청이 이미 자리를 잡음
            throw new BusinessException(ErrorCode.ALREADY_PARTICIPATED);
        }
    }

    private PaymentOrder hostOrder(GroupBuy groupBuy, Long userId, LocalDateTime now) {
        Participation participation = participationRepository
                .findByGroupBuyIdAndUserIdForUpdate(groupBuy.getId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_BUY_NOT_FOUND));
        if (!participation.isPending()) {
            throw new BusinessException(ErrorCode.ALREADY_PARTICIPATED);   // 이미 결제한 개설자
        }
        groupBuy.checkHostPayable(now);
        participation.renew(now.plus(PAYMENT_WINDOW));
        return newOrder(groupBuy, participation);
    }

    private PaymentOrder participantOrder(GroupBuy groupBuy, Long userId, int units, LocalDateTime now) {
        groupBuy.checkJoinable(now);
        validateUnits(groupBuy, units);
        long amount = groupBuy.pricing().participantAmount(units);
        LocalDateTime expiresAt = now.plus(PAYMENT_WINDOW);

        Participation participation = participationRepository
                .findByGroupBuyIdAndUserIdForUpdate(groupBuy.getId(), userId).orElse(null);
        if (participation == null) {
            reserve(groupBuy, units, now);
            participation = participationRepository.save(
                    Participation.pending(groupBuy.getId(), userId, units, amount, expiresAt));
        } else {
            switch (participation.getStatus()) {
                case PENDING_PAYMENT -> {
                    // 결제창을 닫았거나 거절돼 다시 시도: 자리는 이미 잡혀 있으니 같은 수량이면 대기 시간만 새로
                    if (participation.getUnits() != units) {
                        throw new BusinessException(ErrorCode.PARTICIPATION_PENDING);
                    }
                    participation.renew(expiresAt);
                }
                case CANCELED -> {
                    reserve(groupBuy, units, now);
                    participation.rejoin(units, amount, expiresAt);
                }
                case PAID, RECEIVED -> throw new BusinessException(ErrorCode.ALREADY_PARTICIPATED);
                case REFUNDED -> throw new BusinessException(ErrorCode.CANNOT_REJOIN);
            }
        }
        return newOrder(groupBuy, participation);
    }

    /** 균등은 한 사람 한 자리, 수량별은 최소 수량 이상 정원 이하. 남은 자리 확인은 조건부 UPDATE가 한다. */
    private static void validateUnits(GroupBuy groupBuy, int units) {
        boolean valid = groupBuy.getPriceType() == PriceType.EQUAL
                ? units == 1
                : units >= groupBuy.getMinUnitsPerPerson() && units <= groupBuy.getCapacityUnits();
        if (!valid) {
            throw new BusinessException(ErrorCode.INVALID_PARTICIPATION_UNITS);
        }
    }

    private void reserve(GroupBuy groupBuy, int units, LocalDateTime now) {
        if (groupBuyRepository.reserveUnits(groupBuy.getId(), units, now) == 0) {
            throw new BusinessException(ErrorCode.GROUP_BUY_FULL);
        }
    }

    /**
     * 새 주문번호 발급. 같은 참여의 이전 결제 대기 주문은 실패 처리해서, 탭 두 개로 두 번 결제되는 일을 막는다
     * (이전 주문으로 승인을 시도하면 PAYMENT_NOT_PENDING).
     */
    private PaymentOrder newOrder(GroupBuy groupBuy, Participation participation) {
        paymentRepository.findByParticipationIdAndStatus(participation.getId(), PaymentStatus.READY)
                .forEach(old -> old.fail(null, "SUPERSEDED: 새 주문으로 대체"));
        Payment payment = paymentRepository.save(Payment.ready(participation.getId(), participation.getAmount()));
        return new PaymentOrder(participation.getId(), payment.getOrderId(), payment.getAmount(),
                groupBuy.getTitle(), participation.getExpiresAt());
    }
}
