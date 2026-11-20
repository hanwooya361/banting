package com.banting.groupbuy;

import com.banting.user.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/** 공구 API 요청·응답. 시각은 한국 시간(Asia/Seoul) 기준 LocalDateTime ("2026-11-20T18:00"). */
public final class GroupBuyDtos {

    private GroupBuyDtos() {
    }

    public record CreateRequest(
            @NotBlank(message = "상품 링크를 입력해 주세요") @Size(max = 500, message = "링크가 너무 길어요")
            String productUrl,
            @NotBlank(message = "상품명을 입력해 주세요") @Size(min = 2, max = 100, message = "상품명은 2~100자로 써 주세요")
            String title,
            @Size(max = 2000, message = "설명은 2,000자까지 쓸 수 있어요")
            String description,
            @NotNull(message = "카테고리를 골라 주세요")
            Category category,
            @NotNull(message = "금액 방식을 골라 주세요")
            PriceType priceType,
            @NotNull(message = "총가를 입력해 주세요")
            @Min(value = 1_000, message = "총가는 1,000원 이상이어야 해요")
            @Max(value = 10_000_000, message = "총가는 1,000만 원까지 가능해요")
            Long totalPrice,
            @NotNull(message = "총수량을 입력해 주세요")
            @Min(value = 1, message = "총수량은 1 이상이어야 해요") @Max(value = 10_000, message = "총수량은 10,000까지 가능해요")
            Integer totalQuantity,
            @NotBlank(message = "단위를 입력해 주세요") @Size(max = 10, message = "단위는 10자까지 쓸 수 있어요")
            String unitLabel,
            Integer people,
            Integer minUnitsPerPerson,
            Integer hostUnits,
            @NotNull(message = "마감 시각을 정해 주세요")
            LocalDateTime deadlineAt,
            @Size(max = 5, message = "사진은 5장까지 올릴 수 있어요")
            List<String> imageKeys,
            @NotNull(message = "수령 일정을 1개 이상 정해 주세요")
            @Size(min = 1, max = 3, message = "수령 일정은 1~3개 정해 주세요")
            List<@Valid PickupOptionRequest> pickupOptions) {

        GroupBuy.Spec toSpec() {
            return new GroupBuy.Spec(title, description, category, productUrl, priceType, totalPrice,
                    totalQuantity, unitLabel, people, minUnitsPerPerson, hostUnits, deadlineAt);
        }
    }

    public record PickupOptionRequest(
            @NotNull(message = "수령 시각을 정해 주세요") LocalDateTime pickupAt,
            @NotBlank(message = "수령 장소를 입력해 주세요") @Size(min = 2, max = 100, message = "장소는 2~100자로 써 주세요")
            String placeName) {
    }

    /** null인 항목은 바꾸지 않는다. imageKeys는 보내면 전체 교체 (기존 사진 키 + 새로 올린 tmp 키, 순서대로). */
    public record UpdateRequest(
            @Size(min = 2, max = 100, message = "상품명은 2~100자로 써 주세요") String title,
            @Size(max = 2000, message = "설명은 2,000자까지 쓸 수 있어요") String description,
            Category category,
            LocalDateTime deadlineAt,
            @Size(max = 5, message = "사진은 5장까지 올릴 수 있어요") List<String> imageKeys) {
    }

    public record Detail(
            Long id,
            String title,
            String description,
            Category category,
            String productUrl,
            PriceType priceType,
            long totalPrice,
            int totalQuantity,
            String unitLabel,
            int capacityUnits,
            int minUnitsPerPerson,
            int reservedUnits,
            int paidUnits,
            long unitPrice,
            GroupBuyStatus status,
            String neighborhoodName,
            LocalDateTime deadlineAt,
            LocalDateTime createdAt,
            List<Image> images,
            List<PickupOptionView> pickupOptions,
            HostView host,
            boolean mine,
            Long myAmount,
            boolean demo,
            MyParticipation myParticipation,
            GroupBuy.FailReason failReason,
            Long myPickupVoteOptionId,     // 내가 투표한 수령 후보 (T15), 안 했으면 null
            SettlementView settlement) {   // 개설자에게만, 완료된 공구의 정산 기록 (T16)
    }

    public record SettlementView(long grossAmount, long feeAmount, long netAmount,
                                 com.banting.settlement.SettlementStatus status) {
    }

    /** 보는 사람의 참여 (없으면 null). 결제 대기면 expiresAt까지 결제를 이어 갈 수 있다. */
    public record MyParticipation(Long id, com.banting.participation.ParticipationStatus status, int units, long amount,
                                  boolean host, LocalDateTime expiresAt) {
    }

    /**
     * 목록 응답 (T09). 기준 위치의 좌표는 주지 않고 동네 이름만 준다.
     *
     * @param neighborhoodName 검색 기준 동네 이름. 동네 설정 전·비로그인이면 기본 위치 이름
     * @param defaultLocation  내 동네가 아니라 기본 위치(서울시청) 기준인지. 화면이 "내 동네 설정하기"를 띄우는 데 씀
     */
    public record ListResponse(
            String neighborhoodName,
            int radiusM,
            boolean defaultLocation,
            List<ListItem> items,
            int page,
            boolean hasNext) {
    }

    /** 목록 카드. 남은 자리 = capacityUnits − reservedUnits (결제 대기 중인 자리도 찬 것으로 본다). */
    public record ListItem(
            Long id,
            String title,
            Category category,
            PriceType priceType,
            long unitPrice,
            String unitLabel,
            int capacityUnits,
            int minUnitsPerPerson,
            int reservedUnits,
            int paidUnits,
            String neighborhoodName,
            LocalDateTime deadlineAt,
            LocalDateTime createdAt,
            String thumbnailUrl,
            boolean demo) {
    }

    /** key는 수정할 때 기존 사진을 유지하려고 돌려보낼 값, url은 화면 표시용. */
    public record Image(String key, String url) {
    }

    /** voteCount = 지금 결제 완료인 사람의 표만 (T15). selected = 확정 시 고른 수령 일정. */
    public record PickupOptionView(Long id, LocalDateTime pickupAt, String placeName, long voteCount,
                                   boolean selected) {
    }

    public record HostView(String nickname, Role role) {
    }
}
