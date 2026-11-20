package com.banting.pickup;

import com.banting.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 수령 후보 투표 (T15). UNIQUE(group_buy_id, user_id): 공구당 1표, 다시 투표하면 같은 행의 후보만 바꾼다. */
@Entity
@Table(name = "pickup_votes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PickupVote extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_buy_id", nullable = false)
    private Long groupBuyId;

    @Column(name = "pickup_option_id", nullable = false)
    private Long pickupOptionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    public static PickupVote of(Long groupBuyId, Long pickupOptionId, Long userId) {
        PickupVote vote = new PickupVote();
        vote.groupBuyId = groupBuyId;
        vote.pickupOptionId = pickupOptionId;
        vote.userId = userId;
        return vote;
    }

    public void changeTo(Long pickupOptionId) {
        this.pickupOptionId = pickupOptionId;
    }
}
