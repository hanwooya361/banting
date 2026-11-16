package com.banting.config;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * 시간 기준을 한 곳에 모은다.
 * - 국내 전용 서비스라 Asia/Seoul. 서버·CI의 JVM 시간대(UTC 등)와 상관없이 같은 값이 나오게 Clock을 주입해서 쓴다
 * - 테스트에서는 고정 Clock으로 바꿔 만료 시각 등을 검증할 수 있다
 * - JPA Auditing(created_at, updated_at)도 같은 Clock을 쓴다
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class TimeConfig {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(ZONE);
    }

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(LocalDateTime.now(clock));
    }
}
