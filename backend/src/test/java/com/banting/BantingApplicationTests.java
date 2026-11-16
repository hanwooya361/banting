package com.banting;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BantingApplicationTests {

    // Flyway 마이그레이션 + JPA 스키마 검증(ddl-auto=validate)까지 포함해 앱이 뜨는지 확인
    @Test
    void contextLoads() {
    }
}
