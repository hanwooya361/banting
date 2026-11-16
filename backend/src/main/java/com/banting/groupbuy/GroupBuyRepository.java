package com.banting.groupbuy;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
}
