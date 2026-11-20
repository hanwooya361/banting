package com.banting.pickup;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PickupOptionRepository extends JpaRepository<PickupOption, Long> {

    List<PickupOption> findByGroupBuyIdOrderByPickupAtAsc(Long groupBuyId);

    void deleteByGroupBuyId(Long groupBuyId);

    /** 확정 시 고른 수령 일정 (T15). */
    java.util.Optional<PickupOption> findByGroupBuyIdAndSelectedTrue(Long groupBuyId);

    /** 여러 공구의 정해진 수령 일정을 한 번에 (내 공구 화면). */
    List<PickupOption> findByGroupBuyIdInAndSelectedTrue(java.util.Collection<Long> groupBuyIds);
}
