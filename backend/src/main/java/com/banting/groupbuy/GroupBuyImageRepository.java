package com.banting.groupbuy;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupBuyImageRepository extends JpaRepository<GroupBuyImage, Long> {

    List<GroupBuyImage> findByGroupBuyIdOrderBySortOrderAsc(Long groupBuyId);

    void deleteByGroupBuyId(Long groupBuyId);
}
