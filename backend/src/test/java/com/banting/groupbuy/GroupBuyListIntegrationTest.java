package com.banting.groupbuy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banting.TestcontainersConfiguration;
import com.banting.common.geo.GeoPoint;
import com.banting.support.DatabaseCleaner;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 공구 목록 API (T09 완료 조건: 내 동네 공구만, 마감임박순).
 * 서울시청(기본 위치) 근처와 강남역 근처에 공구를 두고, 누가 어떤 기준으로 보는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupBuyListIntegrationTest {

    private static final LocalDateTime NOW =
            LocalDateTime.now(ZoneId.of("Asia/Seoul")).truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private long hostId;
    private long cityHallSoon;
    private long cityHallLater;
    private long gangnam;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        jdbc.update("INSERT INTO users (email, password_hash, nickname, role, search_radius_m, created_at, updated_at) "
                + "VALUES ('host@test.com', 'h', '개설자', 'USER', 1000, NOW(6), NOW(6))");
        hostId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        cityHallLater = insert(37.5680, 126.9780, "중구 명동", 3, "휴지 30롤");
        cityHallSoon = insert(37.5660, 126.9790, "중구 명동", 1, "생수 24병");
        gangnam = insert(37.4980, 127.0280, "강남구 역삼1동", 2, "세제 리필");   // 서울시청에서 약 8.8km
    }

    @Test
    void 로그인하지_않으면_서울시청_기준_1km_마감임박순() throws Exception {
        mvc.perform(get("/api/group-buys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.neighborhoodName").value("서울시청"))
                .andExpect(jsonPath("$.defaultLocation").value(true))
                .andExpect(jsonPath("$.radiusM").value(1000))
                .andExpect(jsonPath("$.items[0].id").value(cityHallSoon))
                .andExpect(jsonPath("$.items[1].id").value(cityHallLater))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void 로그인하고_동네를_정했으면_내_동네_기준으로_내_동네_공구만() throws Exception {
        String token = signupAndLogin("me@test.com", "역삼주민");
        setLocation("me@test.com", 37.499, 127.028, "강남구 역삼1동", 500);

        mvc.perform(get("/api/group-buys").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.neighborhoodName").value("강남구 역삼1동"))
                .andExpect(jsonPath("$.defaultLocation").value(false))
                .andExpect(jsonPath("$.radiusM").value(500))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(gangnam));
    }

    @Test
    void 로그인했어도_동네를_안_정했으면_서울시청_기준() throws Exception {
        String token = signupAndLogin("new@test.com", "새회원");

        mvc.perform(get("/api/group-buys").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.defaultLocation").value(true))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void 반경_파라미터는_500_1000_3000만_허용한다() throws Exception {
        mvc.perform(get("/api/group-buys").param("radius", "3000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.radiusM").value(3000));
        mvc.perform(get("/api/group-buys").param("radius", "100000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void 잘못된_정렬_카테고리_페이지는_400() throws Exception {
        mvc.perform(get("/api/group-buys").param("sort", "PRICE")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/group-buys").param("category", "CAR")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/group-buys").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/group-buys").param("keyword", "가".repeat(51))).andExpect(status().isBadRequest());
    }

    @Test
    void 검색어_앞뒤_공백은_무시하고_빈_검색어는_전체() throws Exception {
        mvc.perform(get("/api/group-buys").param("keyword", "  휴지 "))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(cityHallLater));
        mvc.perform(get("/api/group-buys").param("keyword", "   "))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void 한_페이지는_20개이고_다음_페이지_여부를_알려준다() throws Exception {
        for (int i = 0; i < 19; i++) {
            insert(37.5665, 126.9780, "중구 명동", 4, "추가 공구 " + i);   // 서울시청 근처 총 21개
        }

        mvc.perform(get("/api/group-buys"))
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(get("/api/group-buys").param("page", "1"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void 카드에는_1자리_금액이_있고_좌표와_거리는_없다() throws Exception {
        String body = mvc.perform(get("/api/group-buys"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(6_666))   // 20,000원 / 3명 (남는 원은 개설자)
                .andExpect(jsonPath("$.items[0].capacityUnits").value(3))
                .andExpect(jsonPath("$.items[0].reservedUnits").value(1))
                .andExpect(jsonPath("$.items[0].neighborhoodName").value("중구 명동"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain("location", "latitude", "longitude", "distance", "POINT");
    }

    // ───── 시드(예시) 공구 (T09.5) ─────

    @Test
    void 기본_위치_목록에는_시드_공구가_예시로_섞여_나온다() throws Exception {
        long seed = insertSeed();

        mvc.perform(get("/api/group-buys"))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[?(@.id == %d)].demo".formatted(seed)).value(true))
                .andExpect(jsonPath("$.items[?(@.id == %d)].demo".formatted(cityHallSoon)).value(false));
    }

    @Test
    void 내_동네_목록에는_시드가_안_섞이고_체험_동네를_고르면_보인다() throws Exception {
        long seed = insertSeed();
        String token = signupAndLogin("cityhall@test.com", "시청주민");
        setLocation("cityhall@test.com", 37.567, 126.978, "중구 명동", 1000);

        mvc.perform(get("/api/group-buys").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.defaultLocation").value(false))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.id == %d)]".formatted(seed)).isEmpty());
        mvc.perform(get("/api/group-buys").param("demoArea", "true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.defaultLocation").value(true))
                .andExpect(jsonPath("$.neighborhoodName").value("서울시청"))
                .andExpect(jsonPath("$.items[?(@.id == %d)].demo".formatted(seed)).value(true));
    }

    @Test
    void 상세에도_예시_공구_여부가_있다() throws Exception {
        long seed = insertSeed();

        mvc.perform(get("/api/group-buys/" + seed)).andExpect(jsonPath("$.demo").value(true));
        mvc.perform(get("/api/group-buys/" + cityHallSoon)).andExpect(jsonPath("$.demo").value(false));
    }

    // ───── 헬퍼 ─────

    private long insertSeed() {
        long id = insert(37.5667, 126.9782, "중구 명동", 2, "예시 공구");
        jdbc.update("UPDATE group_buys SET demo_type = 'SEED' WHERE id = ?", id);
        return id;
    }

    private long insert(double lat, double lng, String neighborhood, int deadlineDays, String title) {
        jdbc.update("""
                INSERT INTO group_buys (host_id, title, description, category, product_url, price_type,
                    total_price, total_quantity, unit_label, capacity_units, reserved_units, paid_units, status,
                    location, neighborhood_name, deadline_at, created_at, updated_at)
                VALUES (?, ?, '반띵해요', 'LIVING', 'https://example.com/p', 'EQUAL',
                    20000, 30, '롤', 3, 1, 1, 'OPEN', ST_PointFromText(?, 4326), ?, ?, ?, ?)
                """, hostId, title, new GeoPoint(lat, lng).toWkt(), neighborhood,
                NOW.plusDays(deadlineDays), NOW, NOW);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 카카오 호출 없이 DB에 직접 동네 설정. */
    private void setLocation(String email, double lat, double lng, String name, int radius) {
        jdbc.update("UPDATE users SET location = ST_PointFromText(?, 4326), neighborhood_name = ?, "
                + "search_radius_m = ? WHERE email = ?", new GeoPoint(lat, lng).toWkt(), name, radius, email);
    }

    private String signupAndLogin(String email, String nickname) throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"password123\",\"nickname\":\"%s\"}".formatted(email, nickname)));
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.accessToken");
    }
}
