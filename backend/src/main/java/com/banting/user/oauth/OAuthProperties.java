package com.banting.user.oauth;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 간편 가입 설정 (T23, application.yml banting.oauth). 키는 local: application-secret.yml / prod: Parameter Store → .env.
 *
 * @param baseUrl   사용자가 보는 사이트 주소 (콜백 주소·로그인 후 이동 주소를 만든다). local http://localhost:5180, prod CloudFront
 * @param providers 공급자별 설정. client-id와 client-secret이 모두 있어야 켜진다
 */
@ConfigurationProperties(prefix = "banting.oauth")
public record OAuthProperties(String baseUrl, Map<OAuthProvider, Provider> providers) {

    public record Provider(String clientId, String clientSecret, String authorizeUri, String tokenUri,
                           String userInfoUri, String scope) {

        public boolean enabled() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }

    public OAuthProperties {
        providers = providers == null ? Map.of() : providers;
    }

    public String redirectUri(OAuthProvider provider) {
        return baseUrl + "/api/auth/oauth/" + provider.path() + "/callback";
    }
}
