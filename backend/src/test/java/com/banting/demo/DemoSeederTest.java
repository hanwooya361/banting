package com.banting.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banting.TestcontainersConfiguration;
import com.banting.support.DatabaseCleaner;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** 체험 동네 시드 (T09.5 완료 조건: demo로 구분, 마감일 자동 갱신). */
@SpringBootTest(properties = "banting.demo.seed=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DemoSeederTest {

    @Autowired
    DemoSeeder seeder;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);   // 기동 시 시더가 넣은 데이터도 지우고 테스트마다 처음부터
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    void 서울시청_1km_안에_모집중인_시드_공구_8개를_만든다() {
        assertThat(seeder.seedOrRefresh()).containsExactly(8, 0);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT status, demo_type, deadline_at, reserved_units, paid_units, capacity_units,
                       ST_Distance_Sphere(location, ST_PointFromText('POINT(37.5665 126.978)', 4326)) AS distance
                FROM group_buys
                """);
        assertThat(rows).hasSize(8).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("OPEN");
            assertThat(row.get("demo_type")).isEqualTo("SEED");
            assertThat(((Number) row.get("distance")).doubleValue()).isLessThan(1000);
            assertThat((LocalDateTime) row.get("deadline_at")).isAfter(now().plusHours(12));
            assertThat((Integer) row.get("reserved_units")).isEqualTo(row.get("paid_units"))
                    .isLessThan((Integer) row.get("capacity_units"));   // 아직 모집 중이어야 함
        });
        assertThat(jdbc.queryForList("SELECT DISTINCT category FROM group_buys", String.class))
                .containsExactlyInAnyOrder("LIVING", "FOOD", "KITCHEN", "ETC");
        assertThat(count("SELECT COUNT(*) FROM group_buy_images WHERE url LIKE 'demo/%.webp'")).isEqualTo(8);
    }

    @Test
    void 공구의_자리_수는_참여_행의_수량_합과_같고_개설자는_한_명() {
        seeder.seedOrRefresh();

        assertThat(count("""
                SELECT COUNT(*) FROM group_buys g
                WHERE g.paid_units <> (SELECT SUM(p.units) FROM participations p
                                       WHERE p.group_buy_id = g.id AND p.status = 'PAID')
                   OR (SELECT COUNT(*) FROM participations p WHERE p.group_buy_id = g.id AND p.is_host) <> 1
                """)).isZero();
        // 돈이 오간 적이 없으므로 결제 기록은 만들지 않는다
        assertThat(count("SELECT COUNT(*) FROM payments")).isZero();
    }

    @Test
    void 다시_돌려도_중복으로_만들지_않는다() {
        seeder.seedOrRefresh();
        List<Long> ids = jdbc.queryForList("SELECT id FROM group_buys ORDER BY id", Long.class);

        assertThat(seeder.seedOrRefresh()).containsExactly(0, 8);

        assertThat(jdbc.queryForList("SELECT id FROM group_buys ORDER BY id", Long.class)).isEqualTo(ids);
        assertThat(count("SELECT COUNT(*) FROM users")).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM participations")).isEqualTo(15);   // 개설자 8 + 이웃 7
    }

    @Test
    void 마감이_지나도_다음_실행에서_다시_미래로_옮기고_수령_일정도_마감_뒤로() {
        seeder.seedOrRefresh();
        // 서버를 며칠 꺼 둔 상황: 마감과 수령 일정이 모두 과거
        jdbc.update("UPDATE group_buys SET deadline_at = ?", now().minusDays(3));
        jdbc.update("UPDATE pickup_options SET pickup_at = ?", now().minusDays(2));

        seeder.seedOrRefresh();

        assertThat(count("SELECT COUNT(*) FROM group_buys WHERE deadline_at <= ?", now().plusHours(12))).isZero();
        assertThat(count("""
                SELECT COUNT(*) FROM pickup_options o JOIN group_buys g ON g.id = o.group_buy_id
                WHERE o.pickup_at <= g.deadline_at OR o.pickup_at > g.deadline_at + INTERVAL 14 DAY
                """)).isZero();
    }

    @Test
    void 데모_회원으로는_로그인할_수_없다() throws Exception {
        seeder.seedOrRefresh();

        for (String password : List.of("", "password123", DemoSeeder.NO_LOGIN_PASSWORD_HASH)) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(DemoSeeder.HOST_EMAIL, password)))
                    .andExpect(status().is4xxClientError());
        }
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }
}
