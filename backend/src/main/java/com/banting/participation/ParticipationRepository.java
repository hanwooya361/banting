package com.banting.participation;

import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipationRepository extends JpaRepository<Participation, Long> {

    /** 개설자를 뺀, 자리를 차지하고 있는(결제 대기·결제 완료) 참여자 수. 공구 수정·삭제 가능 여부 판단용. */
    long countByGroupBuyIdAndHostFalseAndStatusIn(Long groupBuyId, Collection<ParticipationStatus> statuses);

    Optional<Participation> findByGroupBuyIdAndHostTrue(Long groupBuyId);

    void deleteByGroupBuyId(Long groupBuyId);
}
