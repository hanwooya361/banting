package com.banting.notification;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인앱 알림 (T15). 알림은 상태를 바꾸는 트랜잭션 안에서 같이 쓴다 (MANDATORY: 트랜잭션 밖에서 부르면 예외).
 * 확정·무산이 커밋되면 알림도 커밋되고, 롤백되면 알림도 없다. 외부 발송이 없어 발송 실패 처리도 필요 없다.
 */
@Slf4j
@Service
public class NotificationService {

    static final Duration RETENTION = Duration.ofDays(30);
    private static final int TITLE_MAX = 30;
    private static final DateTimeFormatter PICKUP_FORMAT = DateTimeFormatter.ofPattern("M/d(E) HH:mm", Locale.KOREAN);

    private final NotificationRepository notificationRepository;
    private final Clock clock;
    private final boolean schedulerEnabled;

    public NotificationService(NotificationRepository notificationRepository, Clock clock,
                               @Value("${banting.scheduler.enabled:false}") boolean schedulerEnabled) {
        this.notificationRepository = notificationRepository;
        this.clock = clock;
        this.schedulerEnabled = schedulerEnabled;
    }

    public record Item(Long id, NotificationType type, Long groupBuyId, String message, boolean read,
                       LocalDateTime createdAt) {
    }

    /** @param hasNext 더 오래된 알림이 있는지. 다음 페이지는 마지막 항목 id를 before로 */
    public record ListResponse(long unreadCount, List<Item> items, boolean hasNext) {
    }

    /** 한 페이지 크기. 보관 기간(30일) 안의 알림은 더 보기로 모두 볼 수 있다. */
    static final int PAGE_SIZE = 20;

    // ───── 보내기 (상태 변경 트랜잭션 안에서만) ─────

    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmed(Collection<Long> userIds, Long groupBuyId, String title, LocalDateTime pickupAt,
                          String placeName) {
        String pickup = pickupAt == null ? "" : " 수령: %s · %s".formatted(PICKUP_FORMAT.format(pickupAt), placeName);
        send(userIds, NotificationType.GROUP_BUY_CONFIRMED, groupBuyId,
                "'%s' 공구가 다 모여서 확정됐어요.%s".formatted(shorten(title), pickup));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void failedByDeadline(Collection<Long> userIds, Long groupBuyId, String title) {
        send(userIds, NotificationType.GROUP_BUY_FAILED, groupBuyId,
                "'%s' 공구가 인원이 모이지 않아 무산됐어요. 결제한 금액은 전액 환불돼요.".formatted(shorten(title)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void canceledByHost(Collection<Long> userIds, Long groupBuyId, String title) {
        send(userIds, NotificationType.GROUP_BUY_CANCELED, groupBuyId,
                "'%s' 공구를 개설자가 취소했어요. 결제한 금액은 전액 환불돼요.".formatted(shorten(title)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void purchased(Collection<Long> userIds, Long groupBuyId, String title, LocalDateTime pickupAt,
                          String placeName) {
        String pickup = pickupAt == null ? "" : " %s · %s".formatted(PICKUP_FORMAT.format(pickupAt), placeName);
        send(userIds, NotificationType.GROUP_BUY_PURCHASED, groupBuyId,
                "'%s' 개설자가 상품을 샀어요. 약속 장소에서 만나요.%s".formatted(shorten(title), pickup));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void completed(Long hostId, Long groupBuyId, String title, long netAmount, long feeAmount) {
        send(java.util.List.of(hostId), NotificationType.GROUP_BUY_COMPLETED, groupBuyId,
                "'%s' 공구가 완료됐어요. 정산 예정 %,d원 (수수료 %,d원)".formatted(shorten(title), netAmount, feeAmount));
    }

    private void send(Collection<Long> userIds, NotificationType type, Long groupBuyId, String message) {
        notificationRepository.saveAll(userIds.stream().distinct()
                .map(userId -> Notification.of(userId, type, groupBuyId, message)).toList());
    }

    private static String shorten(String title) {
        return title.length() <= TITLE_MAX ? title : title.substring(0, TITLE_MAX) + "…";
    }

    // ───── 내 알림 ─────

    @Transactional(readOnly = true)
    public ListResponse list(Long userId, Long before) {
        // 한 개 더 가져와서 다음 페이지가 있는지 판단 (COUNT 쿼리 없이)
        List<Item> rows = notificationRepository
                .findPage(userId, before == null ? Long.MAX_VALUE : before, PageRequest.ofSize(PAGE_SIZE + 1)).stream()
                .map(n -> new Item(n.getId(), n.getType(), n.getGroupBuyId(), n.getMessage(), n.isRead(),
                        n.getCreatedAt()))
                .toList();
        boolean hasNext = rows.size() > PAGE_SIZE;
        return new ListResponse(notificationRepository.countByUserIdAndReadAtIsNull(userId),
                hasNext ? rows.subList(0, PAGE_SIZE) : rows, hasNext);
    }

    @Transactional
    public void read(Long userId, Long id) {
        notificationRepository.findById(id)
                .filter(n -> n.getUserId().equals(userId))   // 남의 알림은 없는 알림처럼
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND))
                .read(LocalDateTime.now(clock));
    }

    @Transactional
    public void readAll(Long userId) {
        notificationRepository.readAll(userId, LocalDateTime.now(clock));
    }

    // ───── 정리 ─────

    /** 30일 지난 알림 삭제. */
    @Transactional
    public int deleteOld() {
        int deleted = notificationRepository.deleteCreatedBefore(LocalDateTime.now(clock).minus(RETENTION));
        if (deleted > 0) {
            log.info("30일 지난 알림 삭제: {}건", deleted);
        }
        return deleted;
    }

    /** 매일 04:10 (refresh 토큰 정리 04:00 다음). 스케줄러가 꺼져 있으면(테스트) 돌지 않는다. */
    @Scheduled(cron = "0 10 4 * * *", zone = "Asia/Seoul")
    void daily() {
        if (schedulerEnabled) {
            deleteOld();
        }
    }
}
