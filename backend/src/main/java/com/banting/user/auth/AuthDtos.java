package com.banting.user.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;

/** 인증 API 요청·응답 DTO. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record SignupRequest(
            @NotBlank(message = "이메일을 입력해 주세요")
            @Email(message = "이메일 형식이 아니에요")
            @Size(max = 100, message = "이메일은 100자까지 쓸 수 있어요")
            String email,

            @NotBlank(message = "비밀번호를 입력해 주세요")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                    message = "비밀번호는 영문과 숫자를 섞어 8~64자로 만들어 주세요")
            String password,

            @NotBlank(message = "닉네임을 입력해 주세요")
            @Pattern(regexp = "^[가-힣A-Za-z0-9]{2,20}$",
                    message = "닉네임은 한글·영문·숫자 2~20자로 만들어 주세요")
            String nickname,

            // 개인정보처리방침 동의 (T21). 빠지거나 false면 가입하지 않는다 (화면 체크만으로는 우회 가능하므로 서버에서)
            @NotNull(message = "개인정보처리방침에 동의해 주세요")
            @AssertTrue(message = "개인정보처리방침에 동의해 주세요")
            Boolean agreePrivacy) {

        // 검증(@Email 등)보다 먼저 실행되므로, 복사·붙여넣기로 붙은 앞뒤 공백 때문에 거부되지 않게 정리
        public SignupRequest {
            email = trim(email);
            nickname = trim(nickname);
        }
    }

    public record SignupResponse(Long id, String email, String nickname) {
    }

    public record LoginRequest(
            @NotBlank(message = "이메일을 입력해 주세요") String email,
            @NotBlank(message = "비밀번호를 입력해 주세요") String password) {

        public LoginRequest {
            email = trim(email);
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    /** refresh 토큰은 본문이 아니라 HttpOnly 쿠키로만 내려간다. */
    public record TokenResponse(String accessToken, long expiresIn) {
    }

    /** 서비스 → 컨트롤러 전달용: 쿠키에 넣을 refresh 원문 포함. */
    public record IssuedTokens(String accessToken, Duration accessTtl, String refreshToken, Duration refreshTtl) {

        public TokenResponse toResponse() {
            return new TokenResponse(accessToken, accessTtl.toSeconds());
        }
    }
}
