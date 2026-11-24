package com.banting.user.oauth;

import com.banting.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 처음 온 소셜 사용자의 가입 대기표 (T23). 동의·닉네임 화면을 거친 뒤에만 회원을 만든다.
 * 쿠키에는 무작위 원문, DB에는 SHA-256 해시 (refresh 토큰과 같은 방식). 10분 안에 한 번만 쓸 수 있다.
 * JWT로 서명하지 않은 이유: access 토큰과 같은 키로 서명하면 대기표가 access 토큰으로 오인될 수 있다.
 */
@Entity
@Table(name = "oauth_signup_tickets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OAuthSignupTicket extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OAuthProvider provider;

    @Column(name = "provider_user_id", nullable = false, length = 100)
    private String providerUserId;

    /** 공급자가 확인한 이메일만. 확인 안 된 이메일은 저장하지 않는다. */
    @Column(length = 100)
    private String email;

    @Column(name = "suggested_nickname", length = 20)
    private String suggestedNickname;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    public static OAuthSignupTicket issue(String tokenHash, OAuthProfile profile, String suggestedNickname,
                                          LocalDateTime expiresAt) {
        OAuthSignupTicket ticket = new OAuthSignupTicket();
        ticket.tokenHash = tokenHash;
        ticket.provider = profile.provider();
        ticket.providerUserId = profile.providerUserId();
        ticket.email = profile.emailVerified() ? profile.email() : null;
        ticket.suggestedNickname = suggestedNickname;
        ticket.expiresAt = expiresAt;
        return ticket;
    }

    public boolean isUsable(LocalDateTime now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public void use(LocalDateTime now) {
        if (!isUsable(now)) {
            throw new IllegalStateException("쓸 수 없는 가입 대기표");
        }
        this.usedAt = now;
    }
}
