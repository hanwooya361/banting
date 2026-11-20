package com.banting.groupbuy;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupBuyImageRepository extends JpaRepository<GroupBuyImage, Long> {

    List<GroupBuyImage> findByGroupBuyIdOrderBySortOrderAsc(Long groupBuyId);

    void deleteByGroupBuyId(Long groupBuyId);

    /** 여러 공구의 대표 사진(첫 번째)을 한 번에 (내 공구 화면, N+1 방지). */
    @org.springframework.data.jpa.repository.Query("""
            SELECT i FROM GroupBuyImage i
            WHERE i.groupBuyId IN :groupBuyIds
              AND i.sortOrder = (SELECT MIN(i2.sortOrder) FROM GroupBuyImage i2 WHERE i2.groupBuyId = i.groupBuyId)
            """)
    List<GroupBuyImage> findThumbnails(
            @org.springframework.data.repository.query.Param("groupBuyIds") java.util.Collection<Long> groupBuyIds);
}
