package com.banting.groupbuy;

import com.banting.common.BaseTimeEntity;
import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

/**
 * 공구. 생성·수정 규칙과 상태 전이는 이 엔티티 메서드에서만 검증한다 (CLAUDE.md: 정해진 전이만 허용).
 *
 * <p>규칙 (T08)
 * <ul>
 *   <li>상품 링크 필수, http/https만 (javascript: 같은 링크로 클릭한 사람을 공격하지 못하게)</li>
 *   <li>마감은 지금부터 1시간 ~ 14일</li>
 *   <li>균등: 2~20명, 한 사람 한 자리. 수량별: 총수량 2~10,000, 개설자 몫을 빼고도 최소 수량만큼 자리가 남아야 함</li>
 *   <li>수정은 설명성 항목(상품명·설명·카테고리·사진)과 마감 연장만. 금액·수량·인원·링크는 결제 금액이 바뀌므로 불가</li>
 * </ul>
 */
@Entity
@Table(name = "group_buys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupBuy extends BaseTimeEntity {

    static final Duration MIN_DEADLINE = Duration.ofHours(1);
    static final Duration MAX_DEADLINE = Duration.ofDays(14);
    static final int MAX_PEOPLE = 20;
    static final int MAX_QUANTITY = 10_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "host_id", nullable = false)
    private Long hostId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Category category;

    @Column(name = "product_url", nullable = false, length = 500)
    private String productUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_type", nullable = false, length = 15)
    private PriceType priceType;

    @Column(name = "total_price", nullable = false)
    private long totalPrice;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @Column(name = "unit_label", nullable = false, length = 10)
    private String unitLabel;

    @Column(name = "capacity_units", nullable = false)
    private int capacityUnits;

    @Column(name = "min_units_per_person", nullable = false)
    private int minUnitsPerPerson;

    @Column(name = "reserved_units", nullable = false)
    private int reservedUnits;

    @Column(name = "paid_units", nullable = false)
    private int paidUnits;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private GroupBuyStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "fail_reason", length = 20)
    private FailReason failReason;

    /** null = 실제 공구. 시드(SEED)는 DemoSeeder가 SQL로만 넣고, 일반 개설은 항상 null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "demo_type", length = 10)
    private DemoType demoType;

    /**
     * 개설자 동네 좌표. 회원 좌표를 그대로 복사한다 (GeoPoint.toWkt()와 같은 (위도 경도) WKT).
     * POINT 컬럼을 문자열로 다루되, 쓸 때 ST_PointFromText, 읽을 때 ST_AsText를 DB가 적용한다.
     * columnDefinition은 스키마 검증(ddl-auto=validate)용: MySQL 드라이버가 공간 컬럼 타입을 "geometry"로 보고하므로
     * 그 이름과 맞춘다. 실제 정의(POINT SRID 4326, SPATIAL INDEX)는 Flyway V1이 관리한다.
     */
    @Column(name = "location", nullable = false, columnDefinition = "geometry")
    @ColumnTransformer(read = "ST_AsText(location)", write = "ST_PointFromText(?, 4326)")
    private String locationWkt;

    @Column(name = "neighborhood_name", nullable = false, length = 50)
    private String neighborhoodName;

    @Column(name = "deadline_at", nullable = false)
    private LocalDateTime deadlineAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** 낙관적 락: 같은 공구를 동시에 수정하면 나중 커밋이 실패한다. */
    @Version
    private long version;

    public enum FailReason {
        DEADLINE_UNMET,
        HOST_CANCELED
    }

    /** 개설 요청 값 (검증 전). */
    public record Spec(String title, String description, Category category, String productUrl,
                       PriceType priceType, long totalPrice, int totalQuantity, String unitLabel,
                       Integer people, Integer minUnitsPerPerson, Integer hostUnits, LocalDateTime deadlineAt) {
    }

    /** 개설: DRAFT로 만들고 개설자 몫만큼 자리를 미리 잡아 둔다 (개설자 결제는 T11). */
    public static GroupBuy create(Long hostId, Spec spec, String locationWkt, String neighborhoodName,
                                  LocalDateTime now) {
        GroupBuy g = new GroupBuy();
        g.hostId = hostId;
        g.title = spec.title().trim();
        g.description = spec.description() == null ? "" : spec.description().trim();
        g.category = spec.category();
        g.productUrl = validateProductUrl(spec.productUrl());
        g.priceType = spec.priceType();
        g.totalPrice = spec.totalPrice();
        g.totalQuantity = spec.totalQuantity();
        g.unitLabel = spec.unitLabel().trim();
        g.deadlineAt = validateDeadline(spec.deadlineAt(), now);
        g.locationWkt = locationWkt;
        g.neighborhoodName = neighborhoodName;
        g.status = GroupBuyStatus.DRAFT;

        int hostUnits;
        if (spec.priceType() == PriceType.EQUAL) {
            int people = spec.people() == null ? 0 : spec.people();
            if (people < 2 || people > MAX_PEOPLE || spec.totalQuantity() < 1) {
                throw new BusinessException(ErrorCode.INVALID_UNITS);
            }
            g.capacityUnits = people;
            g.minUnitsPerPerson = 1;
            hostUnits = 1;
        } else {
            int min = spec.minUnitsPerPerson() == null ? 0 : spec.minUnitsPerPerson();
            hostUnits = spec.hostUnits() == null ? 0 : spec.hostUnits();
            int quantity = spec.totalQuantity();
            // 개설자도 최소 수량 이상, 개설자 몫을 빼고 최소 한 명은 더 참여할 수 있어야 공동구매가 된다
            if (quantity < 2 || quantity > MAX_QUANTITY || min < 1 || hostUnits < min || quantity - hostUnits < min) {
                throw new BusinessException(ErrorCode.INVALID_UNITS);
            }
            g.capacityUnits = quantity;
            g.minUnitsPerPerson = min;
        }
        g.pricing();   // 1자리당 1원 미만이면 여기서 거부
        g.reservedUnits = hostUnits;
        return g;
    }

    public Pricing pricing() {
        try {
            return priceType == PriceType.EQUAL
                    ? Pricing.equal(totalPrice, capacityUnits)
                    : Pricing.byQuantity(totalPrice, totalQuantity);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_PRICE);
        }
    }

    /** 개설자 몫 자리 수 (개설 직후 reserved_units). */
    public int hostUnitsAtCreation() {
        return reservedUnits;
    }

    // ───── 수정·삭제 ─────

    public void checkHost(Long userId) {
        if (!hostId.equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_GROUP_BUY_HOST);
        }
    }

    /** 수정은 모집이 끝나기 전(DRAFT, OPEN)까지만. 참여자 유무는 서비스가 잠금 후 확인한다. */
    public void checkEditable() {
        if (status != GroupBuyStatus.DRAFT && status != GroupBuyStatus.OPEN) {
            throw new BusinessException(ErrorCode.GROUP_BUY_NOT_EDITABLE);
        }
    }

    /** T08: 결제 전(DRAFT)만 삭제. 결제된 공구 삭제는 개설자 환불이 필요해 T12에서. */
    public void checkDeletable() {
        if (status != GroupBuyStatus.DRAFT) {
            throw new BusinessException(ErrorCode.GROUP_BUY_NOT_DELETABLE);
        }
    }

    public void changeDetails(String title, String description, Category category) {
        if (title != null) {
            this.title = title.trim();
        }
        if (description != null) {
            this.description = description.trim();
        }
        if (category != null) {
            this.category = category;
        }
    }

    /** 마감은 늦추기만 (이미 참여를 고민 중인 사람에게 불리하게 바뀌지 않게). */
    public void extendDeadline(LocalDateTime newDeadline, LocalDateTime now) {
        if (!newDeadline.isAfter(deadlineAt)) {
            throw new BusinessException(ErrorCode.DEADLINE_ONLY_EXTEND);
        }
        this.deadlineAt = validateDeadline(newDeadline, now);
    }

    public boolean isDraft() {
        return status == GroupBuyStatus.DRAFT;
    }

    public boolean isDemo() {
        return demoType != null;
    }

    // ───── 참여·결제 (T11) ─────

    /** 참여자가 자리를 잡을 수 있는 상태: 모집 중이고 마감 전. (실제 자리 확보는 조건부 UPDATE가 다시 확인) */
    public void checkJoinable(LocalDateTime now) {
        if (status != GroupBuyStatus.OPEN || !deadlineAt.isAfter(now)) {
            throw new BusinessException(ErrorCode.GROUP_BUY_NOT_OPEN);
        }
    }

    /**
     * 자리 잡기, 비관적 락 방식 (T13 비교용, {@code SeatLock.PESSIMISTIC}). 이 행을 FOR UPDATE로 잠근 상태에서만 부른다.
     * 기본 방식은 같은 조건을 SQL 한 문장에 넣은 {@code GroupBuyRepository.reserveUnits}.
     */
    public void reserve(int units, LocalDateTime now) {
        checkJoinable(now);
        if (reservedUnits + units > capacityUnits) {
            throw new BusinessException(ErrorCode.GROUP_BUY_FULL);
        }
        reservedUnits += units;
    }

    /** 개설자가 내 몫을 결제할 수 있는 상태: 결제 전이고 마감 전. */
    public void checkHostPayable(LocalDateTime now) {
        if (status != GroupBuyStatus.DRAFT || !deadlineAt.isAfter(now)) {
            throw new BusinessException(ErrorCode.GROUP_BUY_NOT_OPEN);
        }
    }

    /**
     * 결제 승인 반영. 개설자 결제면 DRAFT → OPEN(목록 공개), 결제된 자리가 정원과 같아지면 OPEN → CONFIRMED.
     * 허용되지 않은 상태면 예외 → 호출 쪽 트랜잭션이 롤백되고 토스 결제를 취소한다.
     * (확정 시 수령 일정 선택은 투표 티켓 T15에서)
     */
    public void markPaid(int units, boolean byHost, LocalDateTime now) {
        if (byHost) {
            if (status != GroupBuyStatus.DRAFT) {
                throw new IllegalStateException("개설자 결제는 DRAFT에서만: " + status);
            }
            status = GroupBuyStatus.OPEN;
        } else if (status != GroupBuyStatus.OPEN) {
            throw new IllegalStateException("참여자 결제는 OPEN에서만: " + status);
        }
        if (paidUnits + units > reservedUnits) {
            throw new IllegalStateException("결제된 자리가 잡힌 자리보다 많아질 수 없음");
        }
        paidUnits += units;
        if (paidUnits == capacityUnits) {
            status = GroupBuyStatus.CONFIRMED;
            confirmedAt = now;
        }
    }

    // ───── 취소 (T12) ─────

    /** 결제 후 취소는 모집 중이고 마감 전에만. 확정되면 개설자가 살 수 있는 상태라 빠질 수 없다. */
    public void checkCancelable(LocalDateTime now) {
        if (status != GroupBuyStatus.OPEN || !deadlineAt.isAfter(now)) {
            throw new BusinessException(ErrorCode.PARTICIPATION_NOT_CANCELABLE);
        }
    }

    /** 참여 취소로 자리 반환. paid면 결제된 자리도 함께 줄인다. */
    public void release(int units, boolean paid) {
        if (units > reservedUnits || (paid && units > paidUnits)) {
            throw new IllegalStateException("반환할 자리가 잡힌 자리보다 많음");
        }
        reservedUnits -= units;
        if (paid) {
            paidUnits -= units;
        }
    }

    /** 개설자 취소: 모집 중에만. 참여자 환불은 서비스가 이어서 처리한다. 자리 수는 기록으로 그대로 둔다. */
    public void cancelByHost() {
        if (status != GroupBuyStatus.OPEN) {
            throw new BusinessException(ErrorCode.GROUP_BUY_NOT_CANCELABLE);
        }
        status = GroupBuyStatus.FAILED;
        failReason = FailReason.HOST_CANCELED;
    }

    /**
     * 마감 미달 실패 (T14 스케줄러). 모집 중이고 마감이 지났을 때만. 정원이 차면 결제 순간 이미 CONFIRMED라 여기 오지 않는다.
     * 참여자 환불은 서비스가 이어서 처리한다. 자리 수는 개설자 취소처럼 기록으로 그대로 둔다.
     */
    public void failByDeadline(LocalDateTime now) {
        if (status != GroupBuyStatus.OPEN || deadlineAt.isAfter(now)) {
            throw new IllegalStateException("마감 미달 실패는 마감이 지난 OPEN 공구만: " + status + ", " + deadlineAt);
        }
        status = GroupBuyStatus.FAILED;
        failReason = FailReason.DEADLINE_UNMET;
    }

    public boolean isOpen() {
        return status == GroupBuyStatus.OPEN;
    }

    public boolean isCanceledByHost() {
        return status == GroupBuyStatus.FAILED && failReason == FailReason.HOST_CANCELED;
    }

    // ───── 검증 ─────

    static String validateProductUrl(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 500) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCT_URL);
        }
        String url = raw.trim();
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                throw new BusinessException(ErrorCode.INVALID_PRODUCT_URL);
            }
        } catch (URISyntaxException e) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCT_URL);
        }
        return url;
    }

    static LocalDateTime validateDeadline(LocalDateTime deadline, LocalDateTime now) {
        if (deadline == null || deadline.isBefore(now.plus(MIN_DEADLINE)) || deadline.isAfter(now.plus(MAX_DEADLINE))) {
            throw new BusinessException(ErrorCode.INVALID_DEADLINE);
        }
        return deadline;
    }
}
