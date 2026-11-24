package com.banting.user.oauth;

import com.banting.user.auth.AuthController;
import com.banting.user.auth.AuthDtos.IssuedTokens;
import com.banting.user.auth.AuthDtos.TokenResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 간편 가입·로그인 (T23). 브라우저가 직접 이동하는 주소(start, callback)와 화면이 부르는 API(signup-info, complete).
 *
 * <p>쿠키 (모두 HttpOnly·Secure, 경로 /api/auth/oauth, 10분)
 * <ul>
 *   <li>state: 위조 요청(CSRF) 방지. 시작 때 굽고 콜백의 state 값과 비교. 카카오·구글에서 돌아오는 이동에도 실려야 해서 SameSite=Lax</li>
 *   <li>next: 로그인 후 돌아갈 우리 사이트 경로</li>
 *   <li>ticket: 처음 온 사람의 가입 대기표 원문</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/auth/oauth")
@RequiredArgsConstructor
public class OAuthController {

    public static final String STATE_COOKIE = "oauth_state";
    public static final String NEXT_COOKIE = "oauth_next";
    public static final String TICKET_COOKIE = "oauth_ticket";
    private static final String COOKIE_PATH = "/api/auth/oauth";
    private static final Duration COOKIE_TTL = Duration.ofMinutes(10);
    private static final java.util.regex.Pattern SAFE_PATH = java.util.regex.Pattern.compile("^/(?!/)\\S{0,199}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OAuthService oAuthService;
    private final OAuthProperties properties;

    public record ProvidersResponse(List<String> providers) {
    }

    public record CompleteRequest(
            @NotBlank(message = "닉네임을 입력해 주세요")
            @Pattern(regexp = "^[가-힣A-Za-z0-9]{2,20}$", message = "닉네임은 한글·영문·숫자 2~20자로 만들어 주세요")
            String nickname,
            @NotNull(message = "개인정보처리방침에 동의해 주세요")
            @AssertTrue(message = "개인정보처리방침에 동의해 주세요")
            Boolean agreePrivacy) {
    }

    /** 켜진 공급자 (키가 설정된 것만). 화면은 이 버튼만 보여 준다. */
    @GetMapping("/providers")
    public ProvidersResponse providers() {
        return new ProvidersResponse(java.util.Arrays.stream(OAuthProvider.values())
                .filter(oAuthService::isEnabled).map(OAuthProvider::path).toList());
    }

    @GetMapping("/{provider}/start")
    public ResponseEntity<Void> start(@PathVariable String provider, @RequestParam(required = false) String next) {
        OAuthProvider p = OAuthProvider.fromPath(provider).filter(oAuthService::isEnabled).orElse(null);
        if (p == null) {
            return redirect(properties.baseUrl() + "/login?socialError=disabled");
        }
        String state = randomValue();
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(oAuthService.authorizeUrl(p, state)))
                .header(HttpHeaders.SET_COOKIE, cookie(STATE_COOKIE, state, COOKIE_TTL).toString())
                .header(HttpHeaders.SET_COOKIE, cookie(NEXT_COOKIE, safeNext(next), COOKIE_TTL).toString())
                .build();
    }

    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> callback(@PathVariable String provider,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         @CookieValue(name = STATE_COOKIE, required = false) String savedState,
                                         @CookieValue(name = NEXT_COOKIE, required = false) String savedNext) {
        String next = safeNext(savedNext);
        OAuthProvider p = OAuthProvider.fromPath(provider).orElse(null);
        if (error != null) {
            return fail("canceled");   // 사용자가 동의 화면에서 취소
        }
        if (p == null || savedState == null || state == null || !savedState.equals(state) || code == null) {
            return fail("state");
        }
        try {
            OAuthService.CallbackResult result = oAuthService.callback(p, code);
            ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.SET_COOKIE, cookie(STATE_COOKIE, "", Duration.ZERO).toString())
                    .header(HttpHeaders.SET_COOKIE, cookie(NEXT_COOKIE, "", Duration.ZERO).toString());
            if (result instanceof OAuthService.LoggedIn loggedIn) {
                IssuedTokens tokens = loggedIn.tokens();
                return response.location(URI.create(properties.baseUrl() + "/auth/social?next=" + encode(next)))
                        .header(HttpHeaders.SET_COOKIE,
                                AuthController.refreshCookie(tokens.refreshToken(), tokens.refreshTtl()).toString())
                        .build();
            }
            String ticket = ((OAuthService.NeedsSignup) result).ticket();
            return response.location(URI.create(properties.baseUrl() + "/auth/social/signup?next=" + encode(next)))
                    .header(HttpHeaders.SET_COOKIE, cookie(TICKET_COOKIE, ticket, OAuthService.TICKET_TTL).toString())
                    .build();
        } catch (OAuthClient.OAuthFailedException e) {
            log.warn("간편 로그인 실패 provider={}: {}", p, e.getMessage());
            return fail("provider");
        }
    }

    @GetMapping("/signup-info")
    public OAuthService.SignupInfo signupInfo(@CookieValue(name = TICKET_COOKIE, required = false) String ticket) {
        return oAuthService.signupInfo(ticket);
    }

    @PostMapping("/complete")
    public ResponseEntity<TokenResponse> complete(@CookieValue(name = TICKET_COOKIE, required = false) String ticket,
                                                  @Valid @RequestBody CompleteRequest request) {
        IssuedTokens tokens = oAuthService.complete(ticket, request.nickname());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, AuthController.refreshCookie(tokens.refreshToken(), tokens.refreshTtl()).toString())
                .header(HttpHeaders.SET_COOKIE, cookie(TICKET_COOKIE, "", Duration.ZERO).toString())
                .body(tokens.toResponse());
    }

    private ResponseEntity<Void> fail(String reason) {
        return redirect(properties.baseUrl() + "/login?socialError=" + reason);
    }

    private static ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    /** 로그인 후 돌아갈 곳은 우리 사이트 경로만 (/로 시작, //·주소 아님). 다른 사이트로 보내는 열린 리다이렉트 방지. */
    static String safeNext(String next) {
        return next != null && SAFE_PATH.matcher(next).matches() ? next : "/";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String randomValue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ResponseCookie cookie(String name, String value, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
