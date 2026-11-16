package com.banting;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트용 MySQL 컨테이너. 운영과 같은 8.4 버전을 쓴다.
 * {@code @ServiceConnection}이 컨테이너 주소·계정을 datasource 설정에 자동으로 넣어 준다.
 * 같은 설정을 쓰는 테스트끼리는 Spring 컨텍스트 캐시 덕분에 컨테이너 하나를 공유한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer(DockerImageName.parse("mysql:8.4"));
    }
}
