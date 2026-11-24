package com.banting.user.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 실제 카카오·구글 형식의 응답을 JDK HttpServer로 흉내 내 토큰 교환·사용자 정보 해석을 검증한다 (T23). */
class HttpOAuthClientTest {

    private HttpServer server;
    private String base;
    private final Map<String, String> lastForm = new ConcurrentHashMap<>();
    private final Map<String, String> lastAuthorization = new ConcurrentHashMap<>();
    private volatile String kakaoUserJson;
    private volatile int tokenStatus = 200;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastForm.clear();
            for (String pair : body.split("&")) {
                String[] kv = pair.split("=", 2);
                lastForm.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
            respond(exchange, tokenStatus, tokenStatus == 200 ? "{\"access_token\":\"at-1\",\"token_type\":\"bearer\"}" : "{}");
        });
        server.createContext("/kakao/me", exchange -> {
            lastAuthorization.put("kakao", exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, kakaoUserJson);
        });
        server.createContext("/google/userinfo", exchange -> {
            lastAuthorization.put("google", exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"sub\":\"1098\",\"email\":\"me@gmail.com\",\"email_verified\":true,\"name\":\"조 현우\",\"picture\":\"x\"}");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private HttpOAuthClient client(String kakaoSecret) {
        return new HttpOAuthClient(new OAuthProperties("https://banting.example", Map.of(
                OAuthProvider.KAKAO, new OAuthProperties.Provider("kakao-key", kakaoSecret, "https://kauth.kakao.com/oauth/authorize",
                        base + "/token", base + "/kakao/me", null),
                OAuthProvider.GOOGLE, new OAuthProperties.Provider("google-id", "google-secret",
                        "https://accounts.google.com/o/oauth2/v2/auth", base + "/token", base + "/google/userinfo",
                        "openid email profile"))));
    }

    @Test
    void 키가_모두_있는_공급자만_켜진다() {
        assertThat(client("kakao-secret").enabledProviders()).containsExactlyInAnyOrder(OAuthProvider.KAKAO, OAuthProvider.GOOGLE);
        assertThat(client("").enabledProviders()).containsExactly(OAuthProvider.GOOGLE);
    }

    @Test
    void 인증_화면_주소에_client_id_redirect_uri_state_scope가_들어간다() {
        String url = client("s").authorizeUrl(OAuthProvider.GOOGLE, "st-1", "https://banting.example/api/auth/oauth/google/callback");

        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth?response_type=code")
                .contains("client_id=google-id")
                .contains("redirect_uri=https://banting.example/api/auth/oauth/google/callback")
                .contains("state=st-1")
                .contains("scope=openid%20email%20profile")
                .contains("prompt=select_account");
    }

    @Test
    void 카카오_코드를_토큰으로_바꾸고_회원번호와_확인된_이메일을_읽는다() {
        kakaoUserJson = """
                {"id": 4012345678, "kakao_account": {"email": "me@kakao.com", "is_email_valid": true,
                 "is_email_verified": true, "profile": {"nickname": "현우"}}}""";

        OAuthProfile profile = client("kakao-secret").fetchProfile(OAuthProvider.KAKAO, "code-1", "https://cb");

        assertThat(profile).isEqualTo(new OAuthProfile(OAuthProvider.KAKAO, "4012345678", "me@kakao.com", true, "현우"));
        assertThat(lastForm).containsEntry("grant_type", "authorization_code").containsEntry("client_id", "kakao-key")
                .containsEntry("client_secret", "kakao-secret").containsEntry("code", "code-1")
                .containsEntry("redirect_uri", "https://cb");
        assertThat(lastAuthorization.get("kakao")).isEqualTo("Bearer at-1");
    }

    @Test
    void 카카오_이메일이_없거나_확인되지_않았으면_확인된_이메일로_보지_않는다() {
        kakaoUserJson = "{\"id\": 1, \"kakao_account\": {\"profile\": {\"nickname\": \"이웃\"}}}";
        assertThat(client("s").fetchProfile(OAuthProvider.KAKAO, "c", "https://cb"))
                .isEqualTo(new OAuthProfile(OAuthProvider.KAKAO, "1", null, false, "이웃"));

        kakaoUserJson = "{\"id\": 2, \"kakao_account\": {\"email\": \"x@kakao.com\", \"is_email_valid\": true, \"is_email_verified\": false}}";
        assertThat(client("s").fetchProfile(OAuthProvider.KAKAO, "c", "https://cb").emailVerified()).isFalse();
    }

    @Test
    void 구글은_sub와_email_verified를_읽는다() {
        OAuthProfile profile = client("s").fetchProfile(OAuthProvider.GOOGLE, "code-g", "https://cb");

        assertThat(profile).isEqualTo(new OAuthProfile(OAuthProvider.GOOGLE, "1098", "me@gmail.com", true, "조 현우"));
        assertThat(lastForm).containsEntry("client_id", "google-id").containsEntry("client_secret", "google-secret");
    }

    @Test
    void 토큰_교환이_실패하면_OAuthFailedException() {
        tokenStatus = 400;
        assertThatThrownBy(() -> client("s").fetchProfile(OAuthProvider.GOOGLE, "bad", "https://cb"))
                .isInstanceOf(OAuthClient.OAuthFailedException.class);
    }

    @Test
    void 꺼진_공급자는_부를_수_없다() {
        assertThatThrownBy(() -> client("").fetchProfile(OAuthProvider.KAKAO, "c", "https://cb"))
                .isInstanceOf(OAuthClient.OAuthFailedException.class);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
