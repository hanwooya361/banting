package com.banting.settlement;

import com.banting.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 개설자 정산 기록 (T16). 공구당 1건 (UNIQUE group_buy_id → 중복 정산 방지).
 * DB CHECK: gross_amount = fee_amount + net_amount.
 */
@Entity
@Table(name = "settlements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_buy_id", nullable = false)
    private Long groupBuyId;

    @Column(name = "host_id", nullable = false)
    private Long hostId;

    @Column(name = "gross_amount", nullable = false)
    private long grossAmount;

    @Column(name = "fee_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal feeRate;

    @Column(name = "fee_amount", nullable = false)
    private long feeAmount;

    @Column(name = "net_amount", nullable = false)
    private long netAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SettlementStatus status;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    public static Settlement pending(Long groupBuyId, Long hostId, FeePolicy policy, FeePolicy.Fee fee) {
        Settlement s = new Settlement();
        s.groupBuyId = groupBuyId;
        s.hostId = hostId;
        s.grossAmount = fee.gross();
        s.feeRate = policy.rate();
        s.feeAmount = fee.fee();
        s.netAmount = fee.net();
        s.status = SettlementStatus.PENDING;
        return s;
    }
}
