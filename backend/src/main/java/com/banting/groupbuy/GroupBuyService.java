package com.banting.groupbuy;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import com.banting.common.geo.GeoPoint;
import com.banting.common.storage.ImageStorage;
import com.banting.groupbuy.GroupBuyDtos.CreateRequest;
import com.banting.groupbuy.GroupBuyDtos.Detail;
import com.banting.groupbuy.GroupBuyDtos.ListItem;
import com.banting.groupbuy.GroupBuyDtos.ListResponse;
import com.banting.groupbuy.GroupBuyDtos.PickupOptionRequest;
import com.banting.groupbuy.GroupBuyDtos.UpdateRequest;
import com.banting.participation.Participation;
import com.banting.participation.ParticipationRepository;
import com.banting.participation.ParticipationStatus;
import com.banting.payment.PaymentRepository;
import com.banting.payment.PaymentStatus;
import com.banting.payment.RefundService;
import com.banting.pickup.PickupOption;
import com.banting.pickup.PickupOptionRepository;
import com.banting.user.User;
import com.banting.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 공구 개설·조회·수정·삭제 (T08), 목록 (T09).
 *
 * <p>트랜잭션 경계: S3 같은 외부 호출은 트랜잭션 밖에서 먼저 끝내고, DB 변경은 TransactionTemplate 안의
 * 짧은 작업 하나로 묶는다. DB 작업이 실패하면 먼저 복사해 둔 사진을 지워 되돌린다(보상).
 * 반대로 DB 삭제가 커밋된 뒤에만 S3 사진을 지운다 (롤백됐는데 사진만 사라지는 일이 없게).
 */
@Slf4j
@Service
public class GroupBuyService {

    static final Duration PAYMENT_WINDOW = Duration.ofMinutes(10);
    static final Duration MAX_PICKUP_AFTER_DEADLINE = Duration.ofDays(14);
    /** 돈이 오간 결제 상태. 이 기록이 있으면 공구를 지우지 않는다. */
    private static final Set<PaymentStatus> MONEY_MOVED = EnumSet.of(PaymentStatus.DONE, PaymentStatus.CANCELED);

    private static final Set<ParticipationStatus> OCCUPYING =
            EnumSet.of(ParticipationStatus.PENDING_PAYMENT, ParticipationStatus.PAID);
    private static final Pattern IMAGE_FILE = Pattern.compile("[0-9a-f\\-]{36}\\.(jpg|png|webp)");

    // 목록: 동네 설정 전·비로그인은 서울시청 기준 (screen-design 흐름 1)
    static final GeoPoint DEFAULT_CENTER = new GeoPoint(37.5665, 126.9780);
    static final String DEFAULT_NEIGHBORHOOD = "서울시청";
    static final int DEFAULT_RADIUS_M = 1000;
    static final Set<Integer> ALLOWED_RADII = Set.of(500, 1000, 3000);
    static final int PAGE_SIZE = 20;
    private static final int MAX_PAGE = 500;
    private static final int MAX_KEYWORD_LENGTH = 50;

    private final GroupBuyRepository groupBuyRepository;
    private final GroupBuyImageRepository imageRepository;
    private final ParticipationRepository participationRepository;
    private final PickupOptionRepository pickupOptionRepository;
    private final UserRepository userRepository;
    private final GroupBuySpatialQuery spatialQuery;
    private final ImageStorage imageStorage;
    private final PaymentRepository paymentRepository;
    private final RefundService refundService;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String imageBaseUrl;

    public GroupBuyService(GroupBuyRepository groupBuyRepository, GroupBuyImageRepository imageRepository,
                           ParticipationRepository participationRepository,
                           PickupOptionRepository pickupOptionRepository, UserRepository userRepository,
                           GroupBuySpatialQuery spatialQuery, ImageStorage imageStorage,
                           PaymentRepository paymentRepository, RefundService refundService,
                           TransactionTemplate tx, Clock clock,
                           @Value("${banting.images.public-base-url:}") String imageBaseUrl) {
        this.groupBuyRepository = groupBuyRepository;
        this.imageRepository = imageRepository;
        this.participationRepository = participationRepository;
        this.pickupOptionRepository = pickupOptionRepository;
        this.userRepository = userRepository;
        this.spatialQuery = spatialQuery;
        this.imageStorage = imageStorage;
        this.paymentRepository = paymentRepository;
        this.refundService = refundService;
        this.tx = tx;
        this.clock = clock;
        this.imageBaseUrl = imageBaseUrl;
    }

