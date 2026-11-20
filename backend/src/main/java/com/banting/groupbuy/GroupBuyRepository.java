package com.banting.groupbuy;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
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

    // ───── 스케줄러 대상 (T14). ID만 가져오고, 처리는 한 건씩 잠근 뒤 상태를 다시 확인한다. 시드 공구 제외 ─────

    /** 마감이 cutoff 이전인 모집 중 공구. 인덱스 (status, deadline_at). */
    @Query("""
            SELECT g.id FROM GroupBuy g
            WHERE g.status = com.banting.groupbuy.GroupBuyStatus.OPEN AND g.deadlineAt < :cutoff AND g.demoType IS NULL
            ORDER BY g.deadlineAt
            """)
    List<Long> findOverdueOpenIds(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /** 개설자 결제 대기가 cutoff 전에 만료된 개설 대기 공구. */
    @Query("""
            SELECT g.id FROM GroupBuy g, com.banting.participation.Participation p
            WHERE p.groupBuyId = g.id AND p.host = TRUE
              AND g.status = com.banting.groupbuy.GroupBuyStatus.DRAFT AND g.demoType IS NULL
              AND p.status = com.banting.participation.ParticipationStatus.PENDING_PAYMENT AND p.expiresAt < :cutoff
            ORDER BY g.id
            """)
    List<Long> findExpiredDraftIds(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);
}
