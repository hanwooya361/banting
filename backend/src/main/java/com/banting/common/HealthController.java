package com.banting.common;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서버가 살아 있는지 확인하는 엔드포인트.
 * 배포 후 헬스체크(T04)와 프론트 연결 확인에 사용한다.
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
