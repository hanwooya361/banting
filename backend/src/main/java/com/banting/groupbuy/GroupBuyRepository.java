package com.banting.groupbuy;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GroupBuyRepository extends JpaRepository<GroupBuy, Long> {

    /**
     * SELECT … FOR UPDATE. 수정·삭제 시 "개설자 외 참여자 없음"을 확인하는 동안 이 행을 잠근다.
     * 참여(T13)는 같은 행을 조건부 UPDATE하므로, 확인과 수정 사이에 누가 끼어들어 참여할 수 없다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM GroupBuy g WHERE g.id = :id")
    Optional<GroupBuy> findByIdForUpdate(@Param("id") Long id);

    /**
     * 참여 자리 잡기 (tech-design 4장 "마지막 자리 동시성"). 확인과 증가를 UPDATE 한 문장으로 해서
     * 동시에 여러 명이 마지막 자리를 눌러도 한 명만 성공한다 (영향 행 0 = 자리 없음).
     * 조건을 SQL에 다 넣었으므로 읽고-확인하고-쓰는 사이에 끼어들 틈이 없다. CHECK 제약이 마지막 방어선.
     * 다른 곳에서 읽어 둔 엔티티가 옛 reserved_units로 덮어쓰지 않게 version도 올린다.
     */
    @Modifying
    @Query(value = """
            UPDATE group_buys
            SET reserved_units = reserved_units + :units, version = version + 1
            WHERE id = :id AND status = 'OPEN' AND deadline_at > :now
              AND reserved_units + :units <= capacity_units
            """, nativeQuery = true)
    int reserveUnits(@Param("id") Long id, @Param("units") int units, @Param("now") LocalDateTime now);
}
