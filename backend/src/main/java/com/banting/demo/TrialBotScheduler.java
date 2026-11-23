package com.banting.demo;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 체험 봇 실행 주기만 맡는다 (T16.5). 로직은 {@link TrialBotService} (테스트는 그쪽을 직접 부른다).
 *
 * <p>4초마다 체험마다 한 걸음: 방문자가 결제하면 이웃 봇 2명 참여(약 8초) → 확정 → 구매했어요 → 봇 받았어요까지 20초 남짓.
 * 너무 빠르면 화면에서 흐름이 안 보이고, 너무 느리면 기다리다 나간다. 다른 스케줄 작업처럼
 * {@code banting.scheduler.enabled=true}일 때만 등록된다 (테스트에서는 꺼짐).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "banting.scheduler.enabled", havingValue = "true")
public class TrialBotScheduler {

    private final TrialBotService trialBotService;

    @Scheduled(fixedDelayString = "PT4S", initialDelayString = "PT20S")
    public void tick() {
        trialBotService.advanceAll();
    }
}
