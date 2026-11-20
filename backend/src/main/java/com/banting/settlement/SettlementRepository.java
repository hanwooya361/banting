package com.banting.settlement;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findByGroupBuyId(Long groupBuyId);
}
