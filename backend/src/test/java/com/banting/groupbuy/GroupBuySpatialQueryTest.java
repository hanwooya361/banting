package com.banting.groupbuy;

import static org.assertj.core.api.Assertions.assertThat;

import com.banting.TestcontainersConfiguration;
import com.banting.support.DatabaseCleaner;
import com.banting.common.geo.GeoPoint;
import com.banting.groupbuy.GroupBuySpatialQuery.Nearby;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 반경 검색 쿼리 검증 (T07 완료 조건). 기준점: 서울시청 (37.5665, 126.9780).
 * 위도 0.0027° ≈ 300m, 경도 1° ≈ 88.3km(37.57°N).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GroupBuySpatialQueryTest {

    private static final GeoPoint CITY_HALL = new GeoPoint(37.5665, 126.9780);

    @Autowired
    GroupBuySpatialQuery query;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    NamedParameterJdbcTemplate namedJdbc;

    private long hostId;
    private long north300m;
    private long east800m;
    private long south2500m;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        jdbc.update("INSERT INTO users (email, password_hash, nickname, role, search_radius_m, created_at, updated_at) "
                + "VALUES ('spatial@test.com', 'h', 'spatial', 'USER', 1000, NOW(6), NOW(6))");
        hostId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // 마감 순서: east800m(1일 후) → north300m(2일 후) → south2500m(3일 후)
        north300m = insertGroupBuy(37.5692, 126.9780, "OPEN", 2);
        east800m = insertGroupBuy(37.5665, 126.98706, "OPEN", 1);
        south2500m = insertGroupBuy(37.54404, 126.9780, "OPEN", 3);
        insertGroupBuy(37.6114, 126.9780, "OPEN", 1);       // 북쪽 5km: 3km 반경 밖
        insertGroupBuy(37.5670, 126.9785, "CONFIRMED", 1);  // 70m지만 모집 중이 아님
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    void 반경_1km_안의_모집중_공구만_마감임박순으로_찾는다() {
        List<Nearby> result = query.findOpenWithin(CITY_HALL, 1000, 20, 0);

        assertThat(result).extracting(Nearby::id).containsExactly(east800m, north300m);
        assertThat(result.get(0).distanceM()).isBetween(750L, 850L);
        assertThat(result.get(1).distanceM()).isBetween(250L, 350L);
    }

    @Test
    void 반경을_넓히면_더_먼_공구도_포함한다() {
        assertThat(query.findOpenWithin(CITY_HALL, 3000, 20, 0))
                .extracting(Nearby::id).containsExactly(east800m, north300m, south2500m);
        assertThat(query.findOpenWithin(CITY_HALL, 500, 20, 0))
                .extracting(Nearby::id).containsExactly(north300m);
    }

    @Test
    void 페이지_단위로_나눠_가져온다() {
        assertThat(query.findOpenWithin(CITY_HALL, 3000, 2, 0)).extracting(Nearby::id)
                .containsExactly(east800m, north300m);
        assertThat(query.findOpenWithin(CITY_HALL, 3000, 2, 2)).extracting(Nearby::id)
                .containsExactly(south2500m);
    }

    @Test
    void 사각형_모서리는_반경_밖이면_제외한다() {
        // 북동쪽 대각선 약 1.27km: 1km 사각형(±1.1km) 안이지만 실제 거리는 반경 밖
        long corner = insertGroupBuy(37.5746, 126.98823, "OPEN", 1);
        assertThat(query.findOpenWithin(CITY_HALL, 1000, 20, 0)).extracting(Nearby::id).doesNotContain(corner);
    }

    @Test
    void 공구가_많아도_공간_인덱스로_후보를_고른다() {
        // 실제처럼 전국(여기선 부산 쪽)에 공구 3,000건이 마감일도 제각각 흩어져 있고 내 근처는 소수인 상황.
        // 처음엔 1,000건을 모두 같은 마감일로 넣었더니 옵티마이저가 (status, deadline_at) 인덱스를 골랐다.
        // 실제 분포에 가깝게 넣으면 공간 인덱스를 고른다 (데이터 분포에 따라 실행 계획이 바뀐다는 점이 이 테스트의 요지)
        jdbc.update("SET SESSION cte_max_recursion_depth = 5000");
        jdbc.update("""
                INSERT INTO group_buys (host_id, title, description, category, product_url, price_type,
                    total_price, total_quantity, unit_label, capacity_units, status, location,
                    neighborhood_name, deadline_at, created_at, updated_at)
                WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 3000)
                SELECT ?, 'filler', 'd', 'ETC', 'https://example.com', 'EQUAL', 10000, 2, '개', 2, 'OPEN',
                    ST_PointFromText(CONCAT('POINT(', 35.0 + (n MOD 60) * 0.01, ' ', 129.0 + (n DIV 60) * 0.01, ')'), 4326),
                    '부산', NOW(6) + INTERVAL (n MOD 30) DAY, NOW(6), NOW(6)
                FROM seq
                """, hostId);
        jdbc.execute("ANALYZE TABLE group_buys");

        List<Map<String, Object>> plan = namedJdbc.queryForList(
                "EXPLAIN " + GroupBuySpatialQuery.NEARBY_OPEN_SQL, query.params(CITY_HALL, 1000, 20, 0));

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).get("key")).isEqualTo("sp_group_buys_location");
        // 결과도 그대로
        assertThat(query.findOpenWithin(CITY_HALL, 1000, 20, 0)).extracting(Nearby::id)
                .containsExactly(east800m, north300m);
    }

    private long insertGroupBuy(double lat, double lng, String status, int deadlineDays) {
        jdbc.update("""
                INSERT INTO group_buys (host_id, title, description, category, product_url, price_type,
                    total_price, total_quantity, unit_label, capacity_units, status, location,
                    neighborhood_name, deadline_at, created_at, updated_at)
                VALUES (?, '휴지 30롤', '반띵해요', 'LIVING', 'https://example.com/p', 'EQUAL',
                    20000, 30, '롤', 3, ?, ST_PointFromText(?, 4326), '중구 명동',
                    NOW(6) + INTERVAL ? DAY, NOW(6), NOW(6))
                """, hostId, status, new GeoPoint(lat, lng).toWkt(), deadlineDays);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
