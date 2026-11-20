package com.banting.groupbuy;

import com.banting.participation.Participation;
import com.banting.participation.ParticipationRepository;
import com.banting.payment.PaymentRepository;
import com.banting.payment.PaymentStatus;
import com.banting.payment.RefundService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 시간이 지나면 일어나는 상태 변경 (T14, tech-design 4장 "스케줄러"). {@link GroupBuyScheduler}가 주기적으로 부른다.
 *
 * <p>멱등: 대상은 ID만 조회하고, 한 건씩 짧은 트랜잭션에서 행을 잠근 뒤(공구 → 참여, T13과 같은 순서) 상태를 다시 확인한다.
 * 중간에 서버가 꺼지거나 한 건이 실패해도 다음 실행 때 남은 건만 처리한다. 토스 환불은 트랜잭션 밖({@link RefundService}).
 *
 * <p>여유 시간 {@link #GRACE}: 결제 승인은 "만료·마감 전" 확인을 통과한 뒤 토스 응답까지 최대 33초(연결 3초 + 응답 30초)가 걸린다.
 * 그 사이에 끼어들면 승인 직후 바로 보상 취소가 일어나고 결제 기록이 비므로, 만료·마감 후 2분이 지난 건만 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupBuyLifecycleService {

    public static final Duration GRACE = Duration.ofMinutes(2);
    private static final Pageable BATCH = PageRequest.ofSize(100);   // 한 번에 이만큼, 남으면 다음 실행에서

    private final GroupBuyRepository groupBuyRepository;
    private final ParticipationRepository participationRepository;
    private final PaymentRepository paymentRepository;
    private final GroupBuyService groupBuyService;
    private final RefundService refundService;
    private final TransactionTemplate tx;
    private final Clock clock;

    /** ① 결제 대기 만료: 참여자 PENDING_PAYMENT → CANCELED, 자리 반환, 결제창 주문 FAILED. @return 처리 건수 */
    public int expirePendingPayments() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(GRACE);
        return forEach(participationRepository.findExpiredPendingIds(cutoff, BATCH), "결제 대기 만료", id -> {
            // 공구 ID는 트랜잭션 밖에서 읽는다. 안에서 일반 조회로 먼저 읽으면 뒤의 잠금 조회가 캐시된 옛 값을 돌려준다
            Long groupBuyId = participationRepository.findById(id).orElseThrow().getGroupBuyId();
            return Boolean.TRUE.equals(tx.execute(status -> expirePending(groupBuyId, id, cutoff)));
        });
    }

    private boolean expirePending(Long groupBuyId, Long participationId, LocalDateTime cutoff) {
        GroupBuy groupBuy = groupBuyRepository.findByIdForUpdate(groupBuyId).orElseThrow();
        Participation participation = participationRepository.findByIdForUpdate(participationId).orElseThrow();
        if (!participation.isPending() || !participation.isExpired(cutoff)) {
            return false;   // 그 사이 결제했거나, 다시 참여해 대기 시간이 늘었음
        }
        participation.cancelPending();
        if (groupBuy.isOpen()) {
            groupBuy.release(participation.getUnits(), false);
        }
        failReadyPayments(participationId, "결제 대기 만료");
        return true;
    }

    /** ② 개설 대기 만료: 개설자가 결제하지 않은 DRAFT 공구 삭제 + 사진 정리. @return 삭제 건수 */
    public int expireDrafts() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(GRACE);
        return forEach(groupBuyRepository.findExpiredDraftIds(cutoff, BATCH), "개설 대기 만료", id -> {
            List<String> imageKeys = tx.execute(status -> expireDraft(id, cutoff));
            if (imageKeys == null) {
                return false;
            }
            groupBuyService.deleteQuietly(imageKeys);   // 파일은 DB 커밋 후에
            return true;
        });
    }

    private List<String> expireDraft(Long groupBuyId, LocalDateTime cutoff) {
        GroupBuy groupBuy = groupBuyRepository.findByIdForUpdate(groupBuyId).orElse(null);
        if (groupBuy == null || !groupBuy.isDraft()) {
            return null;   // 그 사이 개설자가 결제(OPEN)했거나 직접 삭제함
        }
        Participation host = participationRepository
                .findByGroupBuyIdAndUserIdForUpdate(groupBuyId, groupBuy.getHostId()).orElseThrow();
        if (!host.isPending() || !host.isExpired(cutoff)) {
            return null;   // 결제를 다시 시작해 대기 시간이 늘었음
        }
        return groupBuyService.deleteDraftRows(groupBuy);
    }

    /** ③ 마감 미달: OPEN → FAILED(DEADLINE_UNMET), 결제한 사람 전원 환불. @return 실패 처리한 공구 수 */
    public int failOverdueGroupBuys() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(GRACE);
        return forEach(groupBuyRepository.findOverdueOpenIds(cutoff, BATCH), "마감 미달 실패", id -> {
            List<Long> toRefund = tx.execute(status -> failOverdue(id, cutoff));
            if (toRefund == null) {
                return false;
            }
            // 한 건씩 토스 취소. 실패한 건은 REFUND_PENDING으로 남아 ④ 재시도가 이어서 처리
            toRefund.forEach(participationId -> refundService.refund(participationId, "마감 미달 공구 실패"));
            return true;
        });
    }

    private List<Long> failOverdue(Long groupBuyId, LocalDateTime cutoff) {
        GroupBuy groupBuy = groupBuyRepository.findByIdForUpdate(groupBuyId).orElseThrow();
        if (!groupBuy.isOpen() || !groupBuy.getDeadlineAt().isBefore(cutoff)) {
            return null;   // 그 사이 확정됐거나 개설자가 취소함
        }
        groupBuy.failByDeadline(cutoff);
        List<Long> toRefund = new ArrayList<>();
        // 공구 행을 잠갔으므로 이 공구의 참여 요청·결제 반영·취소는 모두 뒤에서 기다린다 (참여 행을 따로 잠글 필요 없음)
        for (Participation participation : participationRepository.findByGroupBuyId(groupBuyId)) {
            switch (participation.getStatus()) {
                case PAID -> {
                    participation.requestRefund();
                    toRefund.add(participation.getId());
                }
                case PENDING_PAYMENT -> {
                    participation.cancelPending();
                    failReadyPayments(participation.getId(), "마감 미달 공구 실패");
                }
                default -> {
                }
            }
        }
        return toRefund;
    }

    /** ④ 남은 환불 재시도 (마감 실패·참여 취소·개설자 취소에서 토스 취소가 실패한 건). @return 환불 완료 건수 */
    public int retryRefunds() {
        return forEach(participationRepository.findRefundPendingIds(BATCH), "환불 재시도",
                id -> refundService.refund(id, "환불 재시도"));
    }

    private void failReadyPayments(Long participationId, String reason) {
        paymentRepository.findByParticipationIdAndStatus(participationId, PaymentStatus.READY)
                .forEach(payment -> payment.fail(null, reason));
    }

    /** 한 건이 예외로 실패해도 나머지는 계속 처리한다 (다음 실행에서 다시 시도). */
    private static int forEach(List<Long> ids, String job, Predicate<Long> handler) {
        int done = 0;
        for (Long id : ids) {
            try {
                if (handler.test(id)) {
                    done++;
                }
            } catch (RuntimeException e) {
                log.error("{} 처리 실패 id={}", job, id, e);
            }
        }
        if (done > 0) {
            log.info("{}: {}건", job, done);
        }
        return done;
    }
}
