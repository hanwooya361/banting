package com.banting.settlement;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정산 기록 (T16). 실제 송금은 하지 않고 PENDING 기록까지만 (PRD).
 * 공구 완료(COMPLETED)와 같은 트랜잭션에서만 만든다 (MANDATORY) → 완료됐는데 정산 기록이 없는 상태가 생기지 않는다.
 */
@Service
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final FeePolicy feePolicy;

    public SettlementService(SettlementRepository settlementRepository,
                             @Value("${banting.settlement.fee-bps:300}") int feeBps) {
        this.settlementRepository = settlementRepository;
        this.feePolicy = new FeePolicy(feeBps);
    }

    /** @param grossAmount 결제가 유지된 참여(PAID·RECEIVED) 금액의 합 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Settlement record(Long groupBuyId, Long hostId, long grossAmount) {
        FeePolicy.Fee fee = feePolicy.calculate(grossAmount);
        return settlementRepository.save(Settlement.pending(groupBuyId, hostId, feePolicy, fee));
    }

    @Transactional(readOnly = true)
    public Optional<Settlement> find(Long groupBuyId) {
        return settlementRepository.findByGroupBuyId(groupBuyId);
    }
}
