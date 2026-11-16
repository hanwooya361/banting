package com.banting.user.auth;

import com.banting.common.BusinessException;
import com.banting.common.ErrorResponse;
import com.banting.user.auth.AuthDtos.IssuedTokens;
import com.banting.user.auth.AuthDtos.LoginRequest;
import com.banting.user.auth.AuthDtos.SignupRequest;
import com.banting.user.auth.AuthDtos.SignupResponse;
import com.banting.user.auth.AuthDtos.TokenResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API.
 * refresh 토큰은 HttpOnly 쿠키로만 주고받는다: 자바스크립트가 읽을 수 없어 XSS로 훔칠 수 없고,
 * SameSite=Strict + Path=/api/auth라 다른 사이트에서 온 요청이나 다른 API 호출에는 실리지 않는다.
 * access 토큰은 응답 본문으로 주고, 프론트는 메모리에만 들고 있다가 Authorization 헤더로 보낸다.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    static final String REFRESH_COOKIE = "refresh_token";
    private static final String REFRESH_COOKIE_PATH = "/api/auth";

    private final AuthService authService;

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.signup(request));
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return withRefreshCookie(authService.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        try {
            return withRefreshCookie(authService.refresh(refreshToken));
        } catch (BusinessException e) {
            // 쓸 수 없는 쿠키는 지워서 브라우저가 계속 보내지 않게 한다
            return ResponseEntity.status(e.getErrorCode().getStatus())
                    .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                    .body(ErrorResponse.of(e.getErrorCode()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .build();
    }

    private ResponseEntity<TokenResponse> withRefreshCookie(IssuedTokens tokens) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken(), tokens.refreshTtl()).toString())
                .body(tokens.toResponse());
    }

    private static ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(true)          // HTTPS에서만 전송 (localhost는 브라우저가 예외로 허용)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
