package com.banting.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banting.TestcontainersConfiguration;
import com.banting.common.geo.GeoPoint;
import com.banting.groupbuy.GroupBuyCompletionService;
import com.banting.groupbuy.GroupBuyLifecycleService;
import com.banting.support.DatabaseCleaner;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

/**
 * 구매 → 수령 확인 → 완료·정산 (T16 완료 조건: 전원 확인 또는 기한 경과 시 정산 기록).
 *
 * <p>기본 공구: 균등 3명, 30,000원 → 1인 10,000원. 확정됨(개설자·A·B 결제 완료), 수령 일정은 5일 뒤.
 * 수수료 3% → 정산 총액 30,000원, 수수료 900원, 정산액 29,100원.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReceiveSettlementIntegrationTest {

    private static final LocalDateTime NOW =
            LocalDateTime.now(ZoneId.of("Asia/Seoul")).truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    GroupBuyLifecycleService lifecycle;

    @Autowired
    GroupBuyCompletionService completionService;

    private long hostId;
    private long aId;
    private long bId;
    private String hostToken;
    private String aToken;
    private String bToken;
    private long id;
    private long hostPart;
    private long aPart;
    private long bPart;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        hostToken = signupAndLogin("host@test.com", "개설자");
        aToken = signupAndLogin("a@test.com", "이웃A");
        bToken = signupAndLogin("b@test.com", "이웃B");
        hostId = userId("host@test.com");
        aId = userId("a@test.com");
        bId = userId("b@test.com");
        id = insertGroupBuy("CONFIRMED");
        hostPart = insertParticipation(id, hostId, true);
        aPart = insertParticipation(id, aId, false);
        bPart = insertParticipation(id, bId, false);
        insertSelectedPickup(id, NOW.plusDays(5));
    }

    // ───── 구매했어요 ─────

    @Test
    void 개설자가_구매했어요를_누르면_수령_대기가_되고_참여자에게_알린다() throws Exception {
        purchased(hostToken).andExpect(status().isNoContent());

        assertThat(groupBuyStatus()).isEqualTo("PICKUP");
        assertThat(notified("GROUP_BUY_PURCHASED")).containsExactlyInAnyOrder(aId, bId);   // 개설자 본인은 제외
    }

    @Test
    void 개설자가_아니면_구매했어요를_누를_수_없다() throws Exception {
        assertThat(purchased(aToken).andReturn().getResponse().getStatus()).isBetween(400, 499);
        assertThat(groupBuyStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void 확정되지_않은_공구는_구매했어요를_누를_수_없다() throws Exception {
        jdbc.update("UPDATE group_buys SET status = 'OPEN' WHERE id = ?", id);

        purchased(hostToken).andExpect(status().isConflict());
    }

    // ───── 받았어요 ─────

    @Test
    void 개설자가_사기_전에는_받았어요를_누를_수_없다() throws Exception {
        receive(aToken, aPart).andExpect(status().isConflict());
        assertThat(participationStatus(aPart)).isEqualTo("PAID");
    }

    @Test
    void 참여자_전원이_받으면_완료되고_수수료를_뗀_정산이_기록된다() throws Exception {
        purchased(hostToken);

        receive(aToken, aPart).andExpect(status().isOk()).andExpect(jsonPath("$.groupBuyCompleted").value(false));
        assertThat(groupBuyStatus()).isEqualTo("PICKUP");
        assertThat(count("settlements")).isZero();

        receive(bToken, bPart).andExpect(status().isOk()).andExpect(jsonPath("$.groupBuyCompleted").value(true));

        assertThat(groupBuyStatus()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT completed_at IS NOT NULL FROM group_buys WHERE id = ?", Boolean.class, id))
                .isTrue();
        assertThat(participationStatus(aPart)).isEqualTo("RECEIVED");
        assertThat(settlement())
                .containsEntry("host_id", hostId)
                .containsEntry("gross_amount", 30_000L)
                .containsEntry("fee_amount", 900L)
                .containsEntry("net_amount", 29_100L)
                .containsEntry("status", "PENDING");
        assertThat((BigDecimal) settlement().get("fee_rate")).isEqualByComparingTo("0.0300");
        assertThat(notified("GROUP_BUY_COMPLETED")).containsExactly(hostId);
        assertThat(jdbc.queryForObject("SELECT message FROM notifications WHERE type = 'GROUP_BUY_COMPLETED'",
                String.class)).contains("29,100원").contains("900원");
    }

    @Test
    void 받았어요를_두_번_눌러도_그대로() throws Exception {
        purchased(hostToken);
        receive(aToken, aPart).andExpect(status().isOk());
        receive(aToken, aPart).andExpect(status().isOk()).andExpect(jsonPath("$.groupBuyCompleted").value(false));

        assertThat(participationStatus(aPart)).isEqualTo("RECEIVED");
    }

    @Test
    void 남의_참여나_개설자_몫은_받았어요를_누를_수_없다() throws Exception {
        purchased(hostToken);

        receive(bToken, aPart).andExpect(status().isNotFound());         // 남의 참여
        receive(hostToken, hostPart).andExpect(status().isConflict());   // 개설자 몫은 수령 대상 아님
    }

    // ───── 수령 일정 + 3일 경과 자동 완료 ─────

    @Test
    void 수령_일정_3일이_지나면_확인_안_한_사람이_있어도_완료_정산된다() throws Exception {
        purchased(hostToken);
        receive(aToken, aPart);   // B는 안 누름
        movePickupTo(NOW.minusDays(3).minusHours(1));

        assertThat(lifecycle.completeOverduePickups()).isEqualTo(1);

        assertThat(groupBuyStatus()).isEqualTo("COMPLETED");
        assertThat(participationStatus(bPart)).isEqualTo("PAID");   // 확인 안 한 기록은 그대로 (수령 확인율용)
        assertThat(settlement()).containsEntry("gross_amount", 30_000L).containsEntry("net_amount", 29_100L);
    }

    @Test
    void 수령_일정_3일_전이면_자동_완료하지_않는다() throws Exception {
        purchased(hostToken);
        movePickupTo(NOW.minusDays(2));   // 수령일은 지났지만 3일은 안 됨

        assertThat(lifecycle.completeOverduePickups()).isZero();
        assertThat(groupBuyStatus()).isEqualTo("PICKUP");
    }

    @Test
    void 개설자가_사지_않은_공구는_기한이_지나도_자동_완료하지_않는다() {
        movePickupTo(NOW.minusDays(10));   // CONFIRMED 그대로

        assertThat(lifecycle.completeOverduePickups()).isZero();
        assertThat(count("settlements")).isZero();
    }

    @Test
    void 자동_완료를_두_번_돌려도_정산은_한_건() throws Exception {
        purchased(hostToken);
        movePickupTo(NOW.minusDays(4));

        lifecycle.completeOverduePickups();
        assertThat(lifecycle.completeOverduePickups()).isZero();

        assertThat(count("settlements")).isEqualTo(1);
    }

    @Test
    void 자동_완료된_뒤에도_받았어요는_기록된다() throws Exception {
        purchased(hostToken);
        movePickupTo(NOW.minusDays(4));
        lifecycle.completeOverduePickups();

        receive(aToken, aPart).andExpect(status().isOk()).andExpect(jsonPath("$.groupBuyCompleted").value(false));

        assertThat(participationStatus(aPart)).isEqualTo("RECEIVED");
        assertThat(count("settlements")).isEqualTo(1);
    }

    @Test
    void 시드_공구는_자동_완료하지_않는다() throws Exception {
        purchased(hostToken);
        jdbc.update("UPDATE group_buys SET demo_type = 'SEED' WHERE id = ?", id);
        movePickupTo(NOW.minusDays(4));

        assertThat(lifecycle.completeOverduePickups()).isZero();
    }

    @Test
    void 마지막_받았어요와_자동_완료가_동시에_와도_정산은_한_건() throws Exception {
        purchased(hostToken);
        receive(aToken, aPart);
        movePickupTo(NOW.minusDays(4));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Boolean> byReceive = pool.submit(() -> {
            start.await();
            return completionService.receive(bId, bPart);
        });
        Future<Integer> byScheduler = pool.submit(() -> {
            start.await();
            return lifecycle.completeOverduePickups();
        });
        start.countDown();
        boolean receiveCompleted = byReceive.get(30, TimeUnit.SECONDS);
        int schedulerCompleted = byScheduler.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat((receiveCompleted ? 1 : 0) + schedulerCompleted).isEqualTo(1);   // 둘 중 하나만 완료시킴
        assertThat(count("settlements")).isEqualTo(1);
        assertThat(participationStatus(bPart)).isEqualTo("RECEIVED");   // 늦게 온 받았어요도 기록
        assertThat(groupBuyStatus()).isEqualTo("COMPLETED");
    }

    // ───── 상세 화면 ─────

    @Test
    void 정산_정보는_개설자에게만_보인다() throws Exception {
        purchased(hostToken);
        receive(aToken, aPart);
        receive(bToken, bPart);

        mvc.perform(get("/api/group-buys/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + hostToken))
                .andExpect(jsonPath("$.settlement.netAmount").value(29_100))
                .andExpect(jsonPath("$.settlement.feeAmount").value(900));
        mvc.perform(get("/api/group-buys/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + aToken))
                .andExpect(jsonPath("$.settlement").doesNotExist());
    }

    // ───── 헬퍼 ─────

    private ResultActions purchased(String token) throws Exception {
        return mvc.perform(post("/api/group-buys/" + id + "/purchased")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions receive(String token, long participationId) throws Exception {
        return mvc.perform(post("/api/participations/" + participationId + "/receive")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private void movePickupTo(LocalDateTime at) {
        jdbc.update("UPDATE pickup_options SET pickup_at = ? WHERE group_buy_id = ? AND selected = TRUE", at, id);
    }

    private String groupBuyStatus() {
        return jdbc.queryForObject("SELECT status FROM group_buys WHERE id = ?", String.class, id);
    }

    private String participationStatus(long participationId) {
        return jdbc.queryForObject("SELECT status FROM participations WHERE id = ?", String.class, participationId);
    }

    private Map<String, Object> settlement() {
        return jdbc.queryForMap("SELECT * FROM settlements WHERE group_buy_id = ?", id);
    }

    private List<Long> notified(String type) {
        return jdbc.queryForList("SELECT user_id FROM notifications WHERE type = ? AND group_buy_id = ?",
                Long.class, type, id);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private long insertGroupBuy(String status) {
        jdbc.update("""
                INSERT INTO group_buys (host_id, title, description, category, product_url, price_type,
                    total_price, total_quantity, unit_label, capacity_units, min_units_per_person,
                    reserved_units, paid_units, status, location, neighborhood_name, deadline_at, confirmed_at,
                    created_at, updated_at)
                VALUES (?, '휴지 30롤', '반띵해요', 'LIVING', 'https://example.com/p', 'EQUAL', 30000, 30, '롤', 3, 1,
                    3, 3, ?, ST_PointFromText(?, 4326), '중구 명동', ?, ?, ?, ?)
                """, hostId, status, new GeoPoint(37.5665, 126.978).toWkt(), NOW.plusHours(1), NOW, NOW, NOW);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertParticipation(long groupBuyId, long userId, boolean host) {
        jdbc.update("""
                INSERT INTO participations (group_buy_id, user_id, is_host, units, amount, status, expires_at,
                    created_at, updated_at)
                VALUES (?, ?, ?, 1, 10000, 'PAID', ?, ?, ?)
                """, groupBuyId, userId, host, NOW, NOW, NOW);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertSelectedPickup(long groupBuyId, LocalDateTime at) {
        jdbc.update("INSERT INTO pickup_options (group_buy_id, pickup_at, place_name, selected, created_at, updated_at) "
                + "VALUES (?, ?, '편의점 앞', TRUE, ?, ?)", groupBuyId, at, NOW, NOW);
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
