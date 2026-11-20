package com.banting.groupbuy;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import com.banting.notification.NotificationService;
import com.banting.participation.Participation;
import com.banting.participation.ParticipationRepository;
import com.banting.participation.ParticipationStatus;
import com.banting.pickup.PickupOption;
import com.banting.pickup.PickupOptionRepository;
import com.banting.settlement.Settlement;
import com.banting.settlement.SettlementService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구매 → 수령 확인 → 완료·정산 (T16, tech-design 4장 "수령 확인과 정산").
 *
 * <pre>
 * CONFIRMED ─ 개설자 "구매했어요" ─▶ PICKUP ─┬ 참여자 전원 "받았어요" ───────▶ COMPLETED + 정산 기록
 *                                            └ 수령 일정 + 3일 경과 (스케줄러) ▶ COMPLETED + 정산 기록
 * </pre>
 *
 * <p>모두 공구 행을 먼저 잠근다 (T13 락 순서). 마지막 "받았어요"와 스케줄러가 동시에 와도 한 줄로 서서
 * 뒤에 온 쪽은 이미 COMPLETED를 보고 아무것도 하지 않는다. 정산 UNIQUE(group_buy_id)가 마지막 방어선.
 */
@Service
@RequiredArgsConstructor
public class GroupBuyCompletionService {

    /** 수령 일정 뒤 이만큼 지나면 확인하지 않은 사람이 있어도 완료 (받고 나서 누르는 걸 잊는 경우). */
    public static final Duration AUTO_COMPLETE_AFTER_PICKUP = Duration.ofDays(3);

    private final GroupBuyRepository groupBuyRepository;
    private final ParticipationRepository participationRepository;
    private final PickupOptionRepository pickupOptionRepository;
    private final SettlementService settlementService;
    private final NotificationService notificationService;
    private final TransactionTemplate tx;
    private final Clock clock;

    /** 개설자 "구매했어요": CONFIRMED → PICKUP, 참여자에게 알림. */
    public void markPurchased(Long userId, Long groupBuyId) {
        tx.executeWithoutResult(status -> {
            GroupBuy groupBuy = groupBuyRepository.findByIdForUpdate(groupBuyId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_BUY_NOT_FOUND));
            groupBuy.checkHost(userId);
            groupBuy.markPurchased();
            Optional<PickupOption> pickup = pickupOptionRepository.findByGroupBuyIdAndSelectedTrue(groupBuyId);
            notificationService.purchased(activeParticipantIds(groupBuyId), groupBuyId, groupBuy.getTitle(),
                    pickup.map(PickupOption::getPickupAt).orElse(null),
                    pickup.map(PickupOption::getPlaceName).orElse(null));
        });
    }

    /**
     * 참여자 "받았어요": PAID → RECEIVED. 개설자가 산 뒤(PICKUP)부터, 자동 완료된 뒤 늦게 눌러도 기록은 남긴다.
     * 공구가 PICKUP이고 이걸로 참여자 전원이 받았으면 완료·정산까지.
     *
     * @return 이 요청으로 공구가 완료됐는지
     */
    public boolean receive(Long userId, Long participationId) {
        // 공구 ID는 트랜잭션 밖에서 읽는다 (안에서 일반 조회 후 잠금 조회하면 캐시된 옛 값, T13)
        Participation found = participationRepository.findById(participationId)
                .filter(p -> p.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PARTICIPATION_NOT_FOUND));
        if (found.isHost()) {
            throw new BusinessException(ErrorCode.RECEIVE_NOT_ALLOWED);   // 개설자 몫은 수령 대상이 아님
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return Boolean.TRUE.equals(tx.execute(status -> {
            GroupBuy groupBuy = groupBuyRepository.findByIdForUpdate(found.getGroupBuyId()).orElseThrow();
            Participation participation = participationRepository.findByIdForUpdate(participationId).orElseThrow();
            if (participation.getStatus() == ParticipationStatus.RECEIVED) {
                return false;   // 두 번 누름
            }
            if (participation.getStatus() != ParticipationStatus.PAID) {
                throw new BusinessException(ErrorCode.RECEIVE_NOT_ALLOWED);
            }
            if (!groupBuy.isPickup() && groupBuy.getStatus() != GroupBuyStatus.COMPLETED) {
                throw new BusinessException(ErrorCode.RECEIVE_NOT_READY);
            }
            participation.receive(now);
            if (groupBuy.isPickup() && everyoneReceived(groupBuy.getId())) {
                complete(groupBuy, now);
                return true;
            }
            return false;
        }));
    }

    /** ⑥ 스케줄러: 수령 일정 + 3일이 지난 PICKUP 공구 완료. @return 완료 여부 (그 사이 바뀌었으면 false) */
    boolean completeOverdue(Long groupBuyId, LocalDateTime cutoff) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            GroupBuy groupBuy = groupBuyRepository.findByIdForUpdate(groupBuyId).orElseThrow();
            if (!groupBuy.isPickup()) {
                return false;   // 그 사이 마지막 참여자가 받았어요를 눌러 완료됨
            }
            boolean overdue = pickupOptionRepository.findByGroupBuyIdAndSelectedTrue(groupBuyId)
                    .map(o -> o.getPickupAt().isBefore(cutoff)).orElse(false);
            if (!overdue) {
                return false;
            }
            complete(groupBuy, LocalDateTime.now(clock));
            return true;
        }));
    }

    /** 완료 + 정산 기록 + 개설자 알림. 공구 행을 잠근 트랜잭션 안에서만. */
    private void complete(GroupBuy groupBuy, LocalDateTime now) {
        groupBuy.complete(now);
        long gross = participationRepository.findByGroupBuyId(groupBuy.getId()).stream()
                .filter(p -> p.getStatus() == ParticipationStatus.PAID || p.getStatus() == ParticipationStatus.RECEIVED)
                .mapToLong(Participation::getAmount)
                .sum();
        Settlement settlement = settlementService.record(groupBuy.getId(), groupBuy.getHostId(), gross);
        notificationService.completed(groupBuy.getHostId(), groupBuy.getId(), groupBuy.getTitle(),
                settlement.getNetAmount(), settlement.getFeeAmount());
    }

    private boolean everyoneReceived(Long groupBuyId) {
        return participationRepository.findByGroupBuyId(groupBuyId).stream()
                .noneMatch(p -> !p.isHost() && p.getStatus() == ParticipationStatus.PAID);
    }

    /** 결제를 유지 중인 참여자(개설자 제외). */
    private List<Long> activeParticipantIds(Long groupBuyId) {
        return participationRepository.findByGroupBuyId(groupBuyId).stream()
                .filter(p -> !p.isHost() && p.getStatus() == ParticipationStatus.PAID)
                .map(Participation::getUserId)
                .toList();
    }
}
