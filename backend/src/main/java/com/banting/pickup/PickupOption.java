package com.banting.pickup;

import com.banting.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 수령 후보 (개설자가 1~3개 등록). 공구가 확정되면 1위 후보 하나가 selected = 수령 일정 (T15). */
@Entity
@Table(name = "pickup_options")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PickupOption extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_buy_id", nullable = false)
    private Long groupBuyId;

    @Column(name = "pickup_at", nullable = false)
    private LocalDateTime pickupAt;

    @Column(name = "place_name", nullable = false, length = 100)
    private String placeName;

    @Column(nullable = false)
    private boolean selected;

    public static PickupOption of(Long groupBuyId, LocalDateTime pickupAt, String placeName) {
        PickupOption option = new PickupOption();
        option.groupBuyId = groupBuyId;
        option.pickupAt = pickupAt;
        option.placeName = placeName;
        return option;
    }

    /** 확정 시 수령 일정으로 선택. */
    public void select() {
        this.selected = true;
    }
}
