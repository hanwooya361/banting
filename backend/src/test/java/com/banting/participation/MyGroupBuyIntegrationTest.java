package com.banting.participation;

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
import org.hamcrest.Matchers;
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
import org.springframework.test.web.servlet.ResultActions;

/** 내 공구 화면 (S07): 참여 중 / 개설한 / 지난 공구 분류와 "지금 할 일". */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MyGroupBuyIntegrationTest {

    private static final LocalDateTime NOW =
            LocalDateTime.now(ZoneId.of("Asia/Seoul")).truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private long meId;
    private long otherId;
    private String meToken;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        meToken = signupAndLogin("me@test.com", "내계정");
        signupAndLogin("other@test.com", "이웃");
        meId = userId("me@test.com");
        otherId = userId("other@test.com");
    }

    @Test
    void 참여한_공구는_진행_상태에_따라_참여_중이나_지난_공구로_나뉜다() throws Exception {
        long open = groupBuy(otherId, "OPEN", "모집 중", NOW.plusDays(2));
        participate(open, meId, false, "PAID");
        long pickup = groupBuy(otherId, "PICKUP", "나눔 준비", NOW.minusDays(1));
        participate(pickup, meId, false, "PAID");
        jdbc.update("INSERT INTO pickup_options (group_buy_id, pickup_at, place_name, selected, created_at, updated_at) "
                + "VALUES (?, ?, '편의점 앞', TRUE, ?, ?)", pickup, NOW.plusDays(1), NOW, NOW);
        long completed = groupBuy(otherId, "COMPLETED", "완료", NOW.minusDays(5));
        participate(completed, meId, false, "RECEIVED");
        long refunded = groupBuy(otherId, "OPEN", "취소함", NOW.plusDays(3));
        participate(refunded, meId, false, "REFUNDED");

        list().andExpect(status().isOk())
                .andExpect(jsonPath("$.active.length()").value(2))
                .andExpect(jsonPath("$.active[0].title").value("나눔 준비"))   // 할 일(받았어요)이 있어 먼저
                .andExpect(jsonPath("$.active[0].todo").value("RECEIVE"))
                .andExpect(jsonPath("$.active[0].pickupPlace").value("편의점 앞"))
                .andExpect(jsonPath("$.active[1].title").value("모집 중"))
                .andExpect(jsonPath("$.active[1].todo").doesNotExist())
                .andExpect(jsonPath("$.hosting.length()").value(0))
                .andExpect(jsonPath("$.past[*].title", Matchers.containsInAnyOrder("완료", "취소함")));
    }

    @Test
    void 개설한_공구는_개설한_탭이고_확정되면_구매할_차례() throws Exception {
        long confirmed = groupBuy(meId, "CONFIRMED", "확정됨", NOW.minusHours(1));
        participate(confirmed, meId, true, "PAID");
        long draft = groupBuy(meId, "DRAFT", "결제 전", NOW.plusDays(2));
        participate(draft, meId, true, "PENDING_PAYMENT", NOW.plusMinutes(5));
        long failed = groupBuy(meId, "FAILED", "무산", NOW.minusDays(1));
        participate(failed, meId, true, "REFUNDED");

        list().andExpect(jsonPath("$.hosting.length()").value(2))
                .andExpect(jsonPath("$.hosting[*].todo", Matchers.containsInAnyOrder("PURCHASE", "PAY")))
                .andExpect(jsonPath("$.hosting[0].host").value(true))
                .andExpect(jsonPath("$.past[0].title").value("무산"))
                .andExpect(jsonPath("$.past[0].failReason").value("DEADLINE_UNMET"))
                .andExpect(jsonPath("$.active.length()").value(0));
    }

    @Test
    void 결제_대기는_결제할_차례지만_만료됐으면_아니다() throws Exception {
        long fresh = groupBuy(otherId, "OPEN", "대기 중", NOW.plusDays(2));
        participate(fresh, meId, false, "PENDING_PAYMENT", NOW.plusMinutes(5));
        long expired = groupBuy(otherId, "OPEN", "만료", NOW.plusDays(2));
        participate(expired, meId, false, "PENDING_PAYMENT", NOW.minusMinutes(1));   // 스케줄러가 곧 취소

        list().andExpect(jsonPath("$.active[0].title").value("대기 중"))
                .andExpect(jsonPath("$.active[0].todo").value("PAY"))
                .andExpect(jsonPath("$.active[1].todo").doesNotExist());
    }

    @Test
    void 결제하지_않고_취소된_참여와_시드_공구와_남의_공구는_보이지_않는다() throws Exception {
        long canceled = groupBuy(otherId, "OPEN", "결제 안 함", NOW.plusDays(2));
        participate(canceled, meId, false, "CANCELED");
        long seed = groupBuy(otherId, "OPEN", "시드", NOW.plusDays(2));
        jdbc.update("UPDATE group_buys SET demo_type = 'SEED' WHERE id = ?", seed);
        participate(seed, meId, false, "PAID");
        long others = groupBuy(otherId, "OPEN", "남의 공구", NOW.plusDays(2));
        participate(others, otherId, true, "PAID");

        list().andExpect(jsonPath("$.active.length()").value(0))
                .andExpect(jsonPath("$.hosting.length()").value(0))
                .andExpect(jsonPath("$.past.length()").value(0));
    }

    @Test
    void 대표_사진은_첫_번째_사진() throws Exception {
        long id = groupBuy(otherId, "OPEN", "사진 있음", NOW.plusDays(2));
        participate(id, meId, false, "PAID");
        jdbc.update("INSERT INTO group_buy_images (group_buy_id, url, sort_order, created_at, updated_at) VALUES "
                + "(?, 'images/group-buys/second.webp', 1, ?, ?), (?, 'images/group-buys/first.webp', 0, ?, ?)",
                id, NOW, NOW, id, NOW, NOW);

        list().andExpect(jsonPath("$.active[0].thumbnailUrl", Matchers.endsWith("/images/group-buys/first.webp")));
    }

    @Test
    void 로그인하지_않으면_볼_수_없다() throws Exception {
        mvc.perform(get("/api/users/me/group-buys")).andExpect(status().isUnauthorized());
    }

    // ───── 헬퍼 ─────

    private ResultActions list() throws Exception {
        return mvc.perform(get("/api/users/me/group-buys").header(HttpHeaders.AUTHORIZATION, "Bearer " + meToken));
    }

    private long groupBuy(long hostId, String status, String title, LocalDateTime deadline) {
        jdbc.update("""
                INSERT INTO group_buys (host_id, title, description, category, product_url, price_type,
                    total_price, total_quantity, unit_label, capacity_units, min_units_per_person,
                    reserved_units, paid_units, status, fail_reason, location, neighborhood_name, deadline_at,
                    created_at, updated_at)
                VALUES (?, ?, '설명', 'LIVING', 'https://example.com/p', 'EQUAL', 3000, 3, '롤', 3, 1, 1, 1, ?, ?,
                    ST_PointFromText(?, 4326), '중구 명동', ?, ?, ?)
                """, hostId, title, status, "FAILED".equals(status) ? "DEADLINE_UNMET" : null,
                new GeoPoint(37.5665, 126.978).toWkt(), deadline, NOW, NOW);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void participate(long groupBuyId, long userId, boolean host, String status) {
        participate(groupBuyId, userId, host, status, NOW.minusHours(1));
    }

    private void participate(long groupBuyId, long userId, boolean host, String status, LocalDateTime expiresAt) {
        jdbc.update("""
                INSERT INTO participations (group_buy_id, user_id, is_host, units, amount, status, expires_at,
                    created_at, updated_at)
                VALUES (?, ?, ?, 1, 1000, ?, ?, ?, ?)
                """, groupBuyId, userId, host, status, expiresAt, NOW, NOW);
    }

    private long userId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
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
