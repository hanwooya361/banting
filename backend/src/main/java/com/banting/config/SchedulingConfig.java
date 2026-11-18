package com.banting.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄 작업. 지금은 데모 시드 갱신(T09.5, 매일 04:30)만 있고, T14에서 마감·만료 처리가 붙는다.
 * 서버 1대 기준. 여러 대가 되면 ShedLock 도입 (tech-design 4장 "스케줄러").
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
