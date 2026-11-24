package com.banting.user.oauth;

import java.util.Set;

/**
 * 카카오·구글과 주고받는 부분만 모은 인터페이스 (T23). 실제 구현은 {@link HttpOAuthClient},
 * 통합 테스트는 가짜 구현으로 바꿔 네트워크 없이 가입·로그인 흐름을 검증한다 (T07 RegionClient와 같은 방식).
 */
public interface OAuthClient {

    /** 키가 설정된 공급자만. 화면은 이 목록에 있는 버튼만 보여 준다. */
    Set<OAuthProvider> enabledProviders();

    /** 사용자를 보낼 공급자 로그인·동의 화면 주소. */
    String authorizeUrl(OAuthProvider provider, String state, String redirectUri);

    /** 인가 코드 → 토큰 → 사용자 정보. 실패하면 {@link OAuthFailedException}. */
    OAuthProfile fetchProfile(OAuthProvider provider, String code, String redirectUri);

    class OAuthFailedException extends RuntimeException {
        public OAuthFailedException(String message) {
            super(message);
        }

        public OAuthFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
