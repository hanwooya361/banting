package com.banting.user.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 카카오·구글 OAuth 2.0 인가 코드 방식 (T23).
 * ① 사용자를 공급자 화면으로 보냄(authorizeUrl) → ② 돌아온 code로 access token 교환 → ③ 사용자 정보 조회.
 * 토큰은 사용자 정보를 한 번 읽는 데만 쓰고 저장하지 않는다 (카카오·구글 API를 대신 부를 일이 없음).
 * 타임아웃: 연결 2초·응답 5초 (카카오 로컬 API와 같은 수준). DB 트랜잭션 밖에서 부른다.
 */
@Slf4j
@Component
public class HttpOAuthClient implements OAuthClient {

    private final OAuthProperties properties;
    private final RestClient restClient;

    public HttpOAuthClient(OAuthProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        Set<OAuthProvider> enabled = enabledProviders();
        log.info("간편 가입 공급자: {}", enabled.isEmpty() ? "없음 (키 미설정)"
                : enabled.stream().map(Enum::name).collect(Collectors.joining(", ")));
    }

    @Override
    public Set<OAuthProvider> enabledProviders() {
        Set<OAuthProvider> enabled = EnumSet.noneOf(OAuthProvider.class);
        properties.providers().forEach((provider, settings) -> {
            if (settings.enabled()) {
                enabled.add(provider);
            }
        });
        return enabled;
    }

    @Override
    public String authorizeUrl(OAuthProvider provider, String state, String redirectUri) {
        OAuthProperties.Provider settings = settings(provider);
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(settings.authorizeUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", settings.clientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", state);
        if (settings.scope() != null && !settings.scope().isBlank()) {
            uri.queryParam("scope", settings.scope());
        }
        if (provider == OAuthProvider.GOOGLE) {
            uri.queryParam("prompt", "select_account");   // 구글 계정이 여러 개면 고르게
        }
        return uri.encode().build().toUriString();
    }

    @Override
    public OAuthProfile fetchProfile(OAuthProvider provider, String code, String redirectUri) {
        OAuthProperties.Provider settings = settings(provider);
        try {
            String accessToken = exchangeCode(settings, code, redirectUri);
            return provider == OAuthProvider.KAKAO
                    ? kakaoProfile(settings, accessToken)
                    : googleProfile(settings, accessToken);
        } catch (RestClientException e) {
            throw new OAuthFailedException(provider + " 응답 실패: " + e.getMessage(), e);
        }
    }

    private String exchangeCode(OAuthProperties.Provider settings, String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", settings.clientId());
        form.add("client_secret", settings.clientSecret());
        form.add("redirect_uri", redirectUri);
        form.add("code", code);
        TokenResponse token = restClient.post().uri(settings.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        if (token == null || token.accessToken() == null) {
            throw new OAuthFailedException("access token 없음");
        }
        return token.accessToken();
    }

    private OAuthProfile kakaoProfile(OAuthProperties.Provider settings, String accessToken) {
        KakaoUser user = restClient.get().uri(settings.userInfoUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(KakaoUser.class);
        if (user == null || user.id() == null) {
            throw new OAuthFailedException("카카오 회원번호 없음");
        }
        KakaoAccount account = user.account();
        String email = account == null ? null : account.email();
        // 카카오는 유효(is_email_valid)하고 인증된(is_email_verified) 이메일일 때만 소유 확인으로 본다
        boolean verified = account != null && email != null
                && Boolean.TRUE.equals(account.emailValid()) && Boolean.TRUE.equals(account.emailVerified());
        String nickname = account == null || account.profile() == null ? null : account.profile().nickname();
        return new OAuthProfile(OAuthProvider.KAKAO, String.valueOf(user.id()), email, verified, nickname);
    }

    private OAuthProfile googleProfile(OAuthProperties.Provider settings, String accessToken) {
        GoogleUser user = restClient.get().uri(settings.userInfoUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(GoogleUser.class);
        if (user == null || user.sub() == null) {
            throw new OAuthFailedException("구글 sub 없음");
        }
        return new OAuthProfile(OAuthProvider.GOOGLE, user.sub(), user.email(),
                Boolean.TRUE.equals(user.emailVerified()), user.name());
    }

    private OAuthProperties.Provider settings(OAuthProvider provider) {
        OAuthProperties.Provider settings = properties.providers().get(provider);
        if (settings == null || !settings.enabled()) {
            throw new OAuthFailedException(provider + " 설정 없음");
        }
        return settings;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaoUser(Long id, @JsonProperty("kakao_account") KakaoAccount account) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaoAccount(String email, @JsonProperty("is_email_valid") Boolean emailValid,
                        @JsonProperty("is_email_verified") Boolean emailVerified, KakaoProfile profile) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaoProfile(String nickname) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GoogleUser(String sub, String email, @JsonProperty("email_verified") Boolean emailVerified, String name) {
    }
}
