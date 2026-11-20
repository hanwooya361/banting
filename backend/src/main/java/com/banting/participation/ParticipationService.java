package com.banting.participation;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import com.banting.groupbuy.GroupBuy;
import com.banting.groupbuy.GroupBuyRepository;
import com.banting.groupbuy.PriceType;
import com.banting.payment.Payment;
import com.banting.payment.PaymentRepository;
import com.banting.payment.PaymentStatus;
import com.banting.payment.RefundService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
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
public class ParticipationService {

    public static final Duration PAYMENT_WINDOW = Duration.ofMinutes(10);

    private final GroupBuyRepository groupBuyRepository;
    private final ParticipationRepository participationRepository;
    private final PaymentRepository paymentRepository;
    private final RefundService refundService;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final SeatLock seatLock;
    private final EntityManager entityManager;

    public ParticipationService(GroupBuyRepository groupBuyRepository,
                                ParticipationRepository participationRepository,
                                PaymentRepository paymentRepository,
                                RefundService refundService,
                                TransactionTemplate tx,
                                Clock clock,
                                EntityManager entityManager,
                                @Value("${banting.participation.seat-lock:CONDITIONAL}") SeatLock seatLock) {
        this.groupBuyRepository = groupBuyRepository;
        this.participationRepository = participationRepository;
        this.paymentRepository = paymentRepository;
        this.refundService = refundService;
        this.tx = tx;
        this.clock = clock;
        this.entityManager = entityManager;
        this.seatLock = seatLock;
    }

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

