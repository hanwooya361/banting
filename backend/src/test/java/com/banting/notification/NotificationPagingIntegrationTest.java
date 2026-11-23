package com.banting.notification;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banting.TestcontainersConfiguration;
import com.banting.support.DatabaseCleaner;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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

/** 알림 전체 보기: id 기준 페이지(20개씩) + 더 있는지. 보관 기간 안의 알림은 더 보기로 끝까지 볼 수 있다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationPagingIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private String token;
    private long userId;
    private long otherId;
    private long groupBuyId;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        token = signupAndLogin("me@test.com", "알림받는이");
        signupAndLogin("other@test.com", "다른이웃");
        userId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'me@test.com'", Long.class);
        otherId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'other@test.com'", Long.class);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO group_buys (host_id, title, description, category, product_url, price_type, total_price,
                    total_quantity, unit_label, capacity_units, min_units_per_person, reserved_units, paid_units,
                    status, location, neighborhood_name, deadline_at, created_at, updated_at)
                VALUES (?, '휴지', '', 'LIVING', 'https://example.com', 'EQUAL', 10000, 30, '롤', 3, 1, 1, 1,
                    'OPEN', ST_PointFromText('POINT(37.5 127.0)', 4326), '동네', ?, ?, ?)
                """, userId, now.plusDays(1), now, now);
        groupBuyId = jdbc.queryForObject("SELECT id FROM group_buys", Long.class);
    }

    @Test
    void 알림이_많으면_20개씩_더_보기로_끝까지_본다() throws Exception {
        insertNotifications(userId, 45);
        insertNotifications(otherId, 3);   // 남의 알림은 섞이지 않음

        String first = list(null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.unreadCount").value(45))
                .andExpect(jsonPath("$.items[0].message").value("알림 45"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long cursor = lastId(first);

        String second = list(cursor).andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.items[0].message").value("알림 25"))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        list(lastId(second)).andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[4].message").value("알림 1"))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void 딱_20개면_다음_페이지가_없다() throws Exception {
        insertNotifications(userId, 20);

        list(null).andExpect(jsonPath("$.items.length()").value(20)).andExpect(jsonPath("$.hasNext").value(false));
    }

    private void insertNotifications(long user, int count) {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 1; i <= count; i++) {
            jdbc.update("INSERT INTO notifications (user_id, type, group_buy_id, message, created_at, updated_at) "
                    + "VALUES (?, 'GROUP_BUY_CONFIRMED', ?, ?, ?, ?)", user, groupBuyId, "알림 " + i, now, now);
        }
    }

    private ResultActions list(Long before) throws Exception {
        var request = get("/api/notifications").header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (before != null) {
            request.param("before", String.valueOf(before));
        }
        return mvc.perform(request);
    }

    private static long lastId(String body) {
        int last = ((Number) JsonPath.read(body, "$.items.length()")).intValue() - 1;
        return ((Number) JsonPath.read(body, "$.items[" + last + "].id")).longValue();
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
