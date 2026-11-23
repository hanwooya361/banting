package com.banting.demo;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import com.banting.groupbuy.GroupBuy;
import com.banting.groupbuy.GroupBuyImage;
import com.banting.groupbuy.GroupBuyImageRepository;
import com.banting.groupbuy.GroupBuyRepository;
import com.banting.participation.Participation;
import com.banting.participation.ParticipationRepository;
import com.banting.participation.ParticipationService;
import com.banting.participation.ParticipationService.PaymentOrder;
import com.banting.payment.PaymentService;
import com.banting.pickup.PickupOption;
import com.banting.pickup.PickupOptionRepository;
import com.banting.pickup.PickupService;
import com.banting.user.User;
import com.banting.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 체험 모드 시작 (T16.5, tech-design 4장 "시드 데이터와 체험 모드").
 *
 * <p>예시(시드) 공구를 복사해 방문자 전용 체험 공구를 만든다. 개설자는 데모 봇이고, 봇은 사람과 같은 서비스 메서드로
 * 내 몫을 결제(DEMO 결제)해 공구를 모집 중으로 연다. 방문자는 일반 참여 API로 들어와 본인 몫을 토스 테스트 결제로 낸다.
 * 그 뒤 진행은 {@link TrialBotService}가 맡는다.
 *
 * <p>남용 방지: 회원당 끝나지 않은 체험 1개(있으면 새로 만들지 않고 그 체험으로 보냄), 24시간에 {@link #DAILY_LIMIT}번.
 * 같은 회원의 동시 요청은 회원 행 잠금으로 한 줄로 세워 두 개가 만들어지지 않게 한다.
 *
 * <p>체험 데이터는 지우지 않는다 (2026-11-23 결정): demo_type = TRIAL로 목록·내 공구·통계에서 빠지고,
 * 결제·정산 기록은 감사 이력으로 남는다. 끝나지 않은 체험은 일반 공구처럼 마감 미달로 실패하고 자동 환불된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrialService {

    /** 개설자(봇) + 방문자 + 이웃 봇 2명. */
    static final int PEOPLE = 2 + DemoSeeder.NEIGHBOR_EMAILS.size();
    /** 모집 마감. 결제 대기 시간(10분)과 같게 해서, 방문자가 결제를 미루면 마감 미달로 정리된다. */
    static final Duration DURATION = Duration.ofMinutes(10);
    static final int DAILY_LIMIT = 5;
    private static final LocalTime PICKUP_TIME = LocalTime.of(19, 30);

    private final GroupBuyRepository groupBuyRepository;
    private final GroupBuyImageRepository imageRepository;
    private final ParticipationRepository participationRepository;
    private final PickupOptionRepository pickupOptionRepository;
    private final UserRepository userRepository;
    private final ParticipationService participationService;
    private final PaymentService paymentService;
    private final PickupService pickupService;
    private final TransactionTemplate tx;
    private final Clock clock;

    /** @param resumed 새로 만들지 않고 진행 중이던 체험으로 보냈는지 */
    public record TrialStart(Long groupBuyId, boolean resumed) {
    }

    public TrialStart start(Long userId, Long seedId) {
        Long botHostId = userRepository.findByEmail(DemoSeeder.HOST_EMAIL).map(User::getId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRIAL_UNAVAILABLE));   // 시드가 꺼진 환경
        LocalDateTime now = LocalDateTime.now(clock);

        TrialStart start = tx.execute(status -> {
            userRepository.lockById(userId);
            List<Long> active = groupBuyRepository.findActiveTrialIds(userId);
            if (!active.isEmpty()) {
                return new TrialStart(active.get(0), true);
            }
            if (groupBuyRepository.countTrialsSince(userId, now.minusDays(1)) >= DAILY_LIMIT) {
                throw new BusinessException(ErrorCode.TRIAL_DAILY_LIMIT);
            }
            GroupBuy seed = groupBuyRepository.findById(seedId).filter(GroupBuy::isSeed)
                    .orElseThrow(() -> new BusinessException(ErrorCode.TRIAL_SOURCE_NOT_FOUND));
            return new TrialStart(createDraft(seed, botHostId, userId, now), false);
        });

        GroupBuy trial = groupBuyRepository.findById(start.groupBuyId()).orElseThrow();
        if (trial.isDraft()) {
            openByBotHost(trial);   // 새 체험이거나, 지난번에 봇 결제 전에 끊긴 체험
        }
        return start;
    }

    /** 일반 개설과 같은 모양: 공구(DRAFT) + 개설자 참여(결제 대기, 자리 예약) + 수령 후보 + 사진. */
    private Long createDraft(GroupBuy seed, Long botHostId, Long userId, LocalDateTime now) {
        GroupBuy trial = groupBuyRepository.save(GroupBuy.trial(seed, botHostId, userId, PEOPLE, now.plus(DURATION)));
        Long id = trial.getId();
        participationRepository.save(Participation.hostPending(id, botHostId, 1, trial.pricing().hostAmount(1),
                now.plus(ParticipationService.PAYMENT_WINDOW)));
        // 수령 후보는 시드의 장소를 내일·모레 저녁으로. 장소가 하나뿐인 시드도 투표가 보이게 후보는 2개
        List<String> places = pickupOptionRepository.findByGroupBuyIdOrderByPickupAtAsc(seed.getId()).stream()
                .map(PickupOption::getPlaceName).toList();
        for (int day = 1; day <= 2; day++) {
            String place = places.get(Math.min(day - 1, places.size() - 1));
            pickupOptionRepository.save(PickupOption.of(id, now.toLocalDate().plusDays(day).atTime(PICKUP_TIME), place));
        }
        imageRepository.findByGroupBuyIdOrderBySortOrderAsc(seed.getId())
                .forEach(image -> imageRepository.save(GroupBuyImage.of(id, image.getKey(), image.getSortOrder())));
        log.info("체험 시작 groupBuyId={} seedId={} userId={}", id, seed.getId(), userId);
        return id;
    }

    /** 봇 개설자가 사람과 같은 경로로 내 몫을 결제(DEMO) → 모집 중. 첫 수령 후보에 투표해 둔다. */
    private void openByBotHost(GroupBuy trial) {
        Long hostId = trial.getHostId();
        PaymentOrder order = participationService.participateAsTrialBot(hostId, trial.getId(), 1);
        paymentService.confirmDemo(hostId, order.orderId());
        pickupOptionRepository.findByGroupBuyIdOrderByPickupAtAsc(trial.getId()).stream().findFirst()
                .ifPresent(option -> pickupService.vote(hostId, trial.getId(), option.getId()));
    }
}
