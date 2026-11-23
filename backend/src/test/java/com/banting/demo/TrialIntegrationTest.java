package com.banting.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banting.TestcontainersConfiguration;
import com.banting.groupbuy.GroupBuyLifecycleService;
import com.banting.participation.ParticipationService;
import com.banting.payment.PaymentService;
import com.banting.support.DatabaseCleaner;
import com.banting.support.FakeTossPaymentsClient;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 체험 모드 (T16.5 완료 조건: 혼자서 참여·테스트 결제 → 자동 확정 → 투표 → 수령 → 정산, 실제 목록·통계와 격리).
 * 토스는 가짜. 봇 진행은 스케줄러 대신 {@link TrialBotService#step}을 직접 불러 한 걸음씩 확인한다.
 *
 * <p>시드 "3겹 화장지 30롤"(19,800원) → 체험은 균등 4명: 참여자 4,950원 × 3 + 개설자 4,950원.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TrialIntegrationTest.FakeTossConfig.class})
class TrialIntegrationTest {

    @TestConfiguration
    static class FakeTossConfig {
        @Bean
        @Primary
        FakeTossPaymentsClient fakeTossPaymentsClient() {
            return new FakeTossPaymentsClient();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    Clock clock;

    @Autowired
    FakeTossPaymentsClient toss;

    @Autowired
    TrialBotService bot;

    @Autowired
    ParticipationService participationService;

    @Autowired
    PaymentService paymentService;

    @Autowired
    GroupBuyLifecycleService lifecycle;

    private String visitorToken;
    private String otherToken;
    private long visitorId;
    private long seedId;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        toss.reset();
        new DemoSeeder(jdbc, tx, clock).seedOrRefresh();
        seedId = jdbc.queryForObject(
                "SELECT id FROM group_buys WHERE demo_type = 'SEED' AND title = '3겹 화장지 30롤'", Long.class);
        visitorToken = signupAndLogin("visitor@test.com", "방문자");
        otherToken = signupAndLogin("other@test.com", "다른사람");
        visitorId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'visitor@test.com'", Long.class);
    }

    // ───── 시작 ─────

    @Test
    void 체험을_시작하면_봇_개설자가_DEMO_결제로_모집_중_체험_공구를_연다() throws Exception {
        long id = startTrial(visitorToken).andExpect(status().isCreated())
                .andExpect(jsonPath("$.resumed").value(false))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8).transform(TrialIntegrationTest::id);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM group_buys WHERE id = ?", id);
        assertThat(row).containsEntry("demo_type", "TRIAL").containsEntry("status", "OPEN")
                .containsEntry("trial_user_id", visitorId).containsEntry("price_type", "EQUAL")
                .containsEntry("capacity_units", 4).containsEntry("reserved_units", 1).containsEntry("paid_units", 1)
                .containsEntry("title", "3겹 화장지 30롤").containsEntry("total_price", 19_800L);
        assertThat(jdbc.queryForObject("SELECT method FROM payments", String.class)).isEqualTo("DEMO");
        assertThat(toss.confirms()).isEmpty();   // 봇 결제는 토스를 부르지 않는다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pickup_options WHERE group_buy_id = ?", Integer.class, id))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT url FROM group_buy_images WHERE group_buy_id = ?", String.class, id))
                .isEqualTo("demo/tissue.webp");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pickup_votes WHERE group_buy_id = ?", Integer.class, id))
                .isEqualTo(1);   // 봇 개설자가 첫 후보에 투표
    }

    @Test
    void 체험_공구는_목록에_나오지_않고_만든_방문자에게만_보인다() throws Exception {
        long id = startedTrialId();

        mvc.perform(get("/api/group-buys").param("demoArea", "true"))
                .andExpect(jsonPath("$.items[?(@.id == " + id + ")]").isEmpty());
        mvc.perform(get("/api/group-buys/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + visitorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trial").value(true))
                .andExpect(jsonPath("$.demo").value(false));
        mvc.perform(get("/api/group-buys/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/group-buys/" + id)).andExpect(status().isNotFound());
        participate(otherToken, id).andExpect(status().isNotFound());
    }