        Participation participation = seatLock == SeatLock.PESSIMISTIC
                ? reservePessimistic(groupBuy.getId(), userId, units, now)
                : reserveConditional(groupBuy.getId(), userId, units, now);
        if (participation == null) {
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
                case CANCELED -> participation.rejoin(units, amount, expiresAt);   // 자리는 위에서 이미 잡음
                case PAID, RECEIVED -> throw new BusinessException(ErrorCode.ALREADY_PARTICIPATED);
                case REFUND_PENDING, REFUNDED -> throw new BusinessException(ErrorCode.CANNOT_REJOIN);
            }
        }
        return newOrder(groupBuy, participation);
    }

    /*
     * 자리 잡기 + 내 참여 행 잠그기. 반환값은 잠근 내 참여 행(없으면 null = 새로 만들 차례).
     *
     * 락 순서는 취소·결제 승인과 같은 "공구 → 참여"로 맞춘다 (T13).
     * 예전에는 없는 참여 행을 먼저 FOR UPDATE로 찾았는데, MySQL(REPEATABLE READ)은 없는 행 자리에 갭 락을 건다.
     * 갭 락끼리는 서로 막지 않아 100명이 동시에 갭 락을 쥔 채 공구 행 잠금을 기다리고,
     * 공구 행을 얻은 쪽의 INSERT는 남들의 갭 락에 막혀 → 데드락 (동시 100명 중 26명이 500).
     */

    /** 새 참여·재참여면 자리가 필요하다. 결제 대기 중이면 이미 자리가 있고, 그 밖의 상태는 아래 switch가 거부한다. */
    private static boolean needsSeat(Participation participation) {
        return participation == null || participation.getStatus() == ParticipationStatus.CANCELED;
    }

    /**
     * 조건부 UPDATE (기본). 참여 행은 잠그지 않고 먼저 읽기만 해서 갭 락을 만들지 않는다.
     * 자리가 필요하면 조건부 UPDATE(= 공구 행 잠금) → 그다음 내 참여 행을 잠근다.
     * 읽은 뒤 잠그기 전에 같은 사람의 다른 요청이 상태를 바꿨으면 다시 시도하게 한다 (자리 증가도 롤백).
     * 같은 사람이 동시에 새로 참여하면 INSERT가 UNIQUE에 걸려 ALREADY_PARTICIPATED.
     */
    private Participation reserveConditional(Long groupBuyId, Long userId, int units, LocalDateTime now) {
        Participation seen = participationRepository.findByGroupBuyIdAndUserId(groupBuyId, userId).orElse(null);
        if (needsSeat(seen) && groupBuyRepository.reserveUnits(groupBuyId, units, now) == 0) {
            throw new BusinessException(ErrorCode.GROUP_BUY_FULL);
        }
        if (seen == null) {
            return null;
        }
        // 이미 불러온 엔티티는 다시 조회해도 Hibernate가 상태를 덮어쓰지 않으므로, 잠그면서 DB 최신 값으로 새로 읽는다
        boolean seatTaken = needsSeat(seen);
        entityManager.refresh(seen, LockModeType.PESSIMISTIC_WRITE);
        if (needsSeat(seen) != seatTaken) {
            throw new BusinessException(ErrorCode.PARTICIPATION_CHANGED);
        }
        return seen;
    }

    /**
     * 비관적 락 (T13 비교용). 공구 행을 FOR UPDATE로 먼저 잠그므로 같은 공구의 참여 요청은 여기서 한 줄로 선다.
     * 그 뒤의 참여 행 갭 락은 이미 직렬화된 상태라 데드락을 만들지 않는다.
     */
    private Participation reservePessimistic(Long groupBuyId, Long userId, int units, LocalDateTime now) {
        GroupBuy locked = groupBuyRepository.findByIdForUpdate(groupBuyId).orElseThrow();
        Participation participation = participationRepository
                .findByGroupBuyIdAndUserIdForUpdate(groupBuyId, userId).orElse(null);
        if (needsSeat(participation)) {
            locked.reserve(units, now);
        }
        return participation;
    }

    /** 취소 결과. refundAmount는 돌려받는(받을) 금액, 결제 전 취소면 0. */
    public record CancelResult(ParticipationStatus status, long refundAmount) {
    }

    /**
     * 참여 취소 (T12). 결제 전이면 바로 CANCELED, 결제 후면 환불.
     * ① 짧은 트랜잭션(공구 → 참여 잠금): 확인, 자리 반환, 참여 REFUND_PENDING
     * ② 토스 취소(트랜잭션 밖) → ③ REFUNDED. ②가 실패하면 환불 대기로 남고, 같은 요청을 다시 하면 재시도한다.
     */
    public CancelResult cancel(Long userId, Long participationId) {
        Participation found = participationRepository.findById(participationId)
                .filter(p -> p.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PARTICIPATION_NOT_FOUND));
        if (found.isHost()) {
            throw new BusinessException(ErrorCode.HOST_USE_GROUP_CANCEL);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        ParticipationStatus afterTx = found.isRefundPending()
                ? ParticipationStatus.REFUND_PENDING   // 이전 환불이 실패해 다시 누름 → 바로 재시도
                : tx.execute(status -> startCancel(found.getGroupBuyId(), participationId, now));

        if (afterTx == ParticipationStatus.CANCELED) {
            return new CancelResult(ParticipationStatus.CANCELED, 0);
        }
        boolean refunded = refundService.refund(participationId, "참여자 취소");
        return new CancelResult(refunded ? ParticipationStatus.REFUNDED : ParticipationStatus.REFUND_PENDING,
                found.getAmount());
    }

    private ParticipationStatus startCancel(Long groupBuyId, Long participationId, LocalDateTime now) {
        GroupBuy groupBuy = groupBuyRepository.findByIdForUpdate(groupBuyId).orElseThrow();
        Participation participation = participationRepository.findByIdForUpdate(participationId).orElseThrow();
        switch (participation.getStatus()) {
            case PENDING_PAYMENT -> {
                if (groupBuy.getStatus() != com.banting.groupbuy.GroupBuyStatus.OPEN) {
                    throw new BusinessException(ErrorCode.PARTICIPATION_NOT_CANCELABLE);
                }
                participation.cancelPending();
                groupBuy.release(participation.getUnits(), false);
                failReadyPayments(participation.getId(), "참여 취소");
            }
            case PAID -> {
                groupBuy.checkCancelable(now);
                participation.requestRefund();
                groupBuy.release(participation.getUnits(), true);
            }
            case REFUND_PENDING -> {
                // 동시에 들어온 같은 취소가 먼저 처리함 → 환불만 이어서
            }
            default -> throw new BusinessException(ErrorCode.PARTICIPATION_NOT_CANCELABLE);
        }
        return participation.getStatus();
    }

    /** 결제창에서 쓰던 주문번호로 나중에 승인되지 않게 막는다. */
    private void failReadyPayments(Long participationId, String reason) {
        paymentRepository.findByParticipationIdAndStatus(participationId, PaymentStatus.READY)
                .forEach(payment -> payment.fail(null, reason));
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
