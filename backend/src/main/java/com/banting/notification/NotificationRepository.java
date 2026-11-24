package com.banting.notification;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * 내 알림 최신순, before(알림 id)보다 오래된 것부터 한 페이지. OFFSET 대신 id 기준이라
     * 뒤 페이지로 가도 인덱스 (user_id, id)에서 바로 시작 위치를 찾는다 (OFFSET은 앞의 행을 다 읽고 버림).
     */
    @Query("SELECT n FROM Notification n WHERE n.userId = :userId AND n.id < :before ORDER BY n.id DESC")
    List<Notification> findPage(@Param("userId") Long userId, @Param("before") Long before, Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    /** 탈퇴 (T22): 알림은 거래 기록이 아니라 개인 화면용이라 지운다. */
    @Modifying
    @Query("DELETE FROM Notification n WHERE n.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = :now, n.updatedAt = :now WHERE n.userId = :userId AND n.readAt IS NULL")
    int readAll(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM Notification n WHERE n.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") LocalDateTime cutoff);
}
