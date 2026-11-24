package com.banting.groupbuy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banting.TestcontainersConfiguration;
import com.banting.common.geo.GeoPoint;
import com.banting.support.DatabaseCleaner;
import com.banting.support.FakeImageStorage;
import com.banting.support.FakeTossPaymentsClient;
import com.banting.user.auth.RefreshTokenCleaner;
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
 * 스케줄러 (T14 완료 조건: 중간에 멈춰도 다시 돌면 이어서 처리, 멱등). 토스·사진 저장소는 가짜.
 *
 * <p>시간은 Clock을 바꾸지 않고, 지난 시각(만료·마감)을 가진 행을 넣은 뒤 서비스 메서드를 직접 부른다.
 * 스케줄러 자체(@Scheduled)는 테스트에서 꺼져 있다 (banting.scheduler.enabled 기본 false).
 * 기본 공구: 균등 4명, 4,000원 → 1자리 1,000원.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupBuyLifecycleIntegrationTest.FakeConfig.class})
class GroupBuyLifecycleIntegrationTest {

    @TestConfiguration
    static class FakeConfig {
        @Bean
        @Primary
        FakeTossPaymentsClient fakeTossPaymentsClient() {
            return new FakeTossPaymentsClient();
        }

        @Bean
        @Primary
        FakeImageStorage fakeImageStorage() {
            return new FakeImageStorage();
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
    FakeImageStorage storage;

    @Autowired
    GroupBuyLifecycleService lifecycle;

    @Autowired
    RefreshTokenCleaner refreshTokenCleaner;

    private long hostId;
    private String hostToken;
    private long aId;
    private long bId;
    private String aToken;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        toss.reset();
        storage.reset();
        hostToken = signupAndLogin("host@test.com", "개설자");
        aToken = signupAndLogin("a@test.com", "이웃A");
        signupAndLogin("b@test.com", "이웃B");
        hostId = userId("host@test.com");
        aId = userId("a@test.com");
        bId = userId("b@test.com");
    }

    // ───── ① 결제 대기 만료 ─────

    @Test
    void 만료된_결제_대기는_취소되고_자리가_반환된다() {
        long id = openGroupBuy(NOW.plusDays(1), 3);   // 개설자 1 + A 대기 + B 대기
        long a = insertParticipation(id, aId, "PENDING_PAYMENT", NOW.minusMinutes(5));
        insertPayment(a, "READY", null);
        long b = insertParticipation(id, bId, "PENDING_PAYMENT", NOW.plusMinutes(5));   // 아직 대기 중

        assertThat(lifecycle.expirePendingPayments()).isEqualTo(1);

        assertThat(participationStatus(a)).isEqualTo("CANCELED");
        assertThat(participationStatus(b)).isEqualTo("PENDING_PAYMENT");
        assertThat(groupBuy(id)).containsEntry("reserved_units", 2).containsEntry("status", "OPEN");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE participation_id = ?", String.class, a))
                .isEqualTo("FAILED");   // 예전 주문번호로 나중에 승인되지 않게
    }

