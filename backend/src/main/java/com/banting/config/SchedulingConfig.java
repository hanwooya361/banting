package com.banting.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄 작업: 데모 시드 갱신(T09.5, 매일 04:30), 결제 대기 만료·마감 미달 환불(T14, GroupBuyScheduler),
 * refresh 토큰 정리(T14, 매일 04:00). T14 작업은 banting.scheduler.enabled=true일 때만 돈다.
 * 서버 1대 기준. 여러 대가 되면 ShedLock 도입 (tech-design 4장 "스케줄러").
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
