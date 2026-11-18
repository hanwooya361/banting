package com.banting.participation;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
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

    java.util.List<Participation> findByGroupBuyIdAndStatus(Long groupBuyId, ParticipationStatus status);

    java.util.List<Participation> findByGroupBuyId(Long groupBuyId);

    /** 같은 사람의 참여 요청·결제 승인이 동시에 같은 행을 바꾸지 않게 잠근다 (늦게 온 쪽이 PAID를 덮어쓰는 일 방지). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Participation p WHERE p.groupBuyId = :groupBuyId AND p.userId = :userId")
    Optional<Participation> findByGroupBuyIdAndUserIdForUpdate(@Param("groupBuyId") Long groupBuyId,
                                                               @Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Participation p WHERE p.id = :id")
    Optional<Participation> findByIdForUpdate(@Param("id") Long id);
}
