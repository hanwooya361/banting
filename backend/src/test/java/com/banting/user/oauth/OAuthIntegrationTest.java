package com.banting.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banting.TestcontainersConfiguration;
import com.banting.support.DatabaseCleaner;
import jakarta.servlet.http.Cookie;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 카카오·구글 간편 가입 (T23). 실제 카카오·구글 대신 가짜 OAuthClient를 쓴다 (인가 코드 → 프로필).
 * 코드 값으로 어떤 사람인지 흉내 낸다: new-google(확인된 이메일), local-google(기존 회원 이메일), unverified-google, kakao-no-email.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, OAuthIntegrationTest.FakeOAuthConfig.class})
class OAuthIntegrationTest {

    @TestConfiguration
    static class FakeOAuthConfig {
        @Bean
        @Primary
        OAuthClient fakeOAuthClient() {
            Map<String, OAuthProfile> profiles = Map.of(
                    "new-google", new OAuthProfile(OAuthProvider.GOOGLE, "g-1", "new@gmail.com", true, "새 이웃"),
                    "local-google", new OAuthProfile(OAuthProvider.GOOGLE, "g-2", "local@test.com", true, "기존"),
                    "unverified-google", new OAuthProfile(OAuthProvider.GOOGLE, "g-3", "local@test.com", false, "미확인"),
                    "kakao-no-email", new OAuthProfile(OAuthProvider.KAKAO, "k-1", null, false, "카카오이웃"));
            return new OAuthClient() {
                @Override
                public Set<OAuthProvider> enabledProviders() {
                    return EnumSet.allOf(OAuthProvider.class);
                }

                @Override
                public String authorizeUrl(OAuthProvider provider, String state, String redirectUri) {
                    return "https://fake-" + provider.path() + ".example/authorize?state=" + state + "&redirect_uri=" + redirectUri;
                }

                @Override
                public OAuthProfile fetchProfile(OAuthProvider provider, String code, String redirectUri) {
                    OAuthProfile profile = profiles.get(code);
                    if (profile == null || profile.provider() != provider) {
                        throw new OAuthClient.OAuthFailedException("알 수 없는 코드: " + code);
                    }
                    return profile;
                }
            };
        }
    }