    @Test
    void 만료_직후_여유_시간_안에는_진행_중인_결제_승인을_위해_기다린다() {
        long id = openGroupBuy(NOW.plusDays(1), 2);
        long a = insertParticipation(id, aId, "PENDING_PAYMENT", NOW.minusSeconds(30));

        assertThat(lifecycle.expirePendingPayments()).isZero();
        assertThat(participationStatus(a)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void 만료_처리는_두_번_돌려도_자리를_두_번_반환하지_않는다() {
        long id = openGroupBuy(NOW.plusDays(1), 2);
        insertParticipation(id, aId, "PENDING_PAYMENT", NOW.minusMinutes(5));

        lifecycle.expirePendingPayments();
        assertThat(lifecycle.expirePendingPayments()).isZero();

        assertThat(groupBuy(id)).containsEntry("reserved_units", 1);
    }

    // ───── ② 개설 대기(DRAFT) 만료 ─────

    @Test
    void 개설자가_결제하지_않고_만료된_공구는_사진까지_삭제된다() {
        long id = draftGroupBuy(NOW.minusMinutes(5));
        long host = hostParticipationId(id);
        insertPayment(host, "READY", null);
        insertPayment(host, "FAILED", null);   // 카드 거절 후 다시 시도했던 기록
        jdbc.update("INSERT INTO group_buy_images (group_buy_id, url, sort_order, created_at, updated_at) "
                + "VALUES (?, 'images/group-buys/a.webp', 0, ?, ?)", id, NOW, NOW);
        jdbc.update("INSERT INTO pickup_options (group_buy_id, pickup_at, place_name, created_at, updated_at) "
                + "VALUES (?, ?, '편의점 앞', ?, ?)", id, NOW.plusDays(3), NOW, NOW);

        assertThat(lifecycle.expireDrafts()).isEqualTo(1);

        assertThat(count("group_buys")).isZero();
        assertThat(count("participations")).isZero();
        assertThat(count("payments")).isZero();
        assertThat(count("pickup_options")).isZero();
        assertThat(storage.deleted()).containsExactly("images/group-buys/a.webp");
    }

    @Test
    void 돈이_오간_기록이_있는_DRAFT는_지우지_않고_남긴다() {
        // 승인 후 반영 실패 → 보상 취소된 결제 (T11). 돈이 오간 기록이라 삭제하면 추적 불가
        long id = draftGroupBuy(NOW.minusMinutes(5));
        insertPayment(hostParticipationId(id), "CANCELED", "pk-compensated");

        assertThat(lifecycle.expireDrafts()).isZero();
        assertThat(count("group_buys")).isEqualTo(1);
        assertThat(count("payments")).isEqualTo(1);
    }

    @Test
    void 아직_결제_시간이_남은_DRAFT는_그대로() {
        draftGroupBuy(NOW.plusMinutes(5));

        assertThat(lifecycle.expireDrafts()).isZero();
        assertThat(count("group_buys")).isEqualTo(1);
    }

    @Test
    void 결제창을_연_뒤_DRAFT를_직접_삭제해도_결제_대기_주문까지_지워진다() throws Exception {
        // T08 삭제 코드가 T11에서 생긴 payments 행을 몰라 FK(RESTRICT)에 걸리던 문제
        long id = draftGroupBuy(NOW.plusMinutes(5));
        mvc.perform(post("/api/group-buys/" + id + "/participations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + hostToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"units\":1}"))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/group-buys/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + hostToken))
                .andExpect(status().isNoContent());

        assertThat(count("group_buys")).isZero();
        assertThat(count("payments")).isZero();
    }

    // ───── ③ 마감 미달 실패 + 환불 ─────

    @Test
    void 마감이_지난_미달_공구는_실패_처리되고_결제한_사람은_전원_환불된다() {
        long id = openGroupBuy(NOW.minusMinutes(5), 3);
        long host = hostParticipationId(id);
        insertPayment(host, "DONE", "pk-host");
        long a = insertParticipation(id, aId, "PAID", NOW.minusMinutes(30));
        insertPayment(a, "DONE", "pk-a");
        long b = insertParticipation(id, bId, "PENDING_PAYMENT", NOW.plusMinutes(5));
        insertPayment(b, "READY", null);

        assertThat(lifecycle.failOverdueGroupBuys()).isEqualTo(1);

        assertThat(groupBuy(id)).containsEntry("status", "FAILED").containsEntry("fail_reason", "DEADLINE_UNMET");
        assertThat(participationStatus(host)).isEqualTo("REFUNDED");   // 개설자도 환불
        assertThat(participationStatus(a)).isEqualTo("REFUNDED");
        assertThat(participationStatus(b)).isEqualTo("CANCELED");      // 결제 전이라 돈이 안 나감
        assertThat(toss.cancels()).containsExactlyInAnyOrder("pk-host", "pk-a");
        assertThat(jdbc.queryForList("SELECT status FROM payments WHERE payment_key IS NOT NULL", String.class))
                .containsOnly("CANCELED");
    }

