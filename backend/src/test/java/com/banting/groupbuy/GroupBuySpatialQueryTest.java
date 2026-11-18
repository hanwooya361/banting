package com.banting.groupbuy;

import static org.assertj.core.api.Assertions.assertThat;

import com.banting.TestcontainersConfiguration;
import com.banting.common.geo.GeoPoint;
import com.banting.groupbuy.GroupBuySpatialQuery.Condition;
import com.banting.groupbuy.GroupBuySpatialQuery.Row;
import com.banting.support.DatabaseCleaner;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
 * 반경 검색 쿼리 (T07) + 목록 조건 (T09). 기준점: 서울시청 (37.5665, 126.9780).
 * 위도 0.0027° ≈ 300m, 경도 1° ≈ 88.3km(37.57°N).
 * 시각은 DB의 NOW()가 아니라 앱 기준(Asia/Seoul) 값을 넣는다 (컨테이너 시간대와 무관하게).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GroupBuySpatialQueryTest {

    private static final GeoPoint CITY_HALL = new GeoPoint(37.5665, 126.9780);
    private static final LocalDateTime NOW =
            LocalDateTime.now(ZoneId.of("Asia/Seoul")).truncatedTo(ChronoUnit.SECONDS);

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
        // 개설 순서: north300m → east800m → south2500m (최신순이면 반대)
        north300m = insert(37.5692, 126.9780, "OPEN", 2, "휴지 30롤", "LIVING", 3);
        east800m = insert(37.5665, 126.98706, "OPEN", 1, "생수 2L 24병", "FOOD", 2);
        south2500m = insert(37.54404, 126.9780, "OPEN", 3, "키친타월 12롤", "KITCHEN", 1);
        insert(37.6114, 126.9780, "OPEN", 1, "북쪽 5km", "LIVING", 1);       // 3km 반경 밖
        insert(37.5670, 126.9785, "CONFIRMED", 1, "확정된 공구", "LIVING", 1); // 70m지만 모집 중이 아님
        insert(37.5668, 126.9781, "DRAFT", 1, "결제 전 공구", "LIVING", 1);    // 개설자 결제 전
    }

    @AfterEach
    void tearDown() {
        DatabaseCleaner.clean(jdbc);
    }

    // ───── 반경 (T07) ─────

    @Test
    void 반경_1km_안의_모집중_공구만_마감임박순으로_찾는다() {
        assertThat(ids(search(1000))).containsExactly(east800m, north300m);
    }

    @Test
    void 반경을_넓히면_더_먼_공구도_포함한다() {
        assertThat(ids(search(3000))).containsExactly(east800m, north300m, south2500m);
        assertThat(ids(search(500))).containsExactly(north300m);
    }

    @Test
    void 페이지_단위로_나눠_가져온다() {
        assertThat(ids(query.search(condition(3000, null, null, GroupBuySort.DEADLINE, 2, 0))))
                .containsExactly(east800m, north300m);
        assertThat(ids(query.search(condition(3000, null, null, GroupBuySort.DEADLINE, 2, 2))))
                .containsExactly(south2500m);
    }

    @Test
    void 사각형_모서리는_반경_밖이면_제외한다() {
        // 북동쪽 대각선 약 1.27km: 1km 사각형(±1.1km) 안이지만 실제 거리는 반경 밖
        long corner = insert(37.5746, 126.98823, "OPEN", 1, "모서리", "LIVING", 1);
        assertThat(ids(search(1000))).doesNotContain(corner);
    }

    // ───── 목록 조건 (T09) ─────

    @Test
    void 마감이_지난_공구는_스케줄러가_처리하기_전이라도_빠진다() {
        long expired = insert(37.5666, 126.9781, "OPEN", 0, "방금 마감", "LIVING", 1);
        jdbc.update("UPDATE group_buys SET deadline_at = ? WHERE id = ?", NOW.minusMinutes(1), expired);

        assertThat(ids(search(1000))).doesNotContain(expired);
    }

    @Test
    void 최신순은_개설_시각_역순() {
        assertThat(ids(query.search(condition(3000, null, null, GroupBuySort.LATEST, 20, 0))))
                .containsExactly(south2500m, east800m, north300m);
    }

    @Test
    void 카테고리로_거른다() {
        assertThat(ids(query.search(condition(3000, Category.FOOD, null, GroupBuySort.DEADLINE, 20, 0))))
                .containsExactly(east800m);
    }

    @Test
    void 상품명_일부로_검색한다() {
        assertThat(ids(query.search(condition(3000, null, "롤", GroupBuySort.DEADLINE, 20, 0))))
                .containsExactly(north300m, south2500m);
    }

    @Test
    void 검색어의_퍼센트와_밑줄은_글자_그대로_찾는다() {
        long percent = insert(37.5666, 126.9781, "OPEN", 1, "세제 100% 리필", "LIVING", 1);
        long underscore = insert(37.5666, 126.9781, "OPEN", 1, "a_b 세트", "LIVING", 1);

        // 이스케이프하지 않으면 "100%"는 "100으로 이어지는 모든 것", "_"는 "아무 글자 하나"가 된다
        assertThat(ids(query.search(condition(1000, null, "100%", GroupBuySort.DEADLINE, 20, 0))))
                .containsExactly(percent);
        assertThat(ids(query.search(condition(1000, null, "_", GroupBuySort.DEADLINE, 20, 0))))
                .containsExactly(underscore);
        assertThat(ids(query.search(condition(1000, null, "%", GroupBuySort.DEADLINE, 20, 0))))
                .containsExactly(percent);
    }

    @Test
    void 카드에_필요한_값과_첫번째_사진을_한_번에_가져온다() {
        jdbc.update("INSERT INTO group_buy_images (group_buy_id, url, sort_order, created_at, updated_at) "
                + "VALUES (?, 'images/group-buys/second.webp', 1, NOW(6), NOW(6)), "
                + "(?, 'images/group-buys/first.webp', 0, NOW(6), NOW(6))", north300m, north300m);

        List<Row> rows = search(1000);

        Row north = rows.stream().filter(r -> r.id() == north300m).findFirst().orElseThrow();
        assertThat(north.thumbnailKey()).isEqualTo("images/group-buys/first.webp");
        assertThat(north.title()).isEqualTo("휴지 30롤");
        assertThat(north.capacityUnits()).isEqualTo(3);
        assertThat(north.neighborhoodName()).isEqualTo("중구 명동");
        Row east = rows.stream().filter(r -> r.id() == east800m).findFirst().orElseThrow();
        assertThat(east.thumbnailKey()).isNull();
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
                    '부산', ? + INTERVAL (n MOD 30) DAY, NOW(6), NOW(6)
                FROM seq
                """, hostId, NOW.plusHours(1));
        jdbc.execute("ANALYZE TABLE group_buys");

        Condition condition = condition(1000, null, null, GroupBuySort.DEADLINE, 21, 0);
        List<Map<String, Object>> plan = namedJdbc.queryForList(
                "EXPLAIN " + GroupBuySpatialQuery.sql(condition), GroupBuySpatialQuery.params(condition));

        // 1행은 group_buys, 2행은 대표 사진 서브쿼리(group_buy_images)
        assertThat(plan.get(0).get("table")).isEqualTo("g");
        assertThat(plan.get(0).get("key")).isEqualTo("sp_group_buys_location");
        // 결과도 그대로
        assertThat(ids(query.search(condition))).containsExactly(east800m, north300m);
    }

    // ───── 헬퍼 ─────

    private List<Row> search(int radiusM) {
        return query.search(condition(radiusM, null, null, GroupBuySort.DEADLINE, 20, 0));
    }

    private static Condition condition(int radiusM, Category category, String keyword, GroupBuySort sort,
                                       int limit, int offset) {
        return new Condition(CITY_HALL, radiusM, category, keyword, sort, NOW, limit, offset);
    }

    private static List<Long> ids(List<Row> rows) {
        return rows.stream().map(Row::id).toList();
    }

    /** createdMinutesAgo가 클수록 먼저 개설된 공구. */
    private long insert(double lat, double lng, String status, int deadlineDays, String title, String category,
                        int createdMinutesAgo) {
        jdbc.update("""
                INSERT INTO group_buys (host_id, title, description, category, product_url, price_type,
                    total_price, total_quantity, unit_label, capacity_units, status, location,
                    neighborhood_name, deadline_at, created_at, updated_at)
                VALUES (?, ?, '반띵해요', ?, 'https://example.com/p', 'EQUAL',
                    20000, 30, '롤', 3, ?, ST_PointFromText(?, 4326), '중구 명동', ?, ?, ?)
                """, hostId, title, category, status, new GeoPoint(lat, lng).toWkt(),
                NOW.plusDays(deadlineDays).plusMinutes(1), NOW.minusMinutes(createdMinutesAgo),
                NOW.minusMinutes(createdMinutesAgo));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
