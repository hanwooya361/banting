package com.banting.pickup;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import com.banting.groupbuy.GroupBuy;
import com.banting.groupbuy.GroupBuyRepository;
import com.banting.participation.Participation;
import com.banting.participation.ParticipationRepository;
import com.banting.participation.ParticipationStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수령 후보 투표와 확정 시 1위 선택 (T15, tech-design 4장 "수령 투표").
 *
 * <p>투표는 공구 행을 잠그고 한다. 결제 승인(확정)도 같은 행을 잠그므로, 확정되는 순간 들어온 표가
 * 1위 계산에 들어갔는지 애매해지지 않는다 (확정 전에 들어왔으면 반영, 후면 "투표가 끝났어요").
 */
@Service
@RequiredArgsConstructor
public class PickupService {

    private final GroupBuyRepository groupBuyRepository;
    private final ParticipationRepository participationRepository;
    private final PickupOptionRepository pickupOptionRepository;
    private final PickupVoteRepository pickupVoteRepository;
    private final Clock clock;

    /** 후보별 득표와 내 표. */
    public record VoteSummary(Map<Long, Long> votesByOption, Long myOptionId) {
    }

    /** 투표(또는 표 바꾸기). 결제를 마친 참여자·개설자만, 모집 중·마감 전에만. */
    @Transactional
    public VoteSummary vote(Long userId, Long groupBuyId, Long optionId) {
        GroupBuy groupBuy = groupBuyRepository.findByIdForUpdate(groupBuyId)
                .filter(g -> !g.isDraft() || g.getHostId().equals(userId))   // 결제 전 공구는 남에게 없는 공구
                .orElseThrow(() -> new BusinessException(ErrorCode.GROUP_BUY_NOT_FOUND));
        if (groupBuy.isDemo()) {
            throw new BusinessException(ErrorCode.DEMO_GROUP_BUY);
        }
        if (!groupBuy.isOpen() || !groupBuy.getDeadlineAt().isAfter(LocalDateTime.now(clock))) {
            throw new BusinessException(ErrorCode.PICKUP_VOTE_CLOSED);
        }
        boolean paid = participationRepository.findByGroupBuyIdAndUserId(groupBuyId, userId)
                .map(p -> p.getStatus() == ParticipationStatus.PAID)
                .orElse(false);
        if (!paid) {
            throw new BusinessException(ErrorCode.PICKUP_VOTE_NOT_ALLOWED);
        }
        PickupOption option = pickupOptionRepository.findById(optionId)
                .filter(o -> o.getGroupBuyId().equals(groupBuyId))   // 다른 공구의 후보
                .orElseThrow(() -> new BusinessException(ErrorCode.PICKUP_OPTION_NOT_FOUND));

        pickupVoteRepository.findByGroupBuyIdAndUserId(groupBuyId, userId).ifPresentOrElse(
                vote -> vote.changeTo(option.getId()),
                () -> pickupVoteRepository.save(PickupVote.of(groupBuyId, option.getId(), userId)));
        pickupVoteRepository.flush();   // 아래 집계 쿼리가 방금 바꾼 표를 보게
        return summary(groupBuyId, userId);
    }

    @Transactional(readOnly = true)
    public VoteSummary summary(Long groupBuyId, Long viewerId) {
        Map<Long, Long> votes = pickupVoteRepository.countValidVotes(groupBuyId).stream()
                .collect(Collectors.toMap(PickupVoteRepository.OptionVotes::getOptionId,
                        PickupVoteRepository.OptionVotes::getVotes));
        Long mine = viewerId == null ? null : pickupVoteRepository.findByGroupBuyIdAndUserId(groupBuyId, viewerId)
                .map(PickupVote::getPickupOptionId).orElse(null);
        return new VoteSummary(votes, mine);
    }

    /**
     * 공구가 확정되는 순간 1위 후보를 수령 일정으로 고른다. 결제 승인 트랜잭션 안(공구 행 잠금 상태)에서만 부른다.
     * 1위: 득표 많은 순 → 수령 시각이 이른 순 → 먼저 등록한 순. 아무도 투표하지 않았으면 가장 이른 후보.
     */
    public Optional<PickupOption> selectWinner(Long groupBuyId) {
        Map<Long, Long> votes = summary(groupBuyId, null).votesByOption();
        Optional<PickupOption> winner = pickupOptionRepository.findByGroupBuyIdOrderByPickupAtAsc(groupBuyId).stream()
                .min(winnerOrder(votes));
        winner.ifPresent(PickupOption::select);
        return winner;
    }

    static Comparator<PickupOption> winnerOrder(Map<Long, Long> votes) {
        return Comparator.<PickupOption>comparingLong(o -> -votes.getOrDefault(o.getId(), 0L))
                .thenComparing(PickupOption::getPickupAt)
                .thenComparing(PickupOption::getId);
    }

    /** 결제 완료한 참여자(개설자 포함) ID. 확정 알림 대상. */
    public List<Long> paidUserIds(Long groupBuyId) {
        return participationRepository.findByGroupBuyIdAndStatus(groupBuyId, ParticipationStatus.PAID).stream()
                .map(Participation::getUserId).toList();
    }
}