    private static final String BASE = "http://localhost:5180";   // application.yml의 banting.oauth.base-url 기본값

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
    }

    @Test
    void 켜진_공급자_목록을_준다() throws Exception {
        mvc.perform(get("/api/auth/oauth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers.length()").value(2));
    }

    @Test
    void 시작하면_state_쿠키를_굽고_공급자_인증_화면으로_보낸다() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/oauth/google/start").param("next", "/group-buys/3"))
                .andExpect(status().isFound())
                .andReturn();
        Cookie state = result.getResponse().getCookie(OAuthController.STATE_COOKIE);
        assertThat(state).isNotNull();
        assertThat(state.isHttpOnly()).isTrue();
        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith("https://fake-google.example/authorize?state=" + state.getValue())
                .contains("redirect_uri=" + BASE + "/api/auth/oauth/google/callback");
    }

    @Test
    void 처음_온_사람은_동의와_닉네임을_거쳐_가입하고_로그인된다() throws Exception {
        MvcResult callback = callback("google", "new-google", "/group-buys/3");
        assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo(BASE + "/auth/social/signup?next=%2Fgroup-buys%2F3");
        Cookie ticket = callback.getResponse().getCookie(OAuthController.TICKET_COOKIE);
        assertThat(ticket).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();   // 아직 회원 아님

        mvc.perform(get("/api/auth/oauth/signup-info").cookie(ticket))
                .andExpect(jsonPath("$.provider").value("GOOGLE"))
                .andExpect(jsonPath("$.suggestedNickname").value("새이웃"))   // 닉네임 규칙(한글·영문·숫자)에 맞게 공백 제거
                .andExpect(jsonPath("$.email").value("new@gmail.com"));

        complete(ticket, "새이웃", false).andExpect(status().isBadRequest());   // 동의 없이는 가입 불가
        MvcResult done = complete(ticket, "새이웃", true).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();
        assertThat(done.getResponse().getCookie("refresh_token")).isNotNull();

        Map<String, Object> user = jdbc.queryForMap("SELECT * FROM users");
        assertThat(user).containsEntry("email", "new@gmail.com").containsEntry("nickname", "새이웃");
        assertThat(user.get("password_hash")).isNull();
        assertThat(user.get("privacy_agreed_at")).isNotNull();
        assertThat(jdbc.queryForMap("SELECT provider, provider_user_id FROM user_social_accounts"))
                .containsEntry("provider", "GOOGLE").containsEntry("provider_user_id", "g-1");

        complete(ticket, "다른닉네임", true).andExpect(status().isBadRequest())   // 대기표는 한 번만
                .andExpect(jsonPath("$.code").value("OAUTH_TICKET_INVALID"));
    }

    @Test
    void 이미_연결된_계정이면_바로_로그인된다() throws Exception {
        signUpWithGoogle("new-google", "새이웃");

        MvcResult again = callback("google", "new-google", "/me");
        assertThat(again.getResponse().getRedirectedUrl()).isEqualTo(BASE + "/auth/social?next=%2Fme");
        assertThat(again.getResponse().getCookie("refresh_token")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    void 구글이_확인한_이메일이_기존_회원이면_그_계정에_연결한다() throws Exception {
        long localId = signUpLocal("local@test.com", "기존회원");

        MvcResult result = callback("google", "local-google", "/");
        assertThat(result.getResponse().getRedirectedUrl()).startsWith(BASE + "/auth/social?");
        assertThat(jdbc.queryForObject("SELECT user_id FROM user_social_accounts WHERE provider_user_id = 'g-2'", Long.class))
                .isEqualTo(localId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(1);
    }

    @Test
    void 확인되지_않은_이메일로는_기존_계정에_연결하지_않는다() throws Exception {
        signUpLocal("local@test.com", "기존회원");

        MvcResult result = callback("google", "unverified-google", "/");
        assertThat(result.getResponse().getRedirectedUrl()).startsWith(BASE + "/auth/social/signup");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_social_accounts", Integer.class)).isZero();
        mvc.perform(get("/api/auth/oauth/signup-info").cookie(result.getResponse().getCookie(OAuthController.TICKET_COOKIE)))
                .andExpect(jsonPath("$.email").doesNotExist());   // 확인 안 된 이메일은 계정 이메일로 쓰지 않음
    }

    @Test
    void 카카오가_이메일을_안_줘도_가입된다() throws Exception {
        MvcResult callback = callback("kakao", "kakao-no-email", "/");
        complete(callback.getResponse().getCookie(OAuthController.TICKET_COOKIE), "카카오이웃", true)
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT email FROM users", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT provider FROM user_social_accounts", String.class)).isEqualTo("KAKAO");
    }

    @Test
    void state가_다르거나_없으면_로그인_화면으로_돌려보낸다() throws Exception {
        mvc.perform(get("/api/auth/oauth/google/callback").param("code", "new-google").param("state", "forged")
                        .cookie(new Cookie(OAuthController.STATE_COOKIE, "real")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", BASE + "/login?socialError=state"));
        mvc.perform(get("/api/auth/oauth/google/callback").param("code", "new-google").param("state", "x"))
                .andExpect(header().string("Location", BASE + "/login?socialError=state"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oauth_signup_tickets", Integer.class)).isZero();
    }

    @Test
    void 공급자_인증이_실패하거나_사용자가_취소하면_로그인_화면으로() throws Exception {
        Cookie state = startState("google", "/");
        mvc.perform(get("/api/auth/oauth/google/callback").param("code", "bad-code").param("state", state.getValue())
                        .cookie(state))
                .andExpect(header().string("Location", BASE + "/login?socialError=provider"));
        mvc.perform(get("/api/auth/oauth/google/callback").param("error", "access_denied").param("state", state.getValue())
                        .cookie(state))
                .andExpect(header().string("Location", BASE + "/login?socialError=canceled"));
    }

    @Test
    void 다음_주소는_우리_사이트_경로만_받는다() throws Exception {
        MvcResult callback = callback("google", "new-google", "https://evil.example");
        assertThat(callback.getResponse().getRedirectedUrl()).isEqualTo(BASE + "/auth/social/signup?next=%2F");
    }

    @Test
    void 간편_가입_회원은_비밀번호_로그인을_할_수_없다() throws Exception {
        signUpWithGoogle("new-google", "새이웃");

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@gmail.com\",\"password\":\"password123\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ───── 도우미 ─────

    private Cookie startState(String provider, String next) throws Exception {
        return mvc.perform(get("/api/auth/oauth/" + provider + "/start").param("next", next))
                .andReturn().getResponse().getCookie(OAuthController.STATE_COOKIE);
    }

    /** 시작 → (공급자 화면은 건너뜀) → 같은 state로 콜백. 시작 때 받은 next 쿠키도 함께 보낸다. */
    private MvcResult callback(String provider, String code, String next) throws Exception {
        MvcResult start = mvc.perform(get("/api/auth/oauth/" + provider + "/start").param("next", next)).andReturn();
        Cookie state = start.getResponse().getCookie(OAuthController.STATE_COOKIE);
        Cookie nextCookie = start.getResponse().getCookie(OAuthController.NEXT_COOKIE);
        return mvc.perform(get("/api/auth/oauth/" + provider + "/callback").param("code", code)
                        .param("state", state.getValue()).cookie(state, nextCookie))
                .andExpect(status().isFound())
                .andReturn();
    }

    private ResultActions complete(Cookie ticket, String nickname, boolean agree) throws Exception {
        return mvc.perform(post("/api/auth/oauth/complete").cookie(ticket).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"%s\",\"agreePrivacy\":%s}".formatted(nickname, agree)));
    }

    private void signUpWithGoogle(String code, String nickname) throws Exception {
        complete(callback("google", code, "/").getResponse().getCookie(OAuthController.TICKET_COOKIE), nickname, true)
                .andExpect(status().isOk());
    }

    private long signUpLocal(String email, String nickname) throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"password123\",\"nickname\":\"%s\",\"agreePrivacy\":true}"
                                .formatted(email, nickname)))
                .andExpect(status().isCreated());
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
