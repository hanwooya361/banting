package com.banting.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banting.TestcontainersConfiguration;
import com.banting.support.DatabaseCleaner;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
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
 * 문의 게시판·업데이트 노트 (T17 완료 조건: 문의 등록·관리자 답변 동작).
 * 운영형 흐름: 사용자 피드백 → 관리자 답변(+ GitHub Issue 링크) → 사용자에게 알림 → 업데이트 노트로 반영 내용 공개.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InquiryIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private String userToken;
    private String otherToken;
    private String adminToken;
    private long userId;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        userToken = signupAndLogin("user@test.com", "문의하는이", false);
        otherToken = signupAndLogin("other@test.com", "다른이웃", false);
        adminToken = signupAndLogin("admin@test.com", "운영자", true);
        userId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'user@test.com'", Long.class);
    }

    // ───── 문의 등록·내 문의 ─────

    @Test
    void 문의를_등록하면_내_문의에_답변_대기로_보이고_남의_문의는_안_보인다() throws Exception {
        create(userToken, "BUG", "결제 후 화면이 멈춰요", "토스 결제 후 흰 화면이 떠요").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));
        create(otherToken, "FEATURE", "지도에서 동네 고르기", "지도로 고르고 싶어요");

        mvc.perform(get("/api/inquiries/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("결제 후 화면이 멈춰요"))
                .andExpect(jsonPath("$[0].type").value("BUG"))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].answer").doesNotExist());
    }

    @Test
    void 피드백_버튼으로_남기면_어느_화면에서_남겼는지_관리자에게_보인다() throws Exception {
        mvc.perform(post("/api/inquiries").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"BUG\",\"title\":\"버튼이 안 눌려요\",\"content\":\"참여하기\",\"pagePath\":\"/group-buys/12\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pagePath").value("/group-buys/12"));

        mvc.perform(get("/api/admin/inquiries").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(jsonPath("$[0].pagePath").value("/group-buys/12"));
    }

    @Test
    void 화면_주소는_우리_사이트_경로만_받는다() throws Exception {
        for (String bad : new String[] {"https://evil.example/x", "//evil.example", "javascript:alert(1)", "/" + "a".repeat(200)}) {
            mvc.perform(post("/api/inquiries").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"BUG\",\"title\":\"제목\",\"content\":\"내용\",\"pagePath\":\"%s\"}".formatted(bad)))
                    .andExpect(status().isBadRequest());
        }
        create(userToken, "ETC", "주소 없이", "문의 화면에서 직접 남김").andExpect(status().isCreated())
                .andExpect(jsonPath("$.pagePath").doesNotExist());
    }

    @Test
    void 문의는_로그인이_필요하고_입력을_검증한다() throws Exception {
        mvc.perform(post("/api/inquiries").contentType(MediaType.APPLICATION_JSON)
                .content(body("BUG", "제목", "내용"))).andExpect(status().isUnauthorized());
        create(userToken, "BUG", " ", "내용").andExpect(status().isBadRequest());
        create(userToken, "BUG", "제목", "가".repeat(InquiryService.CONTENT_MAX + 1)).andExpect(status().isBadRequest());
        create(userToken, "WRONG", "제목", "내용").andExpect(status().isBadRequest());
    }

    @Test
    void 문의는_하루_10건까지() throws Exception {
        for (int i = 0; i < InquiryService.DAILY_LIMIT; i++) {
            create(userToken, "ETC", "문의 " + i, "내용").andExpect(status().isCreated());
        }
        create(userToken, "ETC", "한 번 더", "내용").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("INQUIRY_DAILY_LIMIT"));
    }

    // ───── 관리자 답변 ─────

    @Test
    void 관리자가_답변하면_답변_완료가_되고_문의한_사람에게_알림이_간다() throws Exception {
        long id = inquiryId(create(userToken, "BUG", "결제 후 화면이 멈춰요", "흰 화면"));

        mvc.perform(get("/api/admin/inquiries").param("status", "OPEN")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].authorNickname").value("문의하는이"));

        answer(adminToken, id, "고쳤어요. 다음 배포에 반영돼요.", "https://github.com/hanwooya361/banting/issues/40")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"));

        mvc.perform(get("/api/inquiries/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(jsonPath("$[0].status").value("ANSWERED"))
                .andExpect(jsonPath("$[0].answer").value("고쳤어요. 다음 배포에 반영돼요."))
                .andExpect(jsonPath("$[0].issueUrl").value("https://github.com/hanwooya361/banting/issues/40"))
                .andExpect(jsonPath("$[0].answeredAt").isNotEmpty());
        mvc.perform(get("/api/notifications").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(jsonPath("$.items[0].type").value("INQUIRY_ANSWERED"))
                .andExpect(jsonPath("$.items[0].groupBuyId").doesNotExist())
                .andExpect(jsonPath("$.unreadCount").value(1));
        // 답변하면 답변 대기 목록에서 빠진다
        mvc.perform(get("/api/admin/inquiries").param("status", "OPEN")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 답변을_고치면_내용만_바뀌고_알림은_다시_가지_않는다() throws Exception {
        long id = inquiryId(create(userToken, "BUG", "제목", "내용"));
        answer(adminToken, id, "확인 중이에요", null).andExpect(status().isOk());

        answer(adminToken, id, "고쳤어요", "https://github.com/hanwooya361/banting/issues/41")
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT answer FROM inquiries WHERE id = ?", String.class, id)).isEqualTo("고쳤어요");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(1);
    }

    @Test
    void 관리자_기능은_관리자만_쓸_수_있다() throws Exception {
        long id = inquiryId(create(userToken, "BUG", "제목", "내용"));

        mvc.perform(get("/api/admin/inquiries").header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());
        answer(userToken, id, "내가 답변", null).andExpect(status().isForbidden());
        createNotice(userToken, "노트", "내용").andExpect(status().isForbidden());
    }

    @Test
    void 이슈_링크는_http_https_주소만_받는다() throws Exception {
        long id = inquiryId(create(userToken, "BUG", "제목", "내용"));

        answer(adminToken, id, "답변", "javascript:alert(1)").andExpect(status().isBadRequest());
        answer(adminToken, id, " ", null).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT status FROM inquiries WHERE id = ?", String.class, id)).isEqualTo("OPEN");
    }

    @Test
    void 없는_문의에_답변하면_404() throws Exception {
        answer(adminToken, 9999, "답변", null).andExpect(status().isNotFound());
    }

    // ───── 업데이트 노트 ─────

    @Test
    void 업데이트_노트는_관리자가_쓰고_누구나_최신순으로_본다() throws Exception {
        createNotice(adminToken, "11/23 체험 모드", "혼자서도 끝까지 해 볼 수 있어요").andExpect(status().isCreated());
        createNotice(adminToken, "11/24 문의 게시판", "받은 피드백: 결제 후 흰 화면 → 고침 (#40)").andExpect(status().isCreated());

        mvc.perform(get("/api/notices"))   // 로그인 없이
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("11/24 문의 게시판"))
                .andExpect(jsonPath("$[1].title").value("11/23 체험 모드"));
    }

    // ───── 도메인 ─────

    @Test
    void 공구_알림은_공구_없이_만들_수_없다() {
        // V6에서 group_buy_id를 NULL 허용으로 바꿨지만, 공구 알림까지 공구 없이 생기지 않게 CHECK로 묶는다
        assertThatThrownBy(() -> jdbc.update("INSERT INTO notifications (user_id, type, group_buy_id, message, created_at, "
                + "updated_at) VALUES (?, 'GROUP_BUY_CONFIRMED', NULL, '공구 없음', NOW(6), NOW(6))", userId))
                .hasMessageContaining("ck_notifications_group_buy");
    }

    // ───── 도우미 ─────

    private ResultActions create(String token, String type, String title, String content) throws Exception {
        return mvc.perform(post("/api/inquiries").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body(type, title, content)));
    }

    private static String body(String type, String title, String content) {
        return "{\"type\":\"%s\",\"title\":\"%s\",\"content\":\"%s\"}".formatted(type, title, content);
    }

    private ResultActions answer(String token, long id, String answer, String issueUrl) throws Exception {
        String issue = issueUrl == null ? "null" : "\"" + issueUrl + "\"";
        return mvc.perform(post("/api/admin/inquiries/" + id + "/answer")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"%s\",\"issueUrl\":%s}".formatted(answer, issue)));
    }

    private ResultActions createNotice(String token, String title, String content) throws Exception {
        return mvc.perform(post("/api/admin/notices").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"%s\",\"content\":\"%s\"}".formatted(title, content)));
    }

    private static long inquiryId(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id"))
                .longValue();
    }

    /** 관리자는 운영에서도 DB로 role을 바꾼다 (T17 결정). 로그인할 때 role이 토큰에 들어가므로 바꾼 뒤 로그인. */
    private String signupAndLogin(String email, String nickname, boolean admin) throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"password123\",\"nickname\":\"%s\"}".formatted(email, nickname)));
        if (admin) {
            jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email = ?", email);
        }
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.accessToken");
    }
}
