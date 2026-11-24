package com.banting.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banting.TestcontainersConfiguration;
import com.banting.common.geo.GeoPoint;
import com.banting.support.DatabaseCleaner;
import com.banting.support.FakeTossPaymentsClient;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
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
 * 참여 취소·환불과 개설자의 공구 취소 (T12 완료 조건: 마감 전 취소 시 결제 취소와 상태 변경). 토스는 가짜.
 *
 * <p>기본 공구: 균등 4명, 총 12,000원 → 1명 3,000원. 개설자 결제 완료 상태에서 시작.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RefundIntegrationTest.FakeTossConfig.class})
class RefundIntegrationTest {

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

    private long hostId;
    private String hostToken;
    private String aToken;
    private String bToken;
    private long groupBuyId;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        toss.reset();
        hostToken = signupAndLogin("host@test.com", "개설자");
        aToken = signupAndLogin("a@test.com", "이웃A");
        bToken = signupAndLogin("b@test.com", "이웃B");
        hostId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'host@test.com'", Long.class);
        groupBuyId = insertOpenGroupBuy();
    }

    // ───── 참여자 취소 ─────

    @Test
    void 결제한_참여를_마감_전에_취소하면_토스_결제를_취소하고_자리를_돌려준다() throws Exception {
        long participationId = pay(aToken, "pk-a");

        cancel(aToken, participationId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundAmount").value(3_000));

        assertThat(toss.cancels()).containsExactly("pk-a");
        assertThat(groupBuy()).containsEntry("status", "OPEN").containsEntry("reserved_units", 1).containsEntry("paid_units", 1);
        assertThat(participationStatus(participationId)).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForMap("SELECT status, canceled_at IS NOT NULL AS canceled FROM payments WHERE payment_key = 'pk-a'"))
                .containsEntry("status", "CANCELED").containsEntry("canceled", 1L);
    }

    @Test
    void 결제_대기_중인_참여를_취소하면_토스_호출_없이_자리만_돌려준다() throws Exception {
        participate(aToken);
        long participationId = myParticipationId(aToken);

        cancel(aToken, participationId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.refundAmount").value(0));

        assertThat(toss.cancels()).isEmpty();
        assertThat(groupBuy()).containsEntry("reserved_units", 1);
        // 이전 주문번호로는 더 이상 승인할 수 없다
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE participation_id = ?", String.class, participationId))
                .isEqualTo("FAILED");
        // 결제 전 취소는 다시 참여할 수 있다
        participate(aToken).andExpect(status().isCreated());
    }

    @Test
    void 토스_취소가_실패하면_환불_대기로_남고_자리는_바로_풀린다_다시_누르면_재시도() throws Exception {
        long participationId = pay(aToken, "pk-a");
        toss.failCancel();

        cancel(aToken, participationId).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REFUND_PENDING"));
        assertThat(groupBuy()).containsEntry("reserved_units", 1).containsEntry("paid_units", 1);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE payment_key = 'pk-a'", String.class)).isEqualTo("DONE");

        toss.cancelWorks();
        cancel(aToken, participationId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
        assertThat(toss.cancels()).containsExactly("pk-a", "pk-a");
        assertThat(groupBuy()).containsEntry("reserved_units", 1);   // 재시도에서 자리를 두 번 돌려주지 않음
    }

    @Test
    void 토스가_이미_취소된_결제라고_하면_환불_완료로_본다() throws Exception {
        long participationId = pay(aToken, "pk-a");
        toss.rejectCancel("ALREADY_CANCELED_PAYMENT");

        cancel(aToken, participationId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void 환불받은_공구에는_다시_참여할_수_없다() throws Exception {
        long participationId = pay(aToken, "pk-a");
        cancel(aToken, participationId);

        participate(aToken).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANNOT_REJOIN"));
    }

    @Test
    void 확정됐거나_마감된_공구는_취소할_수_없다() throws Exception {
        long participationId = pay(aToken, "pk-a");
        jdbc.update("UPDATE group_buys SET deadline_at = ? WHERE id = ?", NOW.minusMinutes(1), groupBuyId);
        cancel(aToken, participationId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARTICIPATION_NOT_CANCELABLE"));

        jdbc.update("UPDATE group_buys SET deadline_at = ?, status = 'CONFIRMED' WHERE id = ?", NOW.plusDays(1), groupBuyId);
        cancel(aToken, participationId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARTICIPATION_NOT_CANCELABLE"));
        assertThat(toss.cancels()).isEmpty();
    }

    @Test
    void 남의_참여는_취소할_수_없고_개설자는_공구_취소를_써야_한다() throws Exception {
        long participationId = pay(aToken, "pk-a");
        cancel(bToken, participationId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PARTICIPATION_NOT_FOUND"));

        long hostParticipationId = jdbc.queryForObject("SELECT id FROM participations WHERE is_host", Long.class);
        cancel(hostToken, hostParticipationId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOST_USE_GROUP_CANCEL"));
    }

    @Test
    void 취소로_빈_자리에_다른_이웃이_참여할_수_있다() throws Exception {
        pay(bToken, "pk-b");
        long a = pay(aToken, "pk-a");
        String third = signupAndLogin("c@test.com", "이웃C");
        pay(third, "pk-c");                                  // 4/4 → 확정
        assertThat(groupBuy()).containsEntry("status", "CONFIRMED");
        cancel(aToken, a).andExpect(status().isConflict());  // 확정 뒤라 취소 불가

        // 확정 전 상황: 다른 공구에서 취소 → 재참여
        long other = insertOpenGroupBuy();
        groupBuyId = other;
        long a2 = pay(aToken, "pk-a2");
        cancel(aToken, a2).andExpect(status().isOk());
        pay(bToken, "pk-b2");
        assertThat(groupBuy()).containsEntry("reserved_units", 2).containsEntry("paid_units", 2);
    }

    // ───── 개설자의 공구 취소 ─────

    @Test
    void 개설자가_공구를_취소하면_무산되고_개설자_포함_전원_환불_결제_대기는_취소() throws Exception {
        long a = pay(aToken, "pk-a");
        participate(bToken);
        long b = myParticipationId(bToken);

        hostCancel(hostToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.refunded").value(2))
                .andExpect(jsonPath("$.pendingRefunds").value(0));

        assertThat(jdbc.queryForMap("SELECT status, fail_reason FROM group_buys WHERE id = ?", groupBuyId))
                .containsEntry("status", "FAILED").containsEntry("fail_reason", "HOST_CANCELED");
        assertThat(toss.cancels()).containsExactlyInAnyOrder("pk-host", "pk-a");
        assertThat(participationStatus(a)).isEqualTo("REFUNDED");
        assertThat(participationStatus(b)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT status FROM participations WHERE is_host", String.class)).isEqualTo("REFUNDED");
        // 무산된 공구는 목록에서 사라지고, 참여도 안 된다
        mvc.perform(get("/api/group-buys")).andExpect(jsonPath("$.items.length()").value(0));
        participate(bToken).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GROUP_BUY_NOT_OPEN"));
    }

    @Test
    void 공구_취소_중_일부_환불이_실패하면_남은_건만_다시_누를_때_재시도() throws Exception {
        pay(aToken, "pk-a");
        toss.failCancel();

        hostCancel(hostToken).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.refunded").value(0))
                .andExpect(jsonPath("$.pendingRefunds").value(2));
        assertThat(jdbc.queryForObject("SELECT status FROM group_buys WHERE id = ?", String.class, groupBuyId))
                .isEqualTo("FAILED");

        toss.cancelWorks();
        hostCancel(hostToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.refunded").value(2))
                .andExpect(jsonPath("$.pendingRefunds").value(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM participations WHERE status = 'REFUNDED'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void 개설자만_모집_중인_공구를_취소할_수_있다() throws Exception {
        hostCancel(aToken).andExpect(status().isForbidden());

        jdbc.update("UPDATE group_buys SET status = 'CONFIRMED' WHERE id = ?", groupBuyId);
        hostCancel(hostToken).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_BUY_NOT_CANCELABLE"));
    }

    @Test
    void 상세의_내_참여에는_취소할_때_쓸_id가_있다() throws Exception {
        long participationId = pay(aToken, "pk-a");
        mvc.perform(get("/api/group-buys/" + groupBuyId).header(HttpHeaders.AUTHORIZATION, "Bearer " + aToken))
                .andExpect(jsonPath("$.myParticipation.id").value(participationId));
    }

    // ───── 헬퍼 ─────

    /** 참여 + 결제 승인까지. 참여 id를 돌려준다. */
    private long pay(String token, String paymentKey) throws Exception {
        String body = participate(token).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String orderId = JsonPath.read(body, "$.orderId");
        long amount = ((Number) JsonPath.read(body, "$.amount")).longValue();
        mvc.perform(post("/api/payments/confirm").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentKey\":\"%s\",\"orderId\":\"%s\",\"amount\":%d}".formatted(paymentKey, orderId, amount)))
                .andExpect(status().isOk());
        return ((Number) JsonPath.read(body, "$.participationId")).longValue();
    }

    private ResultActions participate(String token) throws Exception {
        return mvc.perform(post("/api/group-buys/" + groupBuyId + "/participations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"units\":1}"));
    }

    private long myParticipationId(String token) throws Exception {
        String body = mvc.perform(get("/api/group-buys/" + groupBuyId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.myParticipation.id")).longValue();
    }

    private ResultActions cancel(String token, long participationId) throws Exception {
        return mvc.perform(delete("/api/participations/" + participationId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions hostCancel(String token) throws Exception {
        return mvc.perform(post("/api/group-buys/" + groupBuyId + "/cancel").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    /** 모집 중 균등 4명 공구 + 결제 완료된 개설자 몫(결제 기록 pk-host 포함). */
    private long insertOpenGroupBuy() {
        jdbc.update("""
                INSERT INTO group_buys (host_id, title, description, category, product_url, price_type,
                    total_price, total_quantity, unit_label, capacity_units, min_units_per_person,
                    reserved_units, paid_units, status, location, neighborhood_name, deadline_at, created_at, updated_at)
                VALUES (?, '생수 24병', '반띵해요', 'FOOD', 'https://example.com/p', 'EQUAL', 12000, 24, '병', 4, 1, 1, 1,
                    'OPEN', ST_PointFromText(?, 4326), '중구 명동', ?, ?, ?)
                """, hostId, new GeoPoint(37.5665, 126.978).toWkt(), NOW.plusDays(2), NOW, NOW);
        long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO participations (group_buy_id, user_id, is_host, units, amount, status, expires_at, created_at, updated_at)
                VALUES (?, ?, TRUE, 1, 3000, 'PAID', ?, ?, ?)
                """, id, hostId, NOW, NOW, NOW);
        long participationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO payments (participation_id, order_id, payment_key, amount, status, approved_at, created_at, updated_at)
                VALUES (?, ?, 'pk-host', 3000, 'DONE', ?, ?, ?)
                """, participationId, "bt-host-" + id, NOW, NOW, NOW);
        return id;
    }

    private Map<String, Object> groupBuy() {
        return jdbc.queryForMap("SELECT status, reserved_units, paid_units FROM group_buys WHERE id = ?", groupBuyId);
    }

    private String participationStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM participations WHERE id = ?", String.class, id);
    }

    private String signupAndLogin(String email, String nickname) throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"password123\",\"nickname\":\"%s\",\"agreePrivacy\":true}".formatted(email, nickname)));
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.accessToken");
    }
}
