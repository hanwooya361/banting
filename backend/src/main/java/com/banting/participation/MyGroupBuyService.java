package com.banting.participation;

import com.banting.groupbuy.GroupBuy;
import com.banting.groupbuy.GroupBuyImage;
import com.banting.groupbuy.GroupBuyImageRepository;
import com.banting.groupbuy.GroupBuyStatus;
import com.banting.pickup.PickupOption;
import com.banting.pickup.PickupOptionRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 공구 화면 (S07, screen-design). 참여 중 / 개설한 / 지난 공구를 한 번에 나눠서 준다.
 *
 * <p>쿼리는 3번: 내 참여+공구 조인 1번, 대표 사진 IN 1번, 수령 일정 IN 1번 (공구마다 따로 조회하는 N+1 없음).
 * 사람 한 명의 참여는 많지 않아 최근 100개로 자르고 분류·정렬은 Java에서 한다.
 */
@Service
public class MyGroupBuyService {

    private static final int LIMIT = 100;
    private static final Set<GroupBuyStatus> IN_PROGRESS =
            EnumSet.of(GroupBuyStatus.DRAFT, GroupBuyStatus.OPEN, GroupBuyStatus.CONFIRMED, GroupBuyStatus.PICKUP);
    private static final Set<ParticipationStatus> KEEPING_SEAT =
            EnumSet.of(ParticipationStatus.PENDING_PAYMENT, ParticipationStatus.PAID, ParticipationStatus.RECEIVED);

    private final ParticipationRepository participationRepository;
    private final GroupBuyImageRepository imageRepository;
    private final PickupOptionRepository pickupOptionRepository;
    private final Clock clock;
    private final String imageBaseUrl;

    public MyGroupBuyService(ParticipationRepository participationRepository, GroupBuyImageRepository imageRepository,
                             PickupOptionRepository pickupOptionRepository, Clock clock,
                             @Value("${banting.images.public-base-url:}") String imageBaseUrl) {
        this.participationRepository = participationRepository;
        this.imageRepository = imageRepository;
        this.pickupOptionRepository = pickupOptionRepository;
        this.clock = clock;
        this.imageBaseUrl = imageBaseUrl;
    }

    /** 지금 내가 할 일. 카드에서 강조하고 맨 위로 올린다. */
    public enum Todo {
        PAY,        // 결제 대기: 결제 마무리하기
        PURCHASE,   // 개설자, 확정됨: 상품 사고 "구매했어요"
        RECEIVE     // 참여자, 나눔 준비: "받았어요" (카드에서 바로)
    }

    public record Item(Long groupBuyId, String title, GroupBuyStatus status, GroupBuy.FailReason failReason,
                       String thumbnailUrl, boolean host, Long participationId, ParticipationStatus participationStatus,
                       int units, long amount, LocalDateTime deadlineAt, LocalDateTime pickupAt, String pickupPlace,
                       Todo todo) {
    }

    public record Response(List<Item> active, List<Item> hosting, List<Item> past) {
    }

    @Transactional(readOnly = true)
    public Response list(Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Object[]> rows = participationRepository.findMineWithGroupBuy(userId, PageRequest.ofSize(LIMIT));
        List<Long> groupBuyIds = rows.stream().map(r -> ((GroupBuy) r[1]).getId()).toList();
        Map<Long, String> thumbnails = groupBuyIds.isEmpty() ? Map.of()
                : imageRepository.findThumbnails(groupBuyIds).stream()
                        .collect(Collectors.toMap(GroupBuyImage::getGroupBuyId, GroupBuyImage::getKey, (a, b) -> a));
        Map<Long, PickupOption> pickups = groupBuyIds.isEmpty() ? Map.of()
                : pickupOptionRepository.findByGroupBuyIdInAndSelectedTrue(groupBuyIds).stream()
                        .collect(Collectors.toMap(PickupOption::getGroupBuyId, Function.identity(), (a, b) -> a));

        List<Item> active = new ArrayList<>();
        List<Item> hosting = new ArrayList<>();
        List<Item> past = new ArrayList<>();
        for (Object[] row : rows) {
            Participation p = (Participation) row[0];
            GroupBuy g = (GroupBuy) row[1];
            PickupOption pickup = pickups.get(g.getId());
            String thumbnailKey = thumbnails.get(g.getId());
            Item item = new Item(g.getId(), g.getTitle(), g.getStatus(), g.getFailReason(),
                    thumbnailKey == null ? null : imageBaseUrl + "/" + thumbnailKey,
                    p.isHost(), p.getId(), p.getStatus(), p.getUnits(), p.getAmount(), g.getDeadlineAt(),
                    pickup == null ? null : pickup.getPickupAt(), pickup == null ? null : pickup.getPlaceName(),
                    todo(p, g, now));
            boolean inProgress = IN_PROGRESS.contains(g.getStatus()) && KEEPING_SEAT.contains(p.getStatus());
            if (!inProgress) {
                past.add(item);           // 완료·무산, 또는 내가 취소해 환불(대기)된 것
            } else if (p.isHost()) {
                hosting.add(item);
            } else {
                active.add(item);
            }
        }
        // 할 일이 있는 것 먼저, 그다음 마감이 가까운 순. 지난 공구는 최근 마감 순
        Comparator<Item> inProgressOrder = Comparator.comparing((Item i) -> i.todo() == null)
                .thenComparing(Item::deadlineAt);
        active.sort(inProgressOrder);
        hosting.sort(inProgressOrder);
        past.sort(Comparator.comparing(Item::deadlineAt).reversed());
        return new Response(active, hosting, past);
    }

    static Todo todo(Participation p, GroupBuy g, LocalDateTime now) {
        if (p.isPending() && !p.isExpired(now)
                && (g.getStatus() == GroupBuyStatus.OPEN || g.getStatus() == GroupBuyStatus.DRAFT)) {
            return Todo.PAY;
        }
        if (p.isHost() && g.getStatus() == GroupBuyStatus.CONFIRMED) {
            return Todo.PURCHASE;
        }
        if (!p.isHost() && p.getStatus() == ParticipationStatus.PAID && g.getStatus() == GroupBuyStatus.PICKUP) {
            return Todo.RECEIVE;
        }
        return null;
    }
}
