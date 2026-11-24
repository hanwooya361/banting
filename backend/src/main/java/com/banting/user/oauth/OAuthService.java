package com.banting.user.oauth;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import com.banting.user.User;
import com.banting.user.UserRepository;
import com.banting.user.auth.AuthDtos.IssuedTokens;
import com.banting.user.auth.AuthService;
import com.banting.user.auth.TokenService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 카카오·구글 간편 가입·로그인 (T23, tech-design 4장 "간편 가입").
 *
 * <p>콜백에서 공급자 프로필을 받은 뒤(트랜잭션 밖) 셋 중 하나:
 * <ol>
 *   <li>이미 연결된 소셜 계정 → 그 회원으로 로그인</li>
 *   <li>공급자가 확인한 이메일이 기존 회원과 같음 → 그 회원에 연결하고 로그인 (한 사람이 계정 두 개가 되지 않게)</li>
 *   <li>처음 온 사람 → 가입 대기표(10분) → 동의·닉네임 화면 → {@link #complete}에서 회원 생성</li>
 * </ol>
 * 확인되지 않은 이메일로는 기존 계정에 연결하지 않는다 (남의 이메일을 적은 계정으로 남의 계정에 들어가는 것 방지).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthService {

    static final Duration TICKET_TTL = Duration.ofMinutes(10);
    private static final int NICKNAME_MIN = 2;
    private static final int NICKNAME_MAX = 20;

    private final OAuthClient oAuthClient;
    private final OAuthProperties properties;
    private final SocialAccountRepository socialAccountRepository;
    private final OAuthSignupTicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final AuthService authService;
    private final TokenService tokenService;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Value("${banting.scheduler.enabled:false}")
    private boolean schedulerEnabled;

    /** 콜백 결과: 로그인됨(tokens) 또는 가입 필요(ticket 원문). */
    public sealed interface CallbackResult permits LoggedIn, NeedsSignup {
    }

    public record LoggedIn(IssuedTokens tokens) implements CallbackResult {
    }

    public record NeedsSignup(String ticket) implements CallbackResult {
    }

    public record SignupInfo(OAuthProvider provider, String suggestedNickname, String email) {
    }

    public boolean isEnabled(OAuthProvider provider) {
        return oAuthClient.enabledProviders().contains(provider);
    }

    public String authorizeUrl(OAuthProvider provider, String state) {
        return oAuthClient.authorizeUrl(provider, state, properties.redirectUri(provider));
    }

    /** 공급자 호출은 트랜잭션 밖 (느린 외부 호출 동안 DB 커넥션을 잡지 않게). */
    public CallbackResult callback(OAuthProvider provider, String code) {
        OAuthProfile profile = oAuthClient.fetchProfile(provider, code, properties.redirectUri(provider));
        return tx.execute(status -> handleProfile(profile));
    }

    private CallbackResult handleProfile(OAuthProfile profile) {
        Optional<User> linked = socialAccountRepository
                .findByProviderAndProviderUserId(profile.provider(), profile.providerUserId())
                .flatMap(account -> userRepository.findById(account.getUserId()))
                .filter(user -> !user.isDeleted());
        if (linked.isPresent()) {
            return new LoggedIn(authService.issueTokens(linked.get()));
        }
        if (profile.emailVerified() && profile.email() != null) {
            Optional<User> sameEmail = userRepository.findByEmail(normalize(profile.email())).filter(u -> !u.isDeleted());
            if (sameEmail.isPresent()) {
                socialAccountRepository.save(
                        SocialAccount.link(sameEmail.get().getId(), profile.provider(), profile.providerUserId()));
                log.info("소셜 계정 연결 userId={} provider={}", sameEmail.get().getId(), profile.provider());
                return new LoggedIn(authService.issueTokens(sameEmail.get()));
            }
        }
        String ticket = tokenService.createRefreshTokenValue();   // 같은 방식의 무작위 값 (256비트)
        ticketRepository.save(OAuthSignupTicket.issue(tokenService.hash(ticket),
                profile.emailVerified() && profile.email() != null ? withEmail(profile) : profile,
                suggestNickname(profile.nickname()), LocalDateTime.now(clock).plus(TICKET_TTL)));
        return new NeedsSignup(ticket);
    }

    public SignupInfo signupInfo(String ticket) {
        OAuthSignupTicket found = usableTicket(ticket, false);
        return new SignupInfo(found.getProvider(), found.getSuggestedNickname(), found.getEmail());
    }

    /** 동의·닉네임을 받아 회원 생성 + 소셜 연결 + 로그인. 대기표를 잠가 한 번만. */
    public IssuedTokens complete(String ticket, String nickname) {
        String trimmed = nickname.trim();
        try {
            return tx.execute(status -> {
                OAuthSignupTicket found = usableTicket(ticket, true);
                LocalDateTime now = LocalDateTime.now(clock);
                if (userRepository.existsByNickname(trimmed)) {
                    throw new BusinessException(ErrorCode.NICKNAME_DUPLICATED);
                }
                // 대기표를 받은 뒤 같은 이메일로 누가 가입했으면 이메일 없이 만든다 (이메일은 하나의 계정에만)
                String email = found.getEmail() != null && !userRepository.existsByEmail(found.getEmail())
                        ? found.getEmail() : null;
                User user = userRepository.saveAndFlush(User.signUpSocial(email, trimmed, now));
                socialAccountRepository.save(SocialAccount.link(user.getId(), found.getProvider(), found.getProviderUserId()));
                found.use(now);
                log.info("간편 가입 userId={} provider={}", user.getId(), found.getProvider());
                return authService.issueTokens(user);
            });
        } catch (DataIntegrityViolationException e) {
            // 닉네임 동시 가입(UNIQUE) 또는 같은 소셜 계정이 그 사이 연결됨
            String message = String.valueOf(e.getMostSpecificCause().getMessage());
            throw new BusinessException(message.contains("uk_users_nickname")
                    ? ErrorCode.NICKNAME_DUPLICATED : ErrorCode.OAUTH_TICKET_INVALID);
        }
    }

    private OAuthSignupTicket usableTicket(String ticket, boolean lock) {
        if (ticket == null || ticket.isBlank()) {
            throw new BusinessException(ErrorCode.OAUTH_TICKET_INVALID);
        }
        String hash = tokenService.hash(ticket);
        return (lock ? ticketRepository.findByTokenHashForUpdate(hash) : ticketRepository.findByTokenHash(hash))
                .filter(t -> t.isUsable(LocalDateTime.now(clock)))
                .orElseThrow(() -> new BusinessException(ErrorCode.OAUTH_TICKET_INVALID));
    }

    private static OAuthProfile withEmail(OAuthProfile profile) {
        return new OAuthProfile(profile.provider(), profile.providerUserId(), normalize(profile.email()), true,
                profile.nickname());
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** 공급자 닉네임을 반띵 규칙(한글·영문·숫자 2~20자)에 맞게. 맞출 수 없으면 null → 사용자가 직접 입력. */
    static String suggestNickname(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[^가-힣A-Za-z0-9]", "");
        if (cleaned.length() < NICKNAME_MIN) {
            return null;
        }
        return cleaned.length() > NICKNAME_MAX ? cleaned.substring(0, NICKNAME_MAX) : cleaned;
    }

    /** 하루 지난 가입 대기표 정리. 매일 04:05 (refresh 토큰 정리 04:00 다음). 스케줄러가 꺼져 있으면 안 돎. */
    @Scheduled(cron = "0 5 4 * * *", zone = "Asia/Seoul")
    void cleanTickets() {
        if (schedulerEnabled) {
            tx.executeWithoutResult(status ->
                    ticketRepository.deleteExpiredBefore(LocalDateTime.now(clock).minusDays(1)));
        }
    }
}
