package com.banting.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banting.TestcontainersConfiguration;
import com.banting.support.DatabaseCleaner;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 회원 탈퇴 (T22 완료 조건: 진행 중 공구가 없으면 탈퇴, 개인정보 익명화, 같은 이메일 재가입 가능).
 * 회원 행은 지우지 않는다: 결제·정산 기록이 회원을 가리키므로 행은 남기고 개인정보만 지운다 (개인정보처리방침 3항).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WithdrawalIntegrationTest {

    private static final String EMAIL = "leaver@test.com";
    private static final String PASSWORD = "password123";
    private static final String NICKNAME = "떠나는이웃";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private String token;
    private Cookie refreshCookie;
    private long userId;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        signup(EMAIL, NICKNAME).andExpect(status().isCreated());
        MvcResult login = login(EMAIL);
        token = JsonPath.read(login.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.accessToken");
        refreshCookie = login.getResponse().getCookie("refresh_token");
        userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, EMAIL);
        jdbc.update("UPDATE users SET location = ST_PointFromText('POINT(37.567 126.979)', 4326), "
                + "neighborhood_name = '중구 명동' WHERE id = ?", userId);
    }

    @Test
    void 탈퇴하면_개인정보가_지워지고_다시_로그인할_수_없다() throws Exception {
        long completed = insertGroupBuy("COMPLETED");
        insertParticipation(completed, userId, "RECEIVED");
        jdbc.update("INSERT INTO notifications (user_id, type, group_buy_id, message, created_at, updated_at) "
                + "VALUES (?, 'GROUP_BUY_COMPLETED', ?, '완료', NOW(6), NOW(6))", userId, completed);

        withdraw(token, PASSWORD).andExpect(status().isNoContent());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", userId);
        assertThat(row.get("email")).isEqualTo("deleted-" + userId + "@deleted.banting.invalid");
        assertThat(row.get("nickname")).isEqualTo("탈퇴회원" + userId);
        assertThat(row.get("location")).isNull();
        assertThat(row.get("neighborhood_name")).isNull();
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat((String) row.get("password_hash")).doesNotStartWith("$2");   // BCrypt가 아니라 어떤 비밀번호와도 불일치
        // 거래 기록은 회원을 가리킨 채 남고, 알림·로그인 토큰은 지운다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM participations WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?", Integer.class, userId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId)).isZero();

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(EMAIL, PASSWORD)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(refreshCookie)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void 탈퇴한_뒤_같은_이메일과_닉네임으로_다시_가입할_수_있다() throws Exception {
        withdraw(token, PASSWORD).andExpect(status().isNoContent());

        signup(EMAIL, NICKNAME).andExpect(status().isCreated());
        login(EMAIL);
    }

    @Test
    void 비밀번호가_틀리면_탈퇴하지_않는다() throws Exception {
        withdraw(token, "wrongpass1").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));

        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM users WHERE id = ?", Boolean.class, userId)).isTrue();
    }

    @Test
    void 진행_중인_공구가_있으면_탈퇴할_수_없다() throws Exception {
        long open = insertGroupBuy("OPEN");
        insertParticipation(open, userId, "PAID");

        withdraw(token, PASSWORD).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WITHDRAWAL_BLOCKED"));
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId)).isEqualTo(EMAIL);
    }

    @Test
    void 환불이_진행_중이어도_탈퇴할_수_없다() throws Exception {
        long failed = insertGroupBuy("FAILED");
        insertParticipation(failed, userId, "REFUND_PENDING");

        withdraw(token, PASSWORD).andExpect(status().isConflict());
    }

    @Test
    void 끝난_공구나_결제_전에_취소한_공구만_있으면_탈퇴할_수_있다() throws Exception {
        insertParticipation(insertGroupBuy("FAILED"), userId, "REFUNDED");
        insertParticipation(insertGroupBuy("OPEN"), userId, "CANCELED");

        withdraw(token, PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void 비밀번호가_없는_간편_가입_회원은_탈퇴라고_입력해서_탈퇴하고_소셜_연결도_끊긴다() throws Exception {
        // 간편 가입 회원 흉내: 비밀번호 없음 + 카카오 연결 (T23)
        jdbc.update("UPDATE users SET password_hash = NULL WHERE id = ?", userId);
        jdbc.update("INSERT INTO user_social_accounts (user_id, provider, provider_user_id, created_at, updated_at) "
                + "VALUES (?, 'KAKAO', 'k-9', NOW(6), NOW(6))", userId);

        mvc.perform(post("/api/users/me/withdrawal").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"confirmText\":\"네\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WITHDRAWAL_CONFIRM_REQUIRED"));
        mvc.perform(post("/api/users/me/withdrawal").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"confirmText\":\"탈퇴\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_social_accounts", Integer.class)).isZero();
    }

    @Test
    void 로그인하지_않으면_탈퇴할_수_없다() throws Exception {
        mvc.perform(post("/api/users/me/withdrawal").contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"%s\"}".formatted(PASSWORD))).andExpect(status().isUnauthorized());
    }

    // ───── 도우미 ─────

    private ResultActions withdraw(String accessToken, String password) throws Exception {
        return mvc.perform(post("/api/users/me/withdrawal").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"%s\"}".formatted(password)));
    }

    private ResultActions signup(String email, String nickname) throws Exception {
        return mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\",\"nickname\":\"%s\",\"agreePrivacy\":true}"
                        .formatted(email, PASSWORD, nickname)));
    }

    private MvcResult login(String email) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
    }

    /** 다른 회원이 연 공구 (탈퇴하는 회원은 참여자). */
    private long insertGroupBuy(String status) {
        jdbc.update("INSERT IGNORE INTO users (id, email, password_hash, nickname, role, search_radius_m, created_at, updated_at) "
                + "VALUES (9000, 'host@test.com', 'x', '개설자', 'USER', 1000, NOW(6), NOW(6))");
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO group_buys (host_id, title, description, category, product_url, price_type, total_price,
                    total_quantity, unit_label, capacity_units, min_units_per_person, reserved_units, paid_units,
                    status, fail_reason, location, neighborhood_name, deadline_at, created_at, updated_at)
                VALUES (9000, '휴지', '', 'LIVING', 'https://example.com', 'EQUAL', 9000, 30, '롤', 3, 1, 2, 2,
                    ?, ?, ST_PointFromText('POINT(37.567 126.979)', 4326), '동네', ?, ?, ?)
                """, status, status.equals("FAILED") ? "DEADLINE_UNMET" : null, now.plusDays(1), now, now);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertParticipation(long groupBuyId, long user, String status) {
        jdbc.update("INSERT INTO participations (group_buy_id, user_id, is_host, units, amount, status, expires_at, "
                + "created_at, updated_at) VALUES (?, ?, FALSE, 1, 3000, ?, NOW(6), NOW(6), NOW(6))", groupBuyId, user, status);
    }
}
