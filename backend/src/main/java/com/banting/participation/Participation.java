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

/**
 * 공구 참여 (한 공구에 한 사람당 한 행).
 * 상태 전이 (tech-design 3장): PENDING_PAYMENT → PAID, CANCELED → PENDING_PAYMENT(재참여, 같은 행 재사용).
 * 취소·환불 (T12): PENDING_PAYMENT → CANCELED(결제 전 취소), PAID → REFUND_PENDING → REFUNDED.
 * 만료 처리는 T14.
 */
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

    /** 참여자 몫: 자리를 잡고 결제 대기. */
    public static Participation pending(Long groupBuyId, Long userId, int units, long amount, LocalDateTime expiresAt) {
        Participation p = hostPending(groupBuyId, userId, units, amount, expiresAt);
        p.host = false;
        return p;
    }

    /** 결제 대기 중 다시 결제를 시작하면(거절 후 재시도, 결제창을 닫음 등) 대기 시간을 새로 준다. 자리는 그대로. */
    public void renew(LocalDateTime expiresAt) {
        require(ParticipationStatus.PENDING_PAYMENT);
        this.expiresAt = expiresAt;
    }

    /** 만료·결제 실패로 취소된 행을 다시 결제 대기로 (UNIQUE(group_buy_id, user_id)라 새 행 대신 재사용). */
    public void rejoin(int units, long amount, LocalDateTime expiresAt) {
        require(ParticipationStatus.CANCELED);
        this.units = units;
        this.amount = amount;
        this.expiresAt = expiresAt;
        this.status = ParticipationStatus.PENDING_PAYMENT;
    }

    /** 결제 승인 반영. */
    public void pay() {
        require(ParticipationStatus.PENDING_PAYMENT);
        this.status = ParticipationStatus.PAID;
    }

    /** 결제 전 취소: 돈이 오가지 않았으니 바로 CANCELED (같은 공구에 다시 참여 가능). */
    public void cancelPending() {
        require(ParticipationStatus.PENDING_PAYMENT);
        this.status = ParticipationStatus.CANCELED;
    }

    /** 결제 후 취소 시작: 자리는 호출 쪽이 바로 반환하고, 토스 취소가 끝날 때까지 환불 대기. */
    public void requestRefund() {
        require(ParticipationStatus.PAID);
        this.status = ParticipationStatus.REFUND_PENDING;
    }

    public void refunded() {
        require(ParticipationStatus.REFUND_PENDING);
        this.status = ParticipationStatus.REFUNDED;
    }

    /** 참여자 "받았어요" (T16). */
    public void receive(LocalDateTime now) {
        require(ParticipationStatus.PAID);
        this.status = ParticipationStatus.RECEIVED;
        this.receivedAt = now;
    }

    public boolean isRefundPending() {
        return status == ParticipationStatus.REFUND_PENDING;
    }

    public boolean isPending() {
        return status == ParticipationStatus.PENDING_PAYMENT;
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    private void require(ParticipationStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("참여 상태가 " + expected + "가 아닙니다: " + status);
        }
    }
}
