package com.banting.groupbuy;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 스케줄 실행만 맡는다. 로직은 {@link GroupBuyLifecycleService} (테스트는 그쪽을 직접 부른다).
 *
 * <p>{@code banting.scheduler.enabled=true}일 때만 등록된다 (local·prod). 테스트에서 몰래 돌아 다른 테스트의 데이터를 바꾸지 않게 기본은 꺼짐.
 * 스프링 스케줄러는 기본 스레드 하나라 작업끼리 겹치지 않고, fixedDelay라 앞 실행이 끝난 뒤 간격을 센다.
 * 서버 1대 기준. 여러 대가 되면 ShedLock으로 한 대만 돌게 한다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "banting.scheduler.enabled", havingValue = "true")
public class GroupBuyScheduler {

    private final GroupBuyLifecycleService lifecycle;

    /** 1분마다: ① 결제 대기 만료 ② 개설 대기 만료 ③ 마감 미달 실패 + 환불 ⑥ 수령 기한 경과 완료 + 정산. */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    public void everyMinute() {
        lifecycle.expirePendingPayments();
        lifecycle.expireDrafts();
        lifecycle.failOverdueGroupBuys();
        lifecycle.completeOverduePickups();
    }

    /** 10분마다: ④ 남은 환불 재시도. 토스가 계속 실패할 때 1분마다 두드리지 않게 주기를 나눴다. */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT2M")
    public void retryRefunds() {
        lifecycle.retryRefunds();
    }
}
