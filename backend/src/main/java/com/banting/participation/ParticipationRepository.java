package com.banting.participation;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParticipationRepository extends JpaRepository<Participation, Long> {

    /** 개설자를 뺀, 자리를 차지하고 있는(결제 대기·결제 완료) 참여자 수. 공구 수정·삭제 가능 여부 판단용. */
    long countByGroupBuyIdAndHostFalseAndStatusIn(Long groupBuyId, Collection<ParticipationStatus> statuses);

    Optional<Participation> findByGroupBuyIdAndHostTrue(Long groupBuyId);

    void deleteByGroupBuyId(Long groupBuyId);

    Optional<Participation> findByGroupBuyIdAndUserId(Long groupBuyId, Long userId);

    List<Participation> findByGroupBuyIdAndStatus(Long groupBuyId, ParticipationStatus status);

    List<Participation> findByGroupBuyId(Long groupBuyId);

    /** 같은 사람의 참여 요청·결제 승인이 동시에 같은 행을 바꾸지 않게 잠근다 (늦게 온 쪽이 PAID를 덮어쓰는 일 방지). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Participation p WHERE p.groupBuyId = :groupBuyId AND p.userId = :userId")
    Optional<Participation> findByGroupBuyIdAndUserIdForUpdate(@Param("groupBuyId") Long groupBuyId,
                                                               @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Participation p WHERE p.id = :id")
    Optional<Participation> findByIdForUpdate(@Param("id") Long id);

    /**
     * 내 공구 화면(S07): 내 참여 행과 공구를 한 번에 (최근 참여 순). 결제 대기 만료·결제 전 취소(CANCELED)와 시드 공구는 뺀다.
     * 반환 원소는 [Participation, GroupBuy].
     */
    @Query("""
            SELECT p, g FROM Participation p, com.banting.groupbuy.GroupBuy g
            WHERE g.id = p.groupBuyId AND p.userId = :userId AND g.demoType IS NULL
              AND p.status <> com.banting.participation.ParticipationStatus.CANCELED
            ORDER BY p.id DESC
            """)
    List<Object[]> findMineWithGroupBuy(@Param("userId") Long userId, Pageable pageable);

    // ───── 스케줄러 대상 (T14). 시드 공구 제외 ─────

    /** 결제 대기가 cutoff 전에 만료된 참여자(개설자 제외: 개설자 대기는 DRAFT 만료가 공구째 정리). 인덱스 (status, expires_at). */
    @Query("""
            SELECT p.id FROM Participation p, com.banting.groupbuy.GroupBuy g
            WHERE g.id = p.groupBuyId AND g.demoType IS NULL AND p.host = FALSE
              AND p.status = com.banting.participation.ParticipationStatus.PENDING_PAYMENT AND p.expiresAt < :cutoff
            ORDER BY p.id
            """)
    List<Long> findExpiredPendingIds(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /** 토스 취소가 실패해 남은 환불 대기. */
    @Query("""
            SELECT p.id FROM Participation p
            WHERE p.status = com.banting.participation.ParticipationStatus.REFUND_PENDING
            ORDER BY p.id
            """)
    List<Long> findRefundPendingIds(Pageable pageable);
}
