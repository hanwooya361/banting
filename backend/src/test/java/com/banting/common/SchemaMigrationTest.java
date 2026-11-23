package com.banting.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banting.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Flyway V1이 실제 MySQL 8.4에 적용되고, 설계(tech-design 2장)의 핵심 제약이 DB에 걸려 있는지 확인한다.
 * 각 테스트는 @Transactional로 끝나면 롤백된다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class SchemaMigrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 마이그레이션이_모두_성공하고_테이블이_생긴다() {
        Boolean success = jdbc.queryForObject(
                "SELECT MIN(success) FROM flyway_schema_history", Boolean.class);
        assertThat(success).isTrue();

        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'",
                String.class);
        assertThat(tables).containsExactlyInAnyOrder(
                "users", "group_buys", "group_buy_images", "participations", "payments",
                "pickup_options", "pickup_votes", "settlements", "inquiries", "notices",
                "refresh_tokens", "notifications");
    }

    @Test
    void 공구_좌표에_공간_인덱스가_있다() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'group_buys' "
                        + "AND column_name = 'location' AND index_type = 'SPATIAL'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void SRID_4326의_축_순서는_위도_경도다() {
        // T07 반경 검색에서 좌표를 뒤집어 넣는 실수를 막기 위한 확인용 테스트
        Double latitude = jdbc.queryForObject(
                "SELECT ST_Latitude(ST_PointFromText('POINT(37.5665 126.9780)', 4326))", Double.class);
        assertThat(latitude).isEqualTo(37.5665);
    }

    @Test
    void 정원을_넘는_reserved_units는_DB가_거부한다() {
        long hostId = insertUser("host@banting.test", "host");

        // capacity 4인데 reserved 5 → CHECK 제약 위반 (정원 초과의 마지막 방어선)
        // 주의: MySQL CHECK 위반(오류 3819)은 Spring이 DataIntegrityViolationException으로 번역하지 않고
        // UncategorizedSQLException으로 던진다. T13에서 이 예외를 잡을 때 참고
        assertThatThrownBy(() -> insertGroupBuy(hostId, 4, 5, 0))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_group_buys_units");
        // paid가 reserved보다 많아도 거부
        assertThatThrownBy(() -> insertGroupBuy(hostId, 4, 2, 3))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_group_buys_units");
        // 정원 이내는 통과
        insertGroupBuy(hostId, 4, 4, 4);
    }

    @Test
    void 같은_공구에_같은_사용자는_한_번만_참여한다() {
        long hostId = insertUser("host@banting.test", "host");
        long groupBuyId = insertGroupBuy(hostId, 4, 0, 0);

        insertParticipation(groupBuyId, hostId);
        assertThatThrownBy(() -> insertParticipation(groupBuyId, hostId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void demo_type은_NULL_SEED_TRIAL만_허용한다() {
        long groupBuyId = insertGroupBuy(insertUser("demo@banting.test", "demo"), 4, 0, 0);

        jdbc.update("UPDATE group_buys SET demo_type = 'SEED' WHERE id = ?", groupBuyId);
        jdbc.update("UPDATE group_buys SET demo_type = 'TRIAL', trial_user_id = host_id WHERE id = ?", groupBuyId);   // 체험은 방문자 필수 (V5)
        jdbc.update("UPDATE group_buys SET demo_type = NULL, trial_user_id = NULL WHERE id = ?", groupBuyId);
        assertThatThrownBy(() -> jdbc.update("UPDATE group_buys SET demo_type = 'FAKE' WHERE id = ?", groupBuyId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_group_buys_demo_type");
    }

    private long insertUser(String email, String nickname) {
        jdbc.update("INSERT INTO users (email, password_hash, nickname, role, created_at, updated_at) "
                + "VALUES (?, 'hash', ?, 'USER', NOW(6), NOW(6))", email, nickname);
        return lastId();
    }

    private long insertGroupBuy(long hostId, int capacity, int reserved, int paid) {
        jdbc.update("INSERT INTO group_buys (host_id, title, description, category, product_url, price_type, "
                        + "total_price, total_quantity, unit_label, capacity_units, reserved_units, paid_units, "
                        + "status, location, neighborhood_name, deadline_at, created_at, updated_at) "
                        + "VALUES (?, '휴지 30롤', '반띵해요', 'LIVING', 'https://example.com/p/1', 'EQUAL', "
                        + "20000, 30, '롤', ?, ?, ?, 'OPEN', ST_PointFromText('POINT(37.5665 126.9780)', 4326), "
                        + "'중구 태평로', NOW(6) + INTERVAL 1 DAY, NOW(6), NOW(6))",
                hostId, capacity, reserved, paid);
        return lastId();
    }

    private void insertParticipation(long groupBuyId, long userId) {
        jdbc.update("INSERT INTO participations (group_buy_id, user_id, is_host, units, amount, status, "
                        + "expires_at, created_at, updated_at) "
                        + "VALUES (?, ?, TRUE, 1, 5000, 'PENDING_PAYMENT', NOW(6) + INTERVAL 10 MINUTE, NOW(6), NOW(6))",
                groupBuyId, userId);
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
