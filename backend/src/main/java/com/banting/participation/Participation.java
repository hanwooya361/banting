package com.banting.participation;

import com.banting.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 공구 참여 (한 공구에 한 사람당 한 행). 결제·취소 전이는 T11·T12에서 추가한다. */
@Entity
@Table(name = "participations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Participation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_buy_id", nullable = false)
    private Long groupBuyId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "is_host", nullable = false)
    private boolean host;

    @Column(nullable = false)
    private int units;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ParticipationStatus status;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    /** 공구 개설 시 개설자 몫: 자리를 잡고 결제 대기. */
    public static Participation hostPending(Long groupBuyId, Long hostId, int units, long amount,
                                            LocalDateTime expiresAt) {
        Participation p = new Participation();
        p.groupBuyId = groupBuyId;
        p.userId = hostId;
        p.host = true;
        p.units = units;
        p.amount = amount;
        p.status = ParticipationStatus.PENDING_PAYMENT;
        p.expiresAt = expiresAt;
        return p;
    }
}
