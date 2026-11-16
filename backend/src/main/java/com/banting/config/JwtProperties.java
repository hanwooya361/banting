package com.banting.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 설정 (application.yml의 banting.jwt).
 * secret은 HS256 서명 키. 운영은 서버 .env의 JWT_SECRET, 로컬·테스트는 개발용 고정값.
 */
@ConfigurationProperties(prefix = "banting.jwt")
public record JwtProperties(String issuer, String secret, Duration accessTtl, Duration refreshTtl) {

    public JwtProperties {
        // HS256은 키가 256비트(32바이트) 이상이어야 안전. 짧으면 앱을 띄우지 않는다
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("banting.jwt.secret은 32바이트 이상이어야 합니다");
        }
    }
}
