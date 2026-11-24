package com.banting.user.auth;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import com.banting.config.JwtProperties;
import com.banting.user.User;
import com.banting.user.UserRepository;
import com.banting.user.auth.AuthDtos.IssuedTokens;
import com.banting.user.auth.AuthDtos.SignupRequest;
import com.banting.user.auth.AuthDtos.SignupResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    // 없는 이메일로 로그인할 때도 BCrypt 비교를 한 번 해서 응답 시간으로 가입 여부를 추측하지 못하게 한다
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
                       TokenService tokenService, PasswordEncoder passwordEncoder,
                       JwtProperties jwtProperties, Clock clock) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        String email = normalizeEmail(request.email());
        String nickname = request.nickname().trim();

        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_DUPLICATED);
        }
        if (userRepository.existsByNickname(nickname)) {
            throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
        }

        User user = User.signUp(email, passwordEncoder.encode(request.password()), nickname,
                LocalDateTime.now(clock));   // 요청 검증(@AssertTrue)을 통과했으므로 지금 동의한 것
        try {
            // 위 확인과 저장 사이에 같은 이메일로 동시에 가입하면 UNIQUE 제약이 막는다 → 같은 409로 응답
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            String message = String.valueOf(e.getMostSpecificCause().getMessage());
            throw new BusinessException(message.contains("uk_users_nickname")
                    ? ErrorCode.NICKNAME_DUPLICATED : ErrorCode.EMAIL_DUPLICATED);
        }
        return new SignupResponse(user.getId(), user.getEmail(), user.getNickname());
    }

    @Transactional
    public IssuedTokens login(String rawEmail, String password) {
        User user = userRepository.findByEmail(normalizeEmail(rawEmail)).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyPasswordHash);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        // 이메일 없음·비밀번호 틀림·탈퇴 회원·간편 가입 회원(비밀번호 없음)을 같은 오류로 응답 (가입 여부 노출 방지)
        if (!user.hasPassword() || !passwordEncoder.matches(password, user.getPasswordHash()) || user.isDeleted()) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        return issueTokens(user);
    }

    /**
     * refresh 토큰으로 새 토큰 쌍을 발급하고 기존 refresh는 폐기한다 (rotation).
     * 이미 폐기된 토큰이 다시 들어오면 탈취로 보고 그 회원의 모든 refresh를 폐기한다.
     * noRollbackFor: 탈취 탐지 후 예외를 던져도 "전부 폐기"는 커밋돼야 하므로 롤백하지 않는다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public IssuedTokens refresh(String refreshTokenValue) {
        if (refreshTokenValue == null || refreshTokenValue.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        RefreshToken token = refreshTokenRepository.findByTokenHash(tokenService.hash(refreshTokenValue))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));

        if (token.isExpired(now)) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        // 조건부 UPDATE가 0건 = 이미 폐기된 토큰(재사용) 또는 동시에 다른 요청이 먼저 사용
        if (token.isRevoked() || refreshTokenRepository.revokeIfActive(token.getId(), now) == 0) {
            int revoked = refreshTokenRepository.revokeAllOfUser(token.getUserId(), now);
            log.warn("폐기된 refresh 토큰 재사용 감지: userId={}, 추가 폐기 {}건", token.getUserId(), revoked);
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        User user = userRepository.findById(token.getUserId())
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
        return issueTokens(user);
    }

    /** 로그아웃: 쿠키의 refresh를 폐기. 이미 없거나 폐기된 토큰이어도 성공으로 처리 (멱등). */
    @Transactional
    public void logout(String refreshTokenValue) {
        if (refreshTokenValue == null || refreshTokenValue.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(tokenService.hash(refreshTokenValue))
                .ifPresent(token -> refreshTokenRepository.revokeIfActive(token.getId(), LocalDateTime.now(clock)));
    }

    /** 로그인 성공 처리: refresh 저장 + access 발급. 비밀번호 로그인과 간편 가입·로그인(T23)이 같이 쓴다. */
    @Transactional
    public IssuedTokens issueTokens(User user) {
        String refreshValue = tokenService.createRefreshTokenValue();
        LocalDateTime expiresAt = LocalDateTime.now(clock).plus(jwtProperties.refreshTtl());
        refreshTokenRepository.save(RefreshToken.issue(user.getId(), tokenService.hash(refreshValue), expiresAt));
        return new IssuedTokens(tokenService.createAccessToken(user), jwtProperties.accessTtl(),
                refreshValue, jwtProperties.refreshTtl());
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
