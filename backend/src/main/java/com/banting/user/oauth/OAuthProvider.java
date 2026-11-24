package com.banting.user.oauth;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** 간편 가입 공급자 (T23). path는 주소에 쓰는 이름 (/api/auth/oauth/kakao/...). */
public enum OAuthProvider {
    KAKAO,
    GOOGLE;

    public String path() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<OAuthProvider> fromPath(String path) {
        return Arrays.stream(values()).filter(p -> p.path().equals(path)).findFirst();
    }
}
