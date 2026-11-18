package com.banting.payment;

import static org.assertj.core.api.Assertions.assertThat;
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
 * 참여 = 결제 (T11 완료 조건: 개설자·참여자 결제 승인, 실패 시 보상 취소). 토스는 가짜.
 *
 * <p>기본 공구: 균등 3명, 총 10,000원 → 참여자 3,333원, 개설자 3,334원(남는 1원).
 * 수량별 공구: 30개 30,000원, 최소 5개, 개설자 10개 → 1개 1,000원.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ParticipationPaymentIntegrationTest.FakeTossConfig.class})
class ParticipationPaymentIntegrationTest {

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
    private String cToken;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        toss.reset();
        hostToken = signupAndLogin("host@test.com", "개설자");
        aToken = signupAndLogin("a@test.com", "이웃A");
        bToken = signupAndLogin("b@test.com", "이웃B");
        cToken = signupAndLogin("c@test.com", "이웃C");
        hostId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'host@test.com'", Long.class);
    }

    // ───── 개설자 결제 ─────

    @Test
    void 개설자가_내_몫을_결제하면_공구가_모집_중이_되고_목록에_보인다() throws Exception {
        long id = insertGroupBuy("DRAFT", "EQUAL", 10_000, 3, 3, 1, 1, 0);
        insertHostParticipation(id, 1, 3_334, "PENDING_PAYMENT");

        String orderId = orderId(participate(hostToken, id, 1).andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(3_334)));
        mvc.perform(get("/api/group-buys")).andExpect(jsonPath("$.items.length()").value(0));   // 결제 전엔 목록에 없음

        confirm(hostToken, "pk-host", orderId, 3_334)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupBuyStatus").value("OPEN"))
                .andExpect(jsonPath("$.participationStatus").value("PAID"));

        assertThat(row(id)).containsEntry("status", "OPEN").containsEntry("reserved_units", 1).containsEntry("paid_units", 1);
        assertThat(paymentOf(orderId)).containsEntry("status", "DONE").containsEntry("payment_key", "pk-host");
        mvc.perform(get("/api/group-buys")).andExpect(jsonPath("$.items[0].id").value(id));
    }

    @Test
    void 결제_전_공구에는_개설자만_결제할_수_있고_남에게는_없는_공구() throws Exception {
        long id = insertGroupBuy("DRAFT", "EQUAL", 10_000, 3, 3, 1, 1, 0);
        insertHostParticipation(id, 1, 3_334, "PENDING_PAYMENT");

        participate(aToken, id, 1).andExpect(status().isNotFound());
    }

    // ───── 참여자 결제 ─────

    @Test
    void 참여하면_자리를_먼저_잡고_서버가_계산한_금액으로_주문번호를_준다() throws Exception {
        long id = openEqual();

        participate(aToken, id, 1).andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").isNotEmpty())
                .andExpect(jsonPath("$.amount").value(3_333))
                .andExpect(jsonPath("$.orderName").value("휴지 30롤"));

        assertThat(row(id)).containsEntry("reserved_units", 2).containsEntry("paid_units", 1);
        assertThat(jdbc.queryForMap("SELECT status, units, amount FROM participations WHERE is_host = FALSE"))
                .containsEntry("status", "PENDING_PAYMENT").containsEntry("amount", 3_333L);
        assertThat(toss.confirms()).isEmpty();   // 아직 돈은 안 나감
    }

    @Test
    void 마지막_자리_결제가_승인되면_공구가_확정된다() throws Exception {
        long id = openEqual();

        payFully(aToken, id, 1, "pk-a").andExpect(jsonPath("$.groupBuyStatus").value("OPEN"));
        payFully(bToken, id, 1, "pk-b").andExpect(jsonPath("$.groupBuyStatus").value("CONFIRMED"));

        assertThat(row(id)).containsEntry("status", "CONFIRMED").containsEntry("paid_units", 3);
        assertThat(jdbc.queryForObject("SELECT confirmed_at IS NOT NULL FROM group_buys WHERE id = ?", Boolean.class, id))
                .isTrue();
        // 결제 금액 합계 = 총가 (남는 1원은 개설자 몫에 이미 포함)
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM participations WHERE status = 'PAID'", Long.class))
                .isEqualTo(10_000L);
    }

    @Test
    void 자리가_없으면_참여할_수_없다() throws Exception {
        long id = openEqual();
        participate(aToken, id, 1).andExpect(status().isCreated());
        participate(bToken, id, 1).andExpect(status().isCreated());   // 결제 대기도 자리를 차지

        participate(cToken, id, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_BUY_FULL"));
        assertThat(row(id)).containsEntry("reserved_units", 3);
    }

    @Test
    void 수량별_공구는_최소_수량_이상_남은_자리_이하로만() throws Exception {
        long id = insertGroupBuy("OPEN", "BY_QUANTITY", 30_000, 30, 30, 5, 10, 10);
        insertHostParticipation(id, 10, 10_000, "PAID");

        participate(aToken, id, 4).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARTICIPATION_UNITS"));
        participate(aToken, id, 21).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_BUY_FULL"));
        participate(aToken, id, 7).andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(7_000));
    }

    @Test
    void 균등_공구는_한_자리만() throws Exception {
        long id = openEqual();
        participate(aToken, id, 2).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARTICIPATION_UNITS"));
    }

    @Test
    void 예시_공구_마감된_공구_개설자_본인은_참여할_수_없다() throws Exception {
        long seed = openEqual();
        jdbc.update("UPDATE group_buys SET demo_type = 'SEED' WHERE id = ?", seed);
        participate(aToken, seed, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEMO_GROUP_BUY"));

        long closed = openEqual();
        jdbc.update("UPDATE group_buys SET deadline_at = ? WHERE id = ?", NOW.minusMinutes(1), closed);
        participate(aToken, closed, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_BUY_NOT_OPEN"));

        long mine = openEqual();
        participate(hostToken, mine, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_PARTICIPATED"));
    }

    @Test
    void 결제를_마친_공구에는_다시_참여할_수_없다() throws Exception {
        long id = openEqual();
        payFully(aToken, id, 1, "pk-a");

        participate(aToken, id, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_PARTICIPATED"));
    }

    @Test
    void 결제_대기_중에_다시_참여하면_자리는_그대로_새_주문번호를_주고_이전_주문은_못_쓴다() throws Exception {
        long id = openEqual();
        String first = orderId(participate(aToken, id, 1));
        String second = orderId(participate(aToken, id, 1));

        assertThat(second).isNotEqualTo(first);
        assertThat(row(id)).containsEntry("reserved_units", 2);   // 자리를 두 번 잡지 않음
        confirm(aToken, "pk-old", first, 3_333).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_PENDING"));
        confirm(aToken, "pk-new", second, 3_333).andExpect(status().isOk());
        assertThat(toss.confirms()).hasSize(1);
    }

    @Test
    void 만료되어_취소된_참여는_같은_행을_다시_써서_재참여한다() throws Exception {
        long id = openEqual();
        participate(aToken, id, 1);
        // 스케줄러(T14)가 만료 처리한 상태를 흉내: 참여 CANCELED, 자리 반환
        jdbc.update("UPDATE participations SET status = 'CANCELED' WHERE is_host = FALSE");
        jdbc.update("UPDATE group_buys SET reserved_units = 1 WHERE id = ?", id);

        participate(aToken, id, 1).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM participations WHERE is_host = FALSE", Integer.class)).isEqualTo(1);
        assertThat(row(id)).containsEntry("reserved_units", 2);
    }

    // ───── 승인 검증 (토스 호출 전) ─────

    @Test
    void 금액이_다르면_토스를_부르지_않고_거부한다() throws Exception {
        long id = openEqual();
        String orderId = orderId(participate(aToken, id, 1));

        confirm(aToken, "pk-a", orderId, 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));
        assertThat(toss.confirms()).isEmpty();
    }

    @Test
    void 결제_대기_시간이_지났으면_토스를_부르지_않고_거부한다() throws Exception {
        long id = openEqual();
        String orderId = orderId(participate(aToken, id, 1));
        jdbc.update("UPDATE participations SET expires_at = ? WHERE is_host = FALSE", NOW.minusSeconds(1));

        confirm(aToken, "pk-a", orderId, 3_333).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_EXPIRED"));
        assertThat(toss.confirms()).isEmpty();
    }

    @Test
    void 남의_주문번호로는_승인할_수_없다() throws Exception {
        long id = openEqual();
        String orderId = orderId(participate(aToken, id, 1));

        confirm(bToken, "pk-a", orderId, 3_333).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
        assertThat(toss.confirms()).isEmpty();
    }

    // ───── 멱등·실패·보상 ─────

    @Test
    void 같은_주문번호로_다시_승인을_요청하면_토스를_다시_부르지_않고_성공() throws Exception {
        long id = openEqual();
        String orderId = orderId(participate(aToken, id, 1));
        confirm(aToken, "pk-a", orderId, 3_333).andExpect(status().isOk());

        confirm(aToken, "pk-a", orderId, 3_333).andExpect(status().isOk())
                .andExpect(jsonPath("$.participationStatus").value("PAID"));

        assertThat(toss.confirms()).hasSize(1);
        assertThat(row(id)).containsEntry("paid_units", 2);   // 두 번 더하지 않음
    }

    @Test
    void 토스가_거절하면_결제_실패로_남기고_자리는_유지해_다시_결제할_수_있다() throws Exception {
        long id = openEqual();
        String orderId = orderId(participate(aToken, id, 1));
        toss.reject("REJECT_CARD_PAYMENT");

        confirm(aToken, "pk-a", orderId, 3_333).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_REJECTED"));

        assertThat(paymentOf(orderId)).containsEntry("status", "FAILED");
        assertThat(row(id)).containsEntry("reserved_units", 2).containsEntry("paid_units", 1);
        assertThat(toss.cancels()).isEmpty();   // 승인이 안 됐으니 취소할 것도 없음

        String retry = orderId(participate(aToken, id, 1));
        confirm(aToken, "pk-a2", retry, 3_333).andExpect(status().isOk());
    }

    @Test
    void 토스_응답을_못_받으면_혹시_승인됐을_결제를_취소하고_실패로_남긴다() throws Exception {
        long id = openEqual();
        String orderId = orderId(participate(aToken, id, 1));
        toss.unavailable();

        confirm(aToken, "pk-a", orderId, 3_333).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PAYMENT_UNAVAILABLE"));

        assertThat(toss.cancels()).containsExactly("pk-a");
        assertThat(paymentOf(orderId)).containsEntry("status", "FAILED");
        assertThat(row(id)).containsEntry("paid_units", 1);
    }

    @Test
    void 승인_뒤_DB_반영이_실패하면_토스_결제를_취소한다() throws Exception {
        long id = openEqual();
        String orderId = orderId(participate(aToken, id, 1));
        // 토스 승인과 우리 DB 반영 사이에 공구가 실패 처리된 상황 (예: 마감 스케줄러)
        toss.onConfirm(() -> jdbc.update("UPDATE group_buys SET status = 'FAILED', fail_reason = 'DEADLINE_UNMET' WHERE id = ?", id));

        confirm(aToken, "pk-a", orderId, 3_333).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_CANCELED"));

        assertThat(toss.cancels()).containsExactly("pk-a");
        assertThat(paymentOf(orderId)).containsEntry("status", "CANCELED").containsEntry("payment_key", "pk-a");
        assertThat(jdbc.queryForObject("SELECT status FROM participations WHERE is_host = FALSE", String.class))
                .isEqualTo("PENDING_PAYMENT");
        assertThat(row(id)).containsEntry("paid_units", 1);
    }

    @Test
    void 보상_취소마저_실패하면_수동_확인이_필요하다고_기록한다() throws Exception {
        long id = openEqual();
        String orderId = orderId(participate(aToken, id, 1));
        toss.onConfirm(() -> jdbc.update("UPDATE group_buys SET status = 'FAILED', fail_reason = 'DEADLINE_UNMET' WHERE id = ?", id));
        toss.failCancel();

        confirm(aToken, "pk-a", orderId, 3_333).andExpect(status().isInternalServerError());

        Map<String, Object> payment = paymentOf(orderId);
        assertThat(payment).containsEntry("status", "FAILED").containsEntry("payment_key", "pk-a");
        assertThat((String) payment.get("cancel_reason")).contains("수동 확인");
    }

    // ───── 상세에 내 참여 ─────

    @Test
    void 상세에서_내_참여_상태를_볼_수_있다() throws Exception {
        long id = openEqual();
        payFully(aToken, id, 1, "pk-a");

        mvc.perform(get("/api/group-buys/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + aToken))
                .andExpect(jsonPath("$.myParticipation.status").value("PAID"))
                .andExpect(jsonPath("$.myParticipation.units").value(1))
                .andExpect(jsonPath("$.myParticipation.amount").value(3_333));
        mvc.perform(get("/api/group-buys/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + bToken))
                .andExpect(jsonPath("$.myParticipation").doesNotExist());
    }

    // ───── 헬퍼 ─────

    /** 개설자 결제까지 끝난 모집 중 균등 공구 (3자리 중 1자리 결제됨). */
    private long openEqual() {
        long id = insertGroupBuy("OPEN", "EQUAL", 10_000, 3, 3, 1, 1, 1);
        insertHostParticipation(id, 1, 3_334, "PAID");
        return id;
    }

    private ResultActions payFully(String token, long id, int units, String paymentKey) throws Exception {
        MapAndAmount order = order(participate(token, id, units));
        return confirm(token, paymentKey, order.orderId(), order.amount()).andExpect(status().isOk());
    }

    private record MapAndAmount(String orderId, long amount) {
    }

    private MapAndAmount order(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new MapAndAmount(JsonPath.read(body, "$.orderId"), ((Number) JsonPath.read(body, "$.amount")).longValue());
    }

    private String orderId(ResultActions result) throws Exception {
        return order(result).orderId();
    }

    private ResultActions participate(String token, long id, int units) throws Exception {
        return mvc.perform(post("/api/group-buys/" + id + "/participations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"units\":" + units + "}"));
    }

    private ResultActions confirm(String token, String paymentKey, String orderId, long amount) throws Exception {
        return mvc.perform(post("/api/payments/confirm")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentKey\":\"%s\",\"orderId\":\"%s\",\"amount\":%d}".formatted(paymentKey, orderId, amount)));
    }

    private long insertGroupBuy(String status, String priceType, long totalPrice, int totalQuantity, int capacity,
                                int minUnits, int reserved, int paid) {
        jdbc.update("""
                INSERT INTO group_buys (host_id, title, description, category, product_url, price_type,
                    total_price, total_quantity, unit_label, capacity_units, min_units_per_person,
                    reserved_units, paid_units, status, location, neighborhood_name, deadline_at, created_at, updated_at)
                VALUES (?, '휴지 30롤', '반띵해요', 'LIVING', 'https://example.com/p', ?, ?, ?, '롤', ?, ?, ?, ?, ?,
                    ST_PointFromText(?, 4326), '중구 명동', ?, ?, ?)
                """, hostId, priceType, totalPrice, totalQuantity, capacity, minUnits, reserved, paid, status,
                new GeoPoint(37.5665, 126.978).toWkt(), NOW.plusDays(2), NOW, NOW);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertHostParticipation(long groupBuyId, int units, long amount, String status) {
        jdbc.update("""
                INSERT INTO participations (group_buy_id, user_id, is_host, units, amount, status, expires_at,
                    created_at, updated_at)
                VALUES (?, ?, TRUE, ?, ?, ?, ?, ?, ?)
                """, groupBuyId, hostId, units, amount, status, NOW.plusMinutes(10), NOW, NOW);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT status, reserved_units, paid_units FROM group_buys WHERE id = ?", id);
    }

    private Map<String, Object> paymentOf(String orderId) {
        return jdbc.queryForMap("SELECT status, payment_key, amount, cancel_reason FROM payments WHERE order_id = ?", orderId);
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