    @Test
    void 확정된_공구와_마감_전_공구와_시드_공구는_건드리지_않는다() {
        long confirmed = openGroupBuy(NOW.minusMinutes(5), 1);
        jdbc.update("UPDATE group_buys SET status = 'CONFIRMED', reserved_units = 4, paid_units = 4 WHERE id = ?",
                confirmed);
        long notYet = openGroupBuy(NOW.plusMinutes(10), 1);
        long seed = openGroupBuy(NOW.minusMinutes(5), 1);
        jdbc.update("UPDATE group_buys SET demo_type = 'SEED' WHERE id = ?", seed);

        assertThat(lifecycle.failOverdueGroupBuys()).isZero();

        assertThat(groupBuy(confirmed)).containsEntry("status", "CONFIRMED");
        assertThat(groupBuy(notYet)).containsEntry("status", "OPEN");
        assertThat(groupBuy(seed)).containsEntry("status", "OPEN");
    }

    @Test
    void 환불이_실패하면_환불_대기로_남고_재시도에서_마무리된다() {
        long id = openGroupBuy(NOW.minusMinutes(5), 1);
        long host = hostParticipationId(id);
        insertPayment(host, "DONE", "pk-host");
        toss.failCancel();

        lifecycle.failOverdueGroupBuys();
        assertThat(groupBuy(id)).containsEntry("status", "FAILED");
        assertThat(participationStatus(host)).isEqualTo("REFUND_PENDING");

        toss.cancelWorks();
        assertThat(lifecycle.retryRefunds()).isEqualTo(1);
        assertThat(participationStatus(host)).isEqualTo("REFUNDED");
    }

    @Test
    void 다시_돌려도_이중_환불되지_않는다() {
        long id = openGroupBuy(NOW.minusMinutes(5), 1);
        insertPayment(hostParticipationId(id), "DONE", "pk-host");

        lifecycle.failOverdueGroupBuys();
        lifecycle.failOverdueGroupBuys();
        lifecycle.retryRefunds();

        assertThat(toss.cancels()).containsExactly("pk-host");
    }

