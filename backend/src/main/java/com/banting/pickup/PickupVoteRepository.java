package com.banting.pickup;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PickupVoteRepository extends JpaRepository<PickupVote, Long> {

    Optional<PickupVote> findByGroupBuyIdAndUserId(Long groupBuyId, Long userId);

    /**
     * 후보별 유효 득표. 지금 결제 완료(PAID·RECEIVED)인 사람의 표만 센다.
     * 투표 후 취소·환불한 사람의 표는 행을 지우지 않아도 여기서 빠지므로, 취소·환불 코드가 투표를 몰라도 된다.
     */
    @Query("""
            SELECT v.pickupOptionId AS optionId, COUNT(v) AS votes
            FROM PickupVote v, com.banting.participation.Participation p
            WHERE v.groupBuyId = :groupBuyId AND p.groupBuyId = v.groupBuyId AND p.userId = v.userId
              AND p.status IN (com.banting.participation.ParticipationStatus.PAID,
                               com.banting.participation.ParticipationStatus.RECEIVED)
            GROUP BY v.pickupOptionId
            """)
    List<OptionVotes> countValidVotes(@Param("groupBuyId") Long groupBuyId);

    interface OptionVotes {
        Long getOptionId();

        long getVotes();
    }
}