    // ───── 개설 ─────

    public Detail create(Long userId, CreateRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        UserRepository.LocationView location = userRepository.findLocation(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOCATION_REQUIRED));

        // 1) 검증을 모두 먼저 (외부 호출 전에 빨리 실패)
        GroupBuy groupBuy = GroupBuy.create(userId, request.toSpec(), location.getWkt(),
                location.getNeighborhoodName(), now);
        validatePickupOptions(request.pickupOptions(), groupBuy.getDeadlineAt());
        int hostUnits = groupBuy.hostUnitsAtCreation();
        long hostAmount = groupBuy.pricing().hostAmount(hostUnits);

        // 2) 외부 호출: tmp 사진을 공개 위치로 복사 (트랜잭션 밖)
        List<String> published = new ArrayList<>(publishImages(userId, nullToEmpty(request.imageKeys())).values());

        // 3) DB: 공구 + 개설자 참여(결제 대기, 자리 예약) + 수령 후보 + 사진을 한 트랜잭션으로
        try {
            Long id = tx.execute(status -> {
                groupBuyRepository.save(groupBuy);
                Long groupBuyId = groupBuy.getId();
                participationRepository.save(Participation.hostPending(
                        groupBuyId, userId, hostUnits, hostAmount, now.plus(PAYMENT_WINDOW)));
                request.pickupOptions().forEach(option -> pickupOptionRepository.save(
                        PickupOption.of(groupBuyId, option.pickupAt(), option.placeName().trim())));
                saveImages(groupBuyId, published);
                return groupBuyId;
            });
            return get(id, userId);
        } catch (RuntimeException e) {
            deleteQuietly(published);   // 보상: DB 저장이 실패하면 복사한 사진 정리
            throw e;
        }
    }

    // ───── 목록 (T09) ─────

    /**
     * 내 동네 반경 안의 모집 중 공구. 기준점은 화면이 보낸 좌표가 아니라 서버에 저장된 내 동네 좌표다
     * (좌표를 받으면 여러 지점에서 검색해 개설자 위치를 좁혀 낼 수 있음). 동네가 없으면 서울시청 기준.
     *
     * <p>시드(예시) 공구는 기본 위치(서울시청) 기준일 때만 섞는다. 내 동네 목록에 가짜 공구가 섞이면 안 되므로,
     * 내 동네가 비어 있으면 화면이 demoArea=true로 "체험 동네"를 따로 보여 준다 (T09.5).
     *
     * @param radiusM  화면에서 반경 칩을 고른 값. null이면 저장된 반경(비로그인은 1km)
     * @param demoArea true면 내 동네가 있어도 체험 동네(서울시청) 기준
     */
    public ListResponse list(Long viewerId, Integer radiusM, Category category, String keyword,
                             GroupBuySort sort, int page, boolean demoArea) {
        if (page < 0 || page > MAX_PAGE || (radiusM != null && !ALLOWED_RADII.contains(radiusM))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        String trimmed = keyword == null ? null : keyword.strip();
        if (trimmed != null && trimmed.length() > MAX_KEYWORD_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        UserRepository.LocationView mine = viewerId == null || demoArea
                ? null : userRepository.findLocation(viewerId).orElse(null);
        GeoPoint center = mine == null ? DEFAULT_CENTER : GeoPoint.fromWkt(mine.getWkt());
        int radius = radiusM != null ? radiusM : mine == null ? DEFAULT_RADIUS_M : mine.getSearchRadiusM();
        String neighborhood = mine == null ? DEFAULT_NEIGHBORHOOD : mine.getNeighborhoodName();

        // 한 개 더 가져와서 다음 페이지가 있는지 판단 (COUNT 쿼리 없이)
        List<GroupBuySpatialQuery.Row> rows = spatialQuery.search(new GroupBuySpatialQuery.Condition(
                center, radius, category, trimmed == null || trimmed.isEmpty() ? null : trimmed,
                sort == null ? GroupBuySort.DEADLINE : sort, LocalDateTime.now(clock),
                PAGE_SIZE + 1, page * PAGE_SIZE, mine == null));
        boolean hasNext = rows.size() > PAGE_SIZE;
        List<ListItem> items = rows.stream().limit(PAGE_SIZE).map(this::toListItem).toList();
        return new ListResponse(neighborhood, radius, mine == null, items, page, hasNext);
    }

    private ListItem toListItem(GroupBuySpatialQuery.Row row) {
        long unitPrice = Pricing.of(row.priceType(), row.totalPrice(), row.capacityUnits()).unitPrice();
        return new ListItem(row.id(), row.title(), row.category(), row.priceType(), unitPrice, row.unitLabel(),
                row.capacityUnits(), row.minUnitsPerPerson(), row.reservedUnits(), row.paidUnits(),
                row.neighborhoodName(), row.deadlineAt(), row.createdAt(),
                row.thumbnailKey() == null ? null : imageBaseUrl + "/" + row.thumbnailKey(),
                row.demoType() != null);
    }

    // ───── 조회 ─────

    /** 결제 전(DRAFT) 공구는 개설자 본인에게만 보인다. 남에게는 없는 공구처럼 404. */
    public Detail get(Long id, Long viewerId) {
        GroupBuy groupBuy = groupBuyRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_BUY_NOT_FOUND));
        boolean mine = groupBuy.getHostId().equals(viewerId);
        if (groupBuy.isDraft() && !mine) {
            throw new BusinessException(ErrorCode.GROUP_BUY_NOT_FOUND);
        }
        User host = userRepository.findById(groupBuy.getHostId()).orElseThrow();
        Long myAmount = mine
                ? participationRepository.findByGroupBuyIdAndHostTrue(id).map(Participation::getAmount).orElse(null)
                : null;
        return new Detail(
                groupBuy.getId(), groupBuy.getTitle(), groupBuy.getDescription(), groupBuy.getCategory(),
                groupBuy.getProductUrl(), groupBuy.getPriceType(), groupBuy.getTotalPrice(),
                groupBuy.getTotalQuantity(), groupBuy.getUnitLabel(), groupBuy.getCapacityUnits(),
                groupBuy.getMinUnitsPerPerson(), groupBuy.getReservedUnits(), groupBuy.getPaidUnits(),
                groupBuy.pricing().unitPrice(), groupBuy.getStatus(), groupBuy.getNeighborhoodName(),
                groupBuy.getDeadlineAt(), groupBuy.getCreatedAt(),
                imageRepository.findByGroupBuyIdOrderBySortOrderAsc(id).stream()
                        .map(image -> new GroupBuyDtos.Image(image.getKey(), imageBaseUrl + "/" + image.getKey()))
                        .toList(),
                pickupOptionRepository.findByGroupBuyIdOrderByPickupAtAsc(id).stream()
                        .map(o -> new GroupBuyDtos.PickupOptionView(o.getId(), o.getPickupAt(), o.getPlaceName()))
                        .toList(),
                new GroupBuyDtos.HostView(host.getNickname(), host.getRole()),
                mine, myAmount, groupBuy.isDemo(),
                viewerId == null ? null : participationRepository.findByGroupBuyIdAndUserId(id, viewerId)
                        .map(p -> new GroupBuyDtos.MyParticipation(p.getId(), p.getStatus(), p.getUnits(), p.getAmount(),
                                p.isHost(), p.getExpiresAt()))
                        .orElse(null),
                groupBuy.getFailReason());
    }

    // ───── 개설자 취소 (T12) ─────

    /** refunded = 환불까지 끝난 참여 수, pendingRefunds = 토스 취소가 실패해 환불 대기로 남은 수. */
    public record CancelResult(int refunded, int pendingRefunds) {
    }

    /**
     * 모집 중 공구를 개설자가 취소 → FAILED(HOST_CANCELED), 개설자 포함 결제한 사람 전원 환불, 결제 대기는 취소.
     * 한 트랜잭션에서 상태를 모두 바꾼 뒤(REFUND_PENDING), 토스 취소는 트랜잭션 밖에서 한 건씩.
     * 일부가 실패하면 그 건만 환불 대기로 남고, 개설자가 다시 취소를 누르면 남은 건만 재시도한다.
     */
    public CancelResult cancel(Long userId, Long id) {
        tx.executeWithoutResult(status -> {
            GroupBuy groupBuy = groupBuyRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_BUY_NOT_FOUND));
            groupBuy.checkHost(userId);
            if (groupBuy.isCanceledByHost()) {
                return;   // 이미 취소됨: 남은 환불만 재시도
            }
            groupBuy.cancelByHost();
            for (Participation participation : participationRepository.findByGroupBuyId(id)) {
                switch (participation.getStatus()) {
                    case PAID -> participation.requestRefund();
                    case PENDING_PAYMENT -> {
                        participation.cancelPending();
                        paymentRepository.findByParticipationIdAndStatus(participation.getId(), PaymentStatus.READY)
                                .forEach(payment -> payment.fail(null, "개설자 취소"));
                    }
                    default -> {
                    }
                }
            }
        });

        int refunded = 0;
        int pending = 0;
        for (Participation participation : participationRepository.findByGroupBuyId(id)) {
            if (participation.getStatus() == ParticipationStatus.REFUNDED) {
                refunded++;
            } else if (participation.isRefundPending()) {
                if (refundService.refund(participation.getId(), "개설자 공구 취소")) {
                    refunded++;
                } else {
                    pending++;
                }
            }
        }
        return new CancelResult(refunded, pending);
    }

    // ───── 수정 ─────

    public Detail update(Long userId, Long id, UpdateRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        // 남의 공구에 사진을 복사해 두는 일이 없도록, 외부 호출 전에 가볍게 먼저 확인 (최종 확인은 잠금 후 다시)
        GroupBuy snapshot = groupBuyRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_BUY_NOT_FOUND));
        snapshot.checkHost(userId);
        snapshot.checkEditable();

        List<String> requestedKeys = request.imageKeys();
        Map<String, String> newlyPublished = requestedKeys == null ? Map.of()
                : publishImages(userId, requestedKeys.stream().filter(k -> k.startsWith(ImageStorage.TMP_PREFIX)).toList());

        try {
            List<String> removedKeys = tx.execute(status -> {
                GroupBuy groupBuy = groupBuyRepository.findByIdForUpdate(id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_BUY_NOT_FOUND));
                groupBuy.checkHost(userId);
                groupBuy.checkEditable();
                ensureNoOtherParticipants(id);

                groupBuy.changeDetails(request.title(), request.description(), request.category());
                if (request.deadlineAt() != null) {
                    groupBuy.extendDeadline(request.deadlineAt(), now);
                    // 마감을 늦춰도 수령 일정은 마감 이후여야 한다
                    boolean pickupBeforeDeadline = pickupOptionRepository.findByGroupBuyIdOrderByPickupAtAsc(id)
                            .stream().anyMatch(o -> !o.getPickupAt().isAfter(groupBuy.getDeadlineAt()));
                    if (pickupBeforeDeadline) {
                        throw new BusinessException(ErrorCode.INVALID_PICKUP_OPTION);
                    }
                }
                return requestedKeys == null ? List.<String>of() : replaceImages(id, requestedKeys, newlyPublished);
            });
            deleteQuietly(removedKeys);   // 커밋 후에만 빠진 사진 삭제
            return get(id, userId);
        } catch (RuntimeException e) {
            deleteQuietly(newlyPublished.values());
            throw e;
        }
    }

    // ───── 삭제 ─────

    public void delete(Long userId, Long id) {
        List<String> imageKeys = tx.execute(status -> {
            GroupBuy groupBuy = groupBuyRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_BUY_NOT_FOUND));
            groupBuy.checkHost(userId);
            groupBuy.checkDeletable();
            ensureNoOtherParticipants(id);
            List<String> keys = deleteDraftRows(groupBuy);
            if (keys == null) {
                throw new BusinessException(ErrorCode.GROUP_BUY_NOT_DELETABLE);
            }
            return keys;
        });
        deleteQuietly(imageKeys);
    }

    /**
     * 개설 대기(DRAFT) 공구와 딸린 행 삭제. 직접 삭제(위)와 결제 시간 초과(T14 스케줄러)가 함께 쓴다.
     * 공구 행을 FOR UPDATE로 잠근 트랜잭션 안에서만 부른다. 사진 파일은 커밋 후 호출 쪽이 지운다.
     *
     * <p>개설자가 결제창을 열면 결제 대기 주문(payments)이 생기므로 참여 행보다 먼저 지운다 (FK RESTRICT).
     * 돈이 오간 기록(승인 DONE·보상 취소 CANCELED)이 있으면 추적할 수 있게 지우지 않고 null.
     *
     * @return 커밋 후 지울 사진 키, 지우지 않았으면 null
     */
    List<String> deleteDraftRows(GroupBuy groupBuy) {
        Long id = groupBuy.getId();
        List<Participation> participations = participationRepository.findByGroupBuyId(id);
        for (Participation participation : participations) {
            if (paymentRepository.existsByParticipationIdAndStatusIn(participation.getId(), MONEY_MOVED)) {
                log.error("돈이 오간 결제 기록이 있어 개설 대기 공구를 지우지 않음(수동 확인) groupBuyId={}", id);
                return null;
            }
        }
        List<String> keys = imageRepository.findByGroupBuyIdOrderBySortOrderAsc(id).stream()
                .map(GroupBuyImage::getKey).toList();
        imageRepository.deleteByGroupBuyId(id);
        pickupOptionRepository.deleteByGroupBuyId(id);
        participations.forEach(participation -> paymentRepository.deleteByParticipationId(participation.getId()));
        participationRepository.deleteByGroupBuyId(id);   // DRAFT라 개설자 결제 대기 건만 있음
        groupBuyRepository.delete(groupBuy);
        return keys;
    }

    // ───── 내부 ─────

    private void ensureNoOtherParticipants(Long groupBuyId) {
        if (participationRepository.countByGroupBuyIdAndHostFalseAndStatusIn(groupBuyId, OCCUPYING) > 0) {
            throw new BusinessException(ErrorCode.GROUP_BUY_HAS_PARTICIPANTS);
        }
    }

    private static void validatePickupOptions(List<PickupOptionRequest> options, LocalDateTime deadline) {
        Set<LocalDateTime> times = new HashSet<>();
        for (PickupOptionRequest option : options) {
            LocalDateTime at = option.pickupAt();
            if (!at.isAfter(deadline) || at.isAfter(deadline.plus(MAX_PICKUP_AFTER_DEADLINE)) || !times.add(at)) {
                throw new BusinessException(ErrorCode.INVALID_PICKUP_OPTION);
            }
        }
    }

    /**
     * 회원이 직접 올린 tmp 사진인지 확인하고 공개 위치로 복사한다. 반환: tmp 키 → 공개 키 (순서 유지).
     * 키 형식과 소유자(경로의 회원 id)를 검사해, 남이 올린 파일이나 임의 경로를 끌어오지 못하게 한다.
     */
    private Map<String, String> publishImages(Long userId, List<String> tmpKeys) {
        if (tmpKeys.size() > 5 || new HashSet<>(tmpKeys).size() != tmpKeys.size()) {
            throw new BusinessException(ErrorCode.INVALID_IMAGE);
        }
        String ownPrefix = ImageStorage.TMP_PREFIX + userId + "/";
        for (String key : tmpKeys) {
            String file = key.startsWith(ownPrefix) ? key.substring(ownPrefix.length()) : "";
            if (!IMAGE_FILE.matcher(file).matches() || !imageStorage.exists(key)) {
                throw new BusinessException(ErrorCode.INVALID_IMAGE);
            }
        }
        Map<String, String> published = new LinkedHashMap<>();
        try {
            for (String key : tmpKeys) {
                String target = ImageStorage.PUBLISHED_PREFIX + key.substring(ownPrefix.length());
                imageStorage.copy(key, target);
                published.put(key, target);
            }
        } catch (RuntimeException e) {
            deleteQuietly(published.values());
            throw e;
        }
        return published;
    }

    /** 사진 전체 교체. 요청 키는 이 공구의 기존 사진이거나 방금 복사한 새 사진이어야 한다. 반환: 빠진 기존 사진 키. */
    private List<String> replaceImages(Long groupBuyId, List<String> requestedKeys, Map<String, String> newlyPublished) {
        List<String> existing = imageRepository.findByGroupBuyIdOrderBySortOrderAsc(groupBuyId).stream()
                .map(GroupBuyImage::getKey).toList();
        List<String> finalKeys = new ArrayList<>();
        for (String key : requestedKeys) {
            String resolved = newlyPublished.containsKey(key) ? newlyPublished.get(key) : key;
            if (!newlyPublished.containsKey(key) && !existing.contains(key)) {
                throw new BusinessException(ErrorCode.INVALID_IMAGE);
            }
            finalKeys.add(resolved);
        }
        imageRepository.deleteByGroupBuyId(groupBuyId);
        imageRepository.flush();
        saveImages(groupBuyId, finalKeys);
        return existing.stream().filter(k -> !finalKeys.contains(k)).toList();
    }

    private void saveImages(Long groupBuyId, List<String> keys) {
        for (int i = 0; i < keys.size(); i++) {
            imageRepository.save(GroupBuyImage.of(groupBuyId, keys.get(i), i));
        }
    }

    /** S3 정리는 실패해도 사용자 요청을 실패시키지 않는다 (로그로 남겨 나중에 정리). */
    void deleteQuietly(Iterable<String> keys) {
        for (String key : keys) {
            try {
                imageStorage.delete(key);
            } catch (RuntimeException e) {
                log.warn("사진 삭제 실패(정리 필요): {}", key);
            }
        }
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }
}