    @Test
    void 실패_처리_사이에_도착한_결제_승인은_보상_취소된다() throws Exception {
        long id = openGroupBuy(NOW.plusMinutes(10), 1);
        String body = mvc.perform(post("/api/group-buys/" + id + "/participations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"units\":1}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String orderId = JsonPath.read(body, "$.orderId");

        // 토스 승인은 됐지만 우리 DB에 반영하기 직전에 마감이 지나 스케줄러가 실패 처리
        toss.onConfirm(() -> {
            jdbc.update("UPDATE group_buys SET deadline_at = ? WHERE id = ?", NOW.minusMinutes(5), id);
            lifecycle.failOverdueGroupBuys();
        });
        confirm(aToken, "pk-late", orderId, 1_000).andExpect(status().isConflict());

        assertThat(groupBuy(id)).containsEntry("status", "FAILED");
        assertThat(toss.cancels()).containsExactly("pk-late");   // 돈은 돌려줌
        assertThat(jdbc.queryForObject("SELECT status FROM participations WHERE user_id = ?", String.class, aId))
                .isEqualTo("CANCELED");
        // 실제로는 여유 2분 덕분에 여기까지 오지 않는다 (승인 요청은 최대 33초). 이 테스트는 그래도 돈이 지켜지는지 확인
    }

    @Test
    void 마감_직후_여유_시간_안에는_실패_처리하지_않는다() {
        long id = openGroupBuy(NOW.minusSeconds(30), 1);

        assertThat(lifecycle.failOverdueGroupBuys()).isZero();
        assertThat(groupBuy(id)).containsEntry("status", "OPEN");
    }

    // ───── ⑤ refresh 토큰 정리 ─────

    @Test
    void 만료된_refresh_토큰만_지우고_폐기됐어도_만료_전이면_탈취_감지용으로_남긴다() {
        jdbc.update("DELETE FROM refresh_tokens");
        insertRefreshToken("a".repeat(64), NOW.minusDays(1), null);             // 만료 → 삭제
        insertRefreshToken("b".repeat(64), NOW.minusDays(1), NOW.minusDays(3)); // 폐기 + 만료 → 삭제
        insertRefreshToken("c".repeat(64), NOW.plusDays(5), NOW.minusHours(1)); // 폐기, 만료 전 → 남김
        insertRefreshToken("d".repeat(64), NOW.plusDays(5), null);              // 살아 있음 → 남김

        assertThat(refreshTokenCleaner.deleteExpired()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT LEFT(token_hash, 1) FROM refresh_tokens ORDER BY token_hash",
                String.class)).containsExactly("c", "d");
    }

    // ───── 헬퍼 ─────

    /** 모집 중 공구 + 개설자 결제 완료(1자리). reserved는 개설자 + 이후 넣을 참여자 수. */
    private long openGroupBuy(LocalDateTime deadline, int reserved) {
        long id = insertGroupBuy("OPEN", deadline, reserved, 1);
        insertHostParticipation(id, "PAID", NOW.minusHours(1));
        return id;
    }

    /** 개설 대기 공구 + 개설자 결제 대기(만료 시각 지정). */
    private long draftGroupBuy(LocalDateTime hostExpiresAt) {
        long id = insertGroupBuy("DRAFT", NOW.plusDays(2), 1, 0);
        insertHostParticipation(id, "PENDING_PAYMENT", hostExpiresAt);
        return id;
    }

    private long insertGroupBuy(String status, LocalDateTime deadline, int reserved, int paid) {
        jdbc.update("""
                INSERT INTO group_buys (host_id, title, description, category, product_url, price_type,
                    total_price, total_quantity, unit_label, capacity_units, min_units_per_person,
                    reserved_units, paid_units, status, location, neighborhood_name, deadline_at, created_at, updated_at)
                VALUES (?, '휴지 30롤', '반띵해요', 'LIVING', 'https://example.com/p', 'EQUAL', 4000, 4, '롤', 4, 1,
                    ?, ?, ?, ST_PointFromText(?, 4326), '중구 명동', ?, ?, ?)
                """, hostId, reserved, paid, status, new GeoPoint(37.5665, 126.978).toWkt(), deadline, NOW, NOW);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertHostParticipation(long groupBuyId, String status, LocalDateTime expiresAt) {
        jdbc.update("""
                INSERT INTO participations (group_buy_id, user_id, is_host, units, amount, status, expires_at,
                    created_at, updated_at)
                VALUES (?, ?, TRUE, 1, 1000, ?, ?, ?, ?)
                """, groupBuyId, hostId, status, expiresAt, NOW, NOW);
    }

    private long insertParticipation(long groupBuyId, long userId, String status, LocalDateTime expiresAt) {
        jdbc.update("""
                INSERT INTO participations (group_buy_id, user_id, is_host, units, amount, status, expires_at,
                    created_at, updated_at)
                VALUES (?, ?, FALSE, 1, 1000, ?, ?, ?, ?)
                """, groupBuyId, userId, status, expiresAt, NOW, NOW);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertPayment(long participationId, String status, String paymentKey) {
        jdbc.update("""
                INSERT INTO payments (participation_id, order_id, payment_key, amount, status, approved_at,
                    created_at, updated_at)
                VALUES (?, ?, ?, 1000, ?, ?, ?, ?)
                """, participationId, "bt-" + java.util.UUID.randomUUID(), paymentKey, status,
                paymentKey == null ? null : NOW.minusMinutes(30), NOW, NOW);
    }

    private void insertRefreshToken(String hash, LocalDateTime expiresAt, LocalDateTime revokedAt) {
        jdbc.update("""
                INSERT INTO refresh_tokens (user_id, token_hash, expires_at, revoked_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, aId, hash, expiresAt, revokedAt, NOW, NOW);
    }

    private long hostParticipationId(long groupBuyId) {
        return jdbc.queryForObject("SELECT id FROM participations WHERE group_buy_id = ? AND is_host = TRUE",
                Long.class, groupBuyId);
    }

    private String participationStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM participations WHERE id = ?", String.class, id);
    }

    private Map<String, Object> groupBuy(long id) {
        return jdbc.queryForMap("SELECT status, fail_reason, reserved_units, paid_units FROM group_buys WHERE id = ?",
                id);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private long userId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private ResultActions confirm(String token, String paymentKey, String orderId, long amount) throws Exception {
        return mvc.perform(post("/api/payments/confirm")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentKey\":\"%s\",\"orderId\":\"%s\",\"amount\":%d}".formatted(paymentKey, orderId, amount)));
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
