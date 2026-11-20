package com.banting.user.auth;

import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료된 refresh 토큰 정리 (T14). 재발급마다 행이 하나씩 쌓이므로 하루 한 번 지운다.
 * 폐기됐어도 만료 전인 토큰은 남긴다: 그 토큰이 다시 오면 탈취로 보고 회원의 토큰을 전부 폐기해야 하기 때문 (T06).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenCleaner {

    private final RefreshTokenRepository refreshTokenRepository;
    private final Clock clock;

    @Value("${banting.scheduler.enabled:false}")
    private boolean enabled;

    @Transactional
    public int deleteExpired() {
        int deleted = refreshTokenRepository.deleteExpired(LocalDateTime.now(clock));
        if (deleted > 0) {
            log.info("만료된 refresh 토큰 삭제: {}건", deleted);
        }
        return deleted;
    }

    /** 매일 04:00. 스케줄러가 꺼져 있으면(테스트) 돌지 않는다. */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    void daily() {
        if (enabled) {
            deleteExpired();
        }
    }
}
