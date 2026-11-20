package com.banting.pickup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banting.TestcontainersConfiguration;
import com.banting.common.geo.GeoPoint;
import com.banting.groupbuy.GroupBuyLifecycleService;
import com.banting.support.DatabaseCleaner;
import com.banting.support.FakeTossPaymentsClient;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 수령 투표·확정 시 1위 선택·알림 (T15 완료 조건: 확정 시 1위 일정이 수령 일정으로 저장).
 *
 * <p>기본 공구: 균등 3명, 3,000원(1자리 1,000원), 개설자 결제 완료. 수령 후보 3개 (가: +3일, 나: +4일, 다: +5일).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PickupVoteIntegrationTest.FakeTossConfig.class})
class PickupVoteIntegrationTest {

    @TestConfiguration
    static class FakeTossConfig {
        @Bean
        @Primary
        FakeTossPaymentsClient fakeTossPaymentsClient() {
            return new FakeTossPaymentsClient();
        }
    }

    private static final LocalDateTime NOW =
            LocalDateTime.now(ZoneId.of("Asia/Seoul")).truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeTossPaymentsClient toss;

    @Autowired
    GroupBuyLifecycleService lifecycle;

    private long hostId;
    private long aId;
    private long bId;
    private String hostToken;
    private String aToken;
    private String bToken;
    private long id;
    private long optA;   // 가: 가장 이른 후보
    private long optB;
    private long optC;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        toss.reset();
        hostToken = signupAndLogin("host@test.com", "개설자");
        aToken = signupAndLogin("a@test.com", "이웃A");
        bToken = signupAndLogin("b@test.com", "이웃B");
        hostId = userId("host@test.com");
        aId = userId("a@test.com");
        bId = userId("b@test.com");
        id = insertGroupBuy(NOW.plusDays(1));
        insertParticipation(hostId, true, "PAID");
        optA = insertOption(NOW.plusDays(3), "편의점 앞");
        optB = insertOption(NOW.plusDays(4), "놀이터");
        optC = insertOption(NOW.plusDays(5), "역 출구");
    }

    // ───── 투표 ─────

    @Test
    void 결제한_참여자는_투표하고_다시_투표하면_표가_바뀐다() throws Exception {
        insertParticipation(aId, false, "PAID");

        vote(aToken, optB).andExpect(status().isOk())
                .andExpect(jsonPath("$.myOptionId").value(optB))
                .andExpect(jsonPath("$.votesByOption." + optB).value(1));
        vote(aToken, optC).andExpect(status().isOk())
                .andExpect(jsonPath("$.votesByOption." + optC).value(1))
                .andExpect(jsonPath("$.votesByOption." + optB).doesNotExist());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pickup_votes", Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/group-buys/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + aToken))
                .andExpect(jsonPath("$.myPickupVoteOptionId").value(optC))
                .andExpect(jsonPath("$.pickupOptions[2].voteCount").value(1));
    }

    @Test
    void 개설자도_결제했으니_투표할_수_있다() throws Exception {
        vote(hostToken, optA).andExpect(status().isOk());
    }

    @Test
    void 결제하지_않은_사람은_투표할_수_없다() throws Exception {
        insertParticipation(aId, false, "PENDING_PAYMENT");

        vote(aToken, optA).andExpect(status().isForbidden());   // 결제 대기
        vote(bToken, optA).andExpect(status().isForbidden());   // 참여 안 함
    }

    @Test
    void 다른_공구의_후보에는_투표할_수_없다() throws Exception {
        insertParticipation(aId, false, "PAID");
        long other = insertGroupBuy(NOW.plusDays(1));
        jdbc.update("INSERT INTO pickup_options (group_buy_id, pickup_at, place_name, created_at, updated_at) "
                + "VALUES (?, ?, '다른 곳', ?, ?)", other, NOW.plusDays(3), NOW, NOW);
        long otherOption = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        vote(aToken, otherOption).andExpect(status().isNotFound());
    }

    @Test
    void 확정되면_투표가_닫힌다() throws Exception {
        insertParticipation(aId, false, "PAID");
        jdbc.update("UPDATE group_buys SET status = 'CONFIRMED' WHERE id = ?", id);

        vote(aToken, optA).andExpect(status().isConflict());
    }

    @Test
    void 투표_후_취소한_사람의_표는_세지_않는다() throws Exception {
        long a = insertParticipation(aId, false, "PAID");
        vote(aToken, optC);
        jdbc.update("UPDATE participations SET status = 'REFUNDED' WHERE id = ?", a);

        mvc.perform(get("/api/group-buys/" + id))
                .andExpect(jsonPath("$.pickupOptions[2].voteCount").value(0));
    }

    // ───── 확정 시 1위 선택 (완료 조건) ─────

    @Test
    void 마지막_자리_결제로_확정되면_1위_후보가_수령_일정으로_저장되고_전원에게_알린다() throws Exception {
        insertParticipation(aId, false, "PAID");
        vote(aToken, optC);
        vote(hostToken, optC);

        payLastSeat(bToken, "pk-b").andExpect(jsonPath("$.groupBuyStatus").value("CONFIRMED"));

        assertThat(selectedOptions()).containsExactly(optC);
        mvc.perform(get("/api/group-buys/" + id)).andExpect(jsonPath("$.pickupOptions[2].selected").value(true));
        assertThat(notifiedUsers("GROUP_BUY_CONFIRMED")).containsExactlyInAnyOrder(hostId, aId, bId);
        assertThat(jdbc.queryForObject("SELECT message FROM notifications WHERE user_id = ?", String.class, bId))
                .contains("확정").contains("역 출구");
    }

    @Test
    void 동점이면_수령_시각이_이른_후보가_1위() throws Exception {
        insertParticipation(aId, false, "PAID");
        vote(aToken, optC);
        vote(hostToken, optB);

        payLastSeat(bToken, "pk-b");

        assertThat(selectedOptions()).containsExactly(optB);
    }

    @Test
    void 아무도_투표하지_않았으면_가장_이른_후보() throws Exception {
        insertParticipation(aId, false, "PAID");

        payLastSeat(bToken, "pk-b");

        assertThat(selectedOptions()).containsExactly(optA);
    }

    @Test
    void 확정되지_않은_결제에는_수령_일정도_확정_알림도_없다() throws Exception {
        payLastSeat(bToken, "pk-b").andExpect(jsonPath("$.groupBuyStatus").value("OPEN"));   // 3자리 중 2자리

        assertThat(selectedOptions()).isEmpty();
        assertThat(notifiedUsers("GROUP_BUY_CONFIRMED")).isEmpty();
    }

    // ───── 무산·취소 알림 ─────

    @Test
    void 마감_미달로_무산되면_결제했던_사람에게_알린다() {
        insertParticipation(aId, false, "PAID");
        insertParticipation(bId, false, "PENDING_PAYMENT");   // 결제 전이라 알리지 않음
        jdbc.update("UPDATE group_buys SET reserved_units = 3, paid_units = 2, deadline_at = ? WHERE id = ?",
                NOW.minusMinutes(5), id);

        lifecycle.failOverdueGroupBuys();

        assertThat(notifiedUsers("GROUP_BUY_FAILED")).containsExactlyInAnyOrder(hostId, aId);
    }

    @Test
    void 개설자가_취소하면_참여자에게만_알린다() throws Exception {
        insertParticipation(aId, false, "PAID");
        jdbc.update("UPDATE group_buys SET reserved_units = 2, paid_units = 2 WHERE id = ?", id);

        mvc.perform(post("/api/group-buys/" + id + "/cancel").header(HttpHeaders.AUTHORIZATION, "Bearer " + hostToken))
                .andExpect(status().isOk());

        assertThat(notifiedUsers("GROUP_BUY_CANCELED")).containsExactly(aId);
    }

    // ───── 알림 API ─────

    @Test
    void 내_알림을_보고_읽음_처리한다() throws Exception {
        insertParticipation(aId, false, "PAID");
        payLastSeat(bToken, "pk-b");

        String body = mvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, "Bearer " + aToken))
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.items[0].groupBuyId").value(id))
                .andExpect(jsonPath("$.items[0].read").value(false))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long notificationId = ((Number) JsonPath.read(body, "$.items[0].id")).longValue();

        // 남의 알림은 없는 알림처럼
        mvc.perform(post("/api/notifications/" + notificationId + "/read")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bToken)).andExpect(status().isNotFound());

        mvc.perform(post("/api/notifications/" + notificationId + "/read")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + aToken)).andExpect(status().isNoContent());
        mvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, "Bearer " + aToken))
                .andExpect(jsonPath("$.unreadCount").value(0))
                .andExpect(jsonPath("$.items[0].read").value(true));

        mvc.perform(post("/api/notifications/read-all").header(HttpHeaders.AUTHORIZATION, "Bearer " + bToken))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, "Bearer " + bToken))
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void 로그인하지_않으면_알림을_볼_수_없다() throws Exception {
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
    }

    // ───── 헬퍼 ─────

    /** 참여 → 결제 승인까지. 반환은 승인 응답. */
    private ResultActions payLastSeat(String token, String paymentKey) throws Exception {
        String body = mvc.perform(post("/api/group-buys/" + id + "/participations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"units\":1}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String orderId = JsonPath.read(body, "$.orderId");
        return mvc.perform(post("/api/payments/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentKey\":\"%s\",\"orderId\":\"%s\",\"amount\":1000}".formatted(paymentKey, orderId)))
                .andExpect(status().isOk());
    }

    private ResultActions vote(String token, long optionId) throws Exception {
        return mvc.perform(post("/api/group-buys/" + id + "/pickup-votes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"optionId\":" + optionId + "}"));
    }

    private List<Long> selectedOptions() {
        return jdbc.queryForList("SELECT id FROM pickup_options WHERE group_buy_id = ? AND selected = TRUE",
                Long.class, id);
    }

    private List<Long> notifiedUsers(String type) {
        return jdbc.queryForList("SELECT user_id FROM notifications WHERE type = ? AND group_buy_id = ?",
                Long.class, type, id);
    }

    /** 모집 중, 개설자 1자리 결제됨. reserved는 참여자를 넣을 때마다 맞춘다. */
    private long insertGroupBuy(LocalDateTime deadline) {
        jdbc.update("""
                INSERT INTO group_buys (host_id, title, description, category, product_url, price_type,
                    total_price, total_quantity, unit_label, capacity_units, min_units_per_person,
                    reserved_units, paid_units, status, location, neighborhood_name, deadline_at, created_at, updated_at)
                VALUES (?, '휴지 30롤', '반띵해요', 'LIVING', 'https://example.com/p', 'EQUAL', 3000, 3, '롤', 3, 1,
                    1, 1, 'OPEN', ST_PointFromText(?, 4326), '중구 명동', ?, ?, ?)
                """, hostId, new GeoPoint(37.5665, 126.978).toWkt(), deadline, NOW, NOW);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertParticipation(long userId, boolean host, String status) {
        jdbc.update("""
                INSERT INTO participations (group_buy_id, user_id, is_host, units, amount, status, expires_at,
                    created_at, updated_at)
                VALUES (?, ?, ?, 1, 1000, ?, ?, ?, ?)
                """, id, userId, host, status, NOW.plusMinutes(10), NOW, NOW);
        long participationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (!host) {
            jdbc.update("UPDATE group_buys SET reserved_units = reserved_units + 1"
                    + ("PAID".equals(status) ? ", paid_units = paid_units + 1" : "") + " WHERE id = ?", id);
        }
        if ("PAID".equals(status)) {
            jdbc.update("""
                    INSERT INTO payments (participation_id, order_id, payment_key, amount, status, approved_at,
                        created_at, updated_at)
                    VALUES (?, ?, ?, 1000, 'DONE', ?, ?, ?)
                    """, participationId, "bt-" + java.util.UUID.randomUUID(), "pk-" + participationId, NOW, NOW, NOW);
        }
        return participationId;
    }

    private long insertOption(LocalDateTime at, String place) {
        jdbc.update("INSERT INTO pickup_options (group_buy_id, pickup_at, place_name, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, ?)", id, at, place, NOW, NOW);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
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
