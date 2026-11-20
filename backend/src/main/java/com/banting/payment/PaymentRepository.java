package com.banting.payment;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(String orderId);

    /** 같은 주문의 승인 반영이 동시에 두 번 일어나지 않게 결제 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.orderId = :orderId")
    Optional<Payment> findByOrderIdForUpdate(@Param("orderId") String orderId);

    List<Payment> findByParticipationIdAndStatus(Long participationId, PaymentStatus status);

    /** 돈이 오간 기록(승인·취소)이 있는지. 있으면 개설 대기 공구를 지우지 않는다 (T14). */
    boolean existsByParticipationIdAndStatusIn(Long participationId, java.util.Collection<PaymentStatus> statuses);

    void deleteByParticipationId(Long participationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.participationId = :participationId AND p.status = :status")
    List<Payment> findByParticipationIdAndStatusForUpdate(@Param("participationId") Long participationId,
                                                          @Param("status") PaymentStatus status);
}