    @Test
    void 진행_중인_체험이_있으면_새로_만들지_않고_그_체험으로_보낸다() throws Exception {
        long first = startedTrialId();

        startTrial(visitorToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.resumed").value(true))
                .andExpect(jsonPath("$.groupBuyId").value(first));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM group_buys WHERE demo_type = 'TRIAL'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void 체험은_하루_5번까지() throws Exception {
        for (int i = 0; i < TrialService.DAILY_LIMIT; i++) {
            long id = startedTrialId();
            jdbc.update("UPDATE group_buys SET status = 'FAILED', fail_reason = 'DEADLINE_UNMET' WHERE id = ?", id);
        }

        startTrial(visitorToken).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TRIAL_DAILY_LIMIT"));
        startTrial(otherToken).andExpect(status().isCreated());   // 회원마다 따로 센다
    }

    @Test
    void 예시_공구가_아니면_체험을_만들_수_없고_로그인이_필요하다() throws Exception {
        long trialId = startedTrialId();

        startTrial(otherToken, trialId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIAL_SOURCE_NOT_FOUND"));
        mvc.perform(post("/api/trials").contentType(MediaType.APPLICATION_JSON)
                .content("{\"seedGroupBuyId\":" + seedId + "}")).andExpect(status().isUnauthorized());
    }

    @Test
    void 내가_만든_체험은_내_공구에_체험으로_보인다() throws Exception {
        long id = startedTrialId();
        participate(visitorToken, id).andExpect(status().isCreated());

        mvc.perform(get("/api/users/me/group-buys").header(HttpHeaders.AUTHORIZATION, "Bearer " + visitorToken))
                .andExpect(jsonPath("$.active[0].groupBuyId").value(id))
                .andExpect(jsonPath("$.active[0].trial").value(true))
                .andExpect(jsonPath("$.active[0].todo").value("PAY"));
        mvc.perform(get("/api/users/me/group-buys").header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(jsonPath("$.active.length()").value(0));
    }

    @Test
    void 예시_공구에는_여전히_참여할_수_없다() throws Exception {
        participate(visitorToken, seedId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEMO_GROUP_BUY"));
    }

    // ───── 봇 진행 ─────

    @Test
    void 혼자서_참여_결제부터_확정_투표_수령_정산까지_끝까지_간다() throws Exception {
        long id = startedTrialId();
        assertThat(bot.step(id)).isFalse();   // 방문자 결제 전에는 봇이 자리를 채우지 않는다

        // 방문자: 일반 참여 API + 토스 테스트 결제
        String body = participate(visitorToken, id).andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(4_950))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String orderId = JsonPath.read(body, "$.orderId");
        confirm(visitorToken, "pk-visitor", orderId, 4_950).andExpect(status().isOk())
                .andExpect(jsonPath("$.groupBuyStatus").value("OPEN"));
        long optionTwo = jdbc.queryForObject(
                "SELECT id FROM pickup_options WHERE group_buy_id = ? ORDER BY pickup_at DESC LIMIT 1", Long.class, id);
        vote(visitorToken, id, optionTwo).andExpect(status().isOk());

        assertThat(bot.step(id)).isTrue();    // 이웃 봇 1: 참여 + DEMO 결제 + 투표(두 번째 후보)
        assertThat(statusOf(id)).isEqualTo("OPEN");
        assertThat(bot.step(id)).isTrue();    // 이웃 봇 2: 마지막 자리 → 기존 로직대로 확정
        assertThat(statusOf(id)).isEqualTo("CONFIRMED");
        // 표: 개설자 봇 → 첫 후보, 방문자·봇 1 → 두 번째 후보 → 두 번째 후보가 수령 일정
        assertThat(jdbc.queryForObject("SELECT id FROM pickup_options WHERE group_buy_id = ? AND selected = TRUE",
                Long.class, id)).isEqualTo(optionTwo);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND type = 'GROUP_BUY_CONFIRMED'",
                Integer.class, visitorId)).isEqualTo(1);

        assertThat(bot.step(id)).isTrue();    // 봇 개설자: 구매했어요
        assertThat(statusOf(id)).isEqualTo("PICKUP");
        assertThat(bot.step(id)).isTrue();    // 봇 1 받았어요
        assertThat(bot.step(id)).isTrue();    // 봇 2 받았어요
        assertThat(bot.step(id)).isFalse();   // 방문자 차례
        assertThat(statusOf(id)).isEqualTo("PICKUP");

        long visitorPart = jdbc.queryForObject(
                "SELECT id FROM participations WHERE group_buy_id = ? AND user_id = ?", Long.class, id, visitorId);
        mvc.perform(post("/api/participations/" + visitorPart + "/receive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + visitorToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groupBuyCompleted").value(true));

        assertThat(statusOf(id)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForMap("SELECT gross_amount, fee_amount, net_amount FROM settlements WHERE group_buy_id = ?", id))
                .containsEntry("gross_amount", 19_800L).containsEntry("fee_amount", 594L).containsEntry("net_amount", 19_206L);
        assertThat(jdbc.queryForList("SELECT method FROM payments WHERE status = 'DONE' ORDER BY id", String.class))
                .containsExactly("DEMO", "TOSS", "DEMO", "DEMO");
        assertThat(toss.confirms()).hasSize(1);   // 토스는 방문자 몫 한 번만
        // 체험한 방문자에게도 정산 결과를 보여 준다 (실제 공구에선 개설자만)
        mvc.perform(get("/api/group-buys/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + visitorToken))
                .andExpect(jsonPath("$.settlement.netAmount").value(19_206))
                .andExpect(jsonPath("$.settlement.feeAmount").value(594));
        assertThat(bot.step(id)).isFalse();       // 끝난 체험은 더 진행하지 않음
    }

    @Test
    void 방문자가_결제하지_않으면_마감_미달로_실패하고_봇_결제는_토스_없이_환불된다() throws Exception {
        long id = startedTrialId();
        expireDeadline(id);

        assertThat(lifecycle.failOverdueGroupBuys()).isEqualTo(1);

        assertThat(jdbc.queryForMap("SELECT status, fail_reason FROM group_buys WHERE id = ?", id))
                .containsEntry("status", "FAILED").containsEntry("fail_reason", "DEADLINE_UNMET");
        assertThat(jdbc.queryForObject("SELECT status FROM participations WHERE group_buy_id = ?", String.class, id))
                .isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT status FROM payments", String.class)).isEqualTo("CANCELED");
        assertThat(toss.cancels()).isEmpty();
        // 끝난 체험은 진행 중이 아니므로 새 체험을 시작할 수 있다
        startTrial(visitorToken).andExpect(status().isCreated());
    }

    @Test
    void 결제한_뒤_마감_미달이면_방문자_몫만_토스로_환불된다() throws Exception {
        long id = startedTrialId();
        String body = participate(visitorToken, id).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        confirm(visitorToken, "pk-visitor", JsonPath.read(body, "$.orderId"), 4_950).andExpect(status().isOk());
        assertThat(bot.step(id)).isTrue();   // 봇 1까지만 들어오고 마감
        expireDeadline(id);

        lifecycle.failOverdueGroupBuys();

        assertThat(jdbc.queryForList("SELECT status FROM participations WHERE group_buy_id = ?", String.class, id))
                .containsOnly("REFUNDED").hasSize(3);
        assertThat(toss.cancels()).hasSize(1);   // 방문자 결제만 토스 취소, 봇 2건은 기록만
    }

    // ───── DEMO 결제 격리 ─────

    @Test
    void DEMO_결제와_봇_참여는_실제_공구에서_거부된다() throws Exception {
        long id = startedTrialId();
        // 체험 공구를 실제 공구로 바꿔 놓고 봇 경로를 부른다 (어떤 경로로도 토스 없는 결제가 실제 공구에 들어가면 안 됨)
        String body = participate(visitorToken, id).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String orderId = JsonPath.read(body, "$.orderId");
        jdbc.update("UPDATE group_buys SET demo_type = NULL, trial_user_id = NULL WHERE id = ?", id);

        assertThatThrownBy(() -> paymentService.confirmDemo(visitorId, orderId)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> participationService.participateAsTrialBot(visitorId, id, 1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class, orderId))
                .isEqualTo("READY");
    }

    @Test
    void 체험_공구가_아닌데_체험_방문자가_있으면_CHECK_제약에_걸린다() {
        long id = startedTrialIdQuietly();
        assertThatThrownBy(() -> jdbc.update("UPDATE group_buys SET demo_type = NULL WHERE id = ?", id))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE group_buys SET trial_user_id = NULL WHERE id = ?", id))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE group_buys SET trial_user_id = ? WHERE id = ?", visitorId, seedId))
                .isInstanceOf(Exception.class);
    }

    // ───── 도우미 ─────

    private long startedTrialIdQuietly() {
        try {
            return startedTrialId();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long startedTrialId() throws Exception {
        return id(startTrial(visitorToken).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static long id(String body) {
        return ((Number) JsonPath.read(body, "$.groupBuyId")).longValue();
    }

    private ResultActions startTrial(String token) throws Exception {
        return startTrial(token, seedId);
    }

    private ResultActions startTrial(String token, long seed) throws Exception {
        return mvc.perform(post("/api/trials")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seedGroupBuyId\":" + seed + "}"));
    }

    private ResultActions participate(String token, long id) throws Exception {
        return mvc.perform(post("/api/group-buys/" + id + "/participations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"units\":1}"));
    }

    private ResultActions confirm(String token, String paymentKey, String orderId, long amount) throws Exception {
        return mvc.perform(post("/api/payments/confirm")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentKey\":\"%s\",\"orderId\":\"%s\",\"amount\":%d}".formatted(paymentKey, orderId, amount)));
    }

    private ResultActions vote(String token, long id, long optionId) throws Exception {
        return mvc.perform(post("/api/group-buys/" + id + "/pickup-votes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"optionId\":" + optionId + "}"));
    }

    /** 마감과 결제 대기를 스케줄러 여유 시간(2분)보다 더 과거로. */
    private void expireDeadline(long id) {
        jdbc.update("UPDATE group_buys SET deadline_at = DATE_SUB(deadline_at, INTERVAL 20 MINUTE) WHERE id = ?", id);
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM group_buys WHERE id = ?", String.class, id);
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
