package com.banting.support;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 통합 테스트 사이에 모든 테이블을 비운다 (flyway_schema_history 제외).
 * 테스트 클래스끼리 같은 MySQL 컨테이너를 공유하므로, 테이블이 늘어날 때마다 FK 순서를 맞춰 DELETE하는 대신
 * FK 검사를 잠시 끄고 TRUNCATE한다.
 */
public final class DatabaseCleaner {

    private DatabaseCleaner() {
    }

    public static void clean(JdbcTemplate jdbc) {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' "
                        + "AND table_name <> 'flyway_schema_history'",
                String.class);
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            tables.forEach(table -> jdbc.execute("TRUNCATE TABLE `" + table + "`"));
        } finally {
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }
}
