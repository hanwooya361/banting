package com.banting.demo;

import com.banting.groupbuy.GroupBuy;
import com.banting.groupbuy.GroupBuyCompletionService;
import com.banting.groupbuy.GroupBuyRepository;
import com.banting.participation.Participation;
import com.banting.participation.ParticipationRepository;
import com.banting.participation.ParticipationService;
import com.banting.participation.ParticipationService.PaymentOrder;
import com.banting.participation.ParticipationStatus;
import com.banting.payment.PaymentService;
import com.banting.pickup.PickupOption;
import com.banting.pickup.PickupOptionRepository;
import com.banting.pickup.PickupService;
import com.banting.user.User;
import com.banting.user.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 체험 공구의 봇 진행 (T16.5). {@link TrialBotScheduler}가 몇 초마다 {@link #advanceAll()}을 부른다.
 *
 * <p>진행 상태를 따로 저장하지 않고 <b>지금 DB 상태를 보고 다음 한 걸음만</b> 한다. 그래서 서버가 재시작되거나
 * 한 걸음이 실패해도 다음 실행에서 그 자리부터 이어진다 (메모리에 예약 작업을 두면 재시작 때 끊긴다).
 * 봇은 화면을 흉내 내지 않고 사람과 같은 서비스 메서드(참여·결제 반영·투표·구매했어요·받았어요)를 부른다.
 * 그래서 정원·확정·1위 수령 일정·알림·정산이 실제 공구와 똑같이 일어난다.
 *
 * <pre>
 * OPEN      방문자가 아직 결제 전 → 기다림
 *           방문자 결제 후 → 이웃 봇 한 명이 참여 + DEMO 결제 (+ 아직 모집 중이면 투표). 마지막 자리면 기존 로직대로 확정
 * CONFIRMED 봇 개설자 "구매했어요" → PICKUP
 * PICKUP    이웃 봇 한 명 "받았어요". 봇이 다 받으면 방문자의 받았어요를 기다림 → 방문자가 누르면 완료 + 정산
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrialBotService {

    private final GroupBuyRepository groupBuyRepository;
    private final ParticipationRepository participationRepository;
    private final PickupOptionRepository pickupOptionRepository;
    private final UserRepository userRepository;
    private final ParticipationService participationService;
    private final PaymentService paymentService;
    private final PickupService pickupService;
    private final GroupBuyCompletionService completionService;

    /** 진행 중인 체험마다 한 걸음씩. 한 건이 실패해도 나머지는 계속. @return 걸음을 옮긴 체험 수 */
    public int advanceAll() {
        int moved = 0;
        for (Long id : groupBuyRepository.findTrialIdsInProgress(PageRequest.ofSize(50))) {
            try {
                if (step(id)) {
                    moved++;
                }
            } catch (RuntimeException e) {
                log.warn("체험 봇 진행 실패 groupBuyId={}: {}", id, e.toString());
            }
        }
        return moved;
    }

    /** 이 체험의 다음 한 걸음. @return 무언가 했으면 true, 기다리는 중이거나 끝났으면 false */
    public boolean step(Long groupBuyId) {
        GroupBuy groupBuy = groupBuyRepository.findById(groupBuyId).orElseThrow();
        if (!groupBuy.isTrial()) {
            throw new IllegalStateException("체험 공구가 아님: " + groupBuyId);
        }
        Map<Long, Participation> byUser = participationRepository.findByGroupBuyId(groupBuyId).stream()
                .collect(Collectors.toMap(Participation::getUserId, Function.identity()));
        return switch (groupBuy.getStatus()) {
            case OPEN -> joinNextBot(groupBuy, byUser);
            case CONFIRMED -> {
                completionService.markPurchased(groupBuy.getHostId(), groupBuyId);
                yield true;
            }
            case PICKUP -> receiveNextBot(byUser);
            default -> false;
        };
    }

    private boolean joinNextBot(GroupBuy groupBuy, Map<Long, Participation> byUser) {
        Participation visitor = byUser.get(groupBuy.getTrialUserId());
        if (visitor == null || visitor.getStatus() != ParticipationStatus.PAID) {
            return false;   // 방문자 결제가 먼저. 봇이 자리를 다 채우면 방문자가 들어올 자리가 없다
        }
        Optional<Long> next = neighborBotIds().stream()
                .filter(botId -> !byUser.containsKey(botId))
                .findFirst();
        if (next.isEmpty()) {
            return false;
        }
        Long botId = next.get();
        PaymentOrder order = participationService.participateAsTrialBot(botId, groupBuy.getId(), 1);
        PaymentService.ConfirmResponse paid = paymentService.confirmDemo(botId, order.orderId());
        if (paid.groupBuyStatus() == com.banting.groupbuy.GroupBuyStatus.OPEN) {
            vote(groupBuy.getId(), botId);
        }
        return true;
    }

    /** 봇마다 다른 후보에 투표해 득표가 갈리게 한다 (방문자의 표가 1위를 정할 수 있게). */
    private void vote(Long groupBuyId, Long botId) {
        List<PickupOption> options = pickupOptionRepository.findByGroupBuyIdOrderByPickupAtAsc(groupBuyId);
        if (!options.isEmpty()) {
            int index = neighborBotIds().indexOf(botId) + 1;
            pickupService.vote(botId, groupBuyId, options.get(index % options.size()).getId());
        }
    }

    private boolean receiveNextBot(Map<Long, Participation> byUser) {
        List<Long> bots = neighborBotIds();
        return byUser.values().stream()
                .filter(p -> bots.contains(p.getUserId()) && p.getStatus() == ParticipationStatus.PAID)
                .findFirst()
                .map(p -> {
                    completionService.receive(p.getUserId(), p.getId());
                    return true;
                })
                .orElse(false);   // 봇은 다 받음 → 방문자 차례
    }

    private List<Long> neighborBotIds() {
        return DemoSeeder.NEIGHBOR_EMAILS.stream()
                .map(email -> userRepository.findByEmail(email).map(User::getId).orElseThrow())
                .toList();
    }
}
