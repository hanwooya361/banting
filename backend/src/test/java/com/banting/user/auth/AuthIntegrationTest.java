package com.banting.user.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banting.TestcontainersConfiguration;
import com.banting.support.DatabaseCleaner;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 가입 → 로그인 → 내 정보, refresh 교체·탈취 탐지, 로그아웃을 실제 MySQL(Testcontainers)과 함께 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthIntegrationTest {

    private static final String EMAIL = "banting@example.com";
    private static final String PASSWORD = "password123";
    private static final String NICKNAME = "반띵이";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtEncoder jwtEncoder;

    @BeforeEach
    void cleanUp() {
        DatabaseCleaner.clean(jdbc);
    }

    // ───── 가입·로그인·내 정보 ─────

    @Test
    void 가입하고_로그인하면_access_토큰과_HttpOnly_refresh_쿠키를_받고_내_정보를_조회한다() throws Exception {
        signup(EMAIL, PASSWORD, NICKNAME)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.nickname").value(NICKNAME));

        MvcResult login = login(EMAIL, PASSWORD);
        String setCookie = login.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie)
                .contains("refresh_token=", "HttpOnly", "Secure", "SameSite=Strict", "Path=/api/auth");
        assertThat(JsonPath.<Integer>read(body(login), "$.expiresIn")).isEqualTo(1800);

        mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken(login))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.nickname").value(NICKNAME))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.searchRadiusM").value(1000))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void 이메일은_대소문자와_앞뒤_공백을_정리해_저장하고_로그인한다() throws Exception {
        signup("  Banting@Example.COM ", PASSWORD, NICKNAME).andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(EMAIL));
        login(EMAIL, PASSWORD);
    }

    @Test
    void 비밀번호는_BCrypt_해시로만_저장된다() throws Exception {
        signup(EMAIL, PASSWORD, NICKNAME);
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, EMAIL);
        assertThat(hash).startsWith("$2").doesNotContain(PASSWORD);
    }

    // ───── 가입 실패 ─────

    @Test
    void 같은_이메일이나_닉네임으로는_가입할_수_없다() throws Exception {
        signup(EMAIL, PASSWORD, NICKNAME);

        signup(EMAIL, PASSWORD, "다른닉네임")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_DUPLICATED"));
        signup("other@example.com", PASSWORD, NICKNAME)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NICKNAME_DUPLICATED"));
    }

    @Test
    void 입력값이_규칙에_맞지_않으면_칸별_오류를_돌려준다() throws Exception {
        signup("not-an-email", "short", "a")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors.length()").value(3))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'password')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'nickname')]").exists());
    }

    @Test
    void 영문만_있는_비밀번호는_거부한다() throws Exception {
        signup(EMAIL, "passwordonly", NICKNAME)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
    }

    // ───── 로그인 실패 ─────

    @Test
    void 비밀번호가_틀리거나_없는_이메일이면_같은_오류로_응답한다() throws Exception {
        signup(EMAIL, PASSWORD, NICKNAME);

        mvc.perform(loginRequest(EMAIL, "wrong-password1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(loginRequest("nobody@example.com", PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // ───── access 토큰 검증 ─────

    @Test
    void 토큰이_없거나_위조됐거나_만료되면_401() throws Exception {
        mvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer("not.a.jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        // 같은 키로 서명했지만 만료 시각이 지난 토큰 (시계 오차 허용 60초보다 과거)
        Instant past = Instant.now().minusSeconds(3600);
        JwtClaimsSet expired = JwtClaimsSet.builder()
                .issuer("banting").subject("1").claim("role", "USER")
                .issuedAt(past.minusSeconds(1800)).expiresAt(past)
                .build();
        String expiredToken = jwtEncoder.encode(
                JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), expired)).getTokenValue();
        mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(expiredToken)))
                .andExpect(status().isUnauthorized());
    }

    // ───── refresh ─────

    @Test
    void refresh하면_새_토큰을_주고_쓴_refresh는_폐기한다() throws Exception {
        signup(EMAIL, PASSWORD, NICKNAME);
        String firstRefresh = refreshCookie(login(EMAIL, PASSWORD));

        MvcResult refreshed = mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", firstRefresh)))
                .andExpect(status().isOk())
                .andReturn();
        String secondRefresh = refreshCookie(refreshed);
        assertThat(secondRefresh).isNotEqualTo(firstRefresh);

        // 새 access 토큰으로 내 정보 조회 가능
        mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken(refreshed))))
                .andExpect(status().isOk());

        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE revoked_at IS NULL", Integer.class);
        assertThat(active).isEqualTo(1);
    }

    @Test
    void 폐기된_refresh가_다시_오면_탈취로_보고_그_회원의_refresh를_모두_폐기한다() throws Exception {
        signup(EMAIL, PASSWORD, NICKNAME);
        String stolen = refreshCookie(login(EMAIL, PASSWORD));
        // 정상 사용자가 먼저 refresh → stolen은 폐기되고 새 토큰(latest) 발급
        String latest = refreshCookie(
                mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", stolen))).andReturn());

        // 공격자가 옛 토큰(stolen)을 사용 → 거부 + 쿠키 삭제
        MvcResult reuse = mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", stolen)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
                .andReturn();
        assertThat(reuse.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");

        // 정상 사용자의 최신 토큰도 함께 폐기됨 → 모든 기기 다시 로그인
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", latest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_쿠키가_없거나_모르는_값이면_401() throws Exception {
        mvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", "unknown-value")))
                .andExpect(status().isUnauthorized());
    }

    // ───── 로그아웃 ─────

    @Test
    void 로그아웃하면_refresh가_폐기되고_쿠키가_지워진다() throws Exception {
        signup(EMAIL, PASSWORD, NICKNAME);
        String refresh = refreshCookie(login(EMAIL, PASSWORD));

        MvcResult logout = mvc.perform(post("/api/auth/logout").cookie(new Cookie("refresh_token", refresh)))
                .andExpect(status().isNoContent())
                .andReturn();
        assertThat(logout.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");

        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", refresh)))
                .andExpect(status().isUnauthorized());
        // 쿠키 없이 로그아웃해도 성공 (멱등)
        mvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
    }

    // ───── 헬퍼 ─────

    // ───── 개인정보처리방침 동의 (T21) ─────

    @Test
    void 개인정보처리방침에_동의하지_않으면_가입할_수_없다() throws Exception {
        for (String agree : new String[] {"", ", \"agreePrivacy\": false", ", \"agreePrivacy\": null"}) {
            mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\": \"%s\", \"password\": \"%s\", \"nickname\": \"%s\"%s}"
                                    .formatted(EMAIL, PASSWORD, NICKNAME, agree)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'agreePrivacy')]").exists());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
    }

    @Test
    void 동의하고_가입하면_동의_시각이_저장된다() throws Exception {
        signup(EMAIL, PASSWORD, NICKNAME).andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("SELECT privacy_agreed_at IS NOT NULL FROM users WHERE email = ?",
                Boolean.class, EMAIL)).isTrue();
    }

    private org.springframework.test.web.servlet.ResultActions signup(String email, String password, String nickname)
            throws Exception {
        String json = """
                {"email": "%s", "password": "%s", "nickname": "%s", "agreePrivacy": true}
                """.formatted(email, password, nickname);
        return mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private org.springframework.test.web.servlet.RequestBuilder loginRequest(String email, String password) {
        String json = """
                {"email": "%s", "password": "%s"}
                """.formatted(email, password);
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(loginRequest(email, password)).andExpect(status().isOk()).andReturn();
    }

    private static String accessToken(MvcResult result) throws Exception {
        return JsonPath.read(body(result), "$.accessToken");
    }

    private static String refreshCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("refresh_token");
        assertThat(cookie).isNotNull();
        return cookie.getValue();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
