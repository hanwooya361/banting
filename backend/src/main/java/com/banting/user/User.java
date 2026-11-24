package com.banting.user;

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
 * 회원. location(좌표) 컬럼은 동네 설정(T07)에서 매핑한다.
 * 생성은 정적 팩토리 signUp으로만 해서 기본값(권한 USER, 반경 1000m)이 빠지지 않게 한다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    public static final int DEFAULT_SEARCH_RADIUS_M = 1000;
    /** 탈퇴한 회원의 비밀번호 칸. BCrypt 형식이 아니라 어떤 비밀번호와도 일치하지 않는다 (데모 회원과 같은 방식). */
    static final String WITHDRAWN_PASSWORD_HASH = "!withdrawn-login-disabled";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 20)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role;

    @Column(name = "neighborhood_name", length = 50)
    private String neighborhoodName;

    @Column(name = "search_radius_m", nullable = false)
    private int searchRadiusM;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** 개인정보처리방침 동의 시각 (T21). 이 기능 전 가입자는 null. */
    @Column(name = "privacy_agreed_at")
    private LocalDateTime privacyAgreedAt;

    public static User signUp(String email, String passwordHash, String nickname, LocalDateTime privacyAgreedAt) {
        User user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.nickname = nickname;
        user.role = Role.USER;
        user.searchRadiusM = DEFAULT_SEARCH_RADIUS_M;
        user.privacyAgreedAt = privacyAgreedAt;
        return user;
    }

    /**
     * 탈퇴 (T22): 행은 남기고(결제·정산 기록이 가리킴) 개인정보만 지운다. 이메일·닉네임은 id로 만든 값으로 바꿔
     * UNIQUE를 지키면서 같은 이메일·닉네임으로 다시 가입할 수 있게 한다. 비밀번호 칸은 BCrypt가 아닌 값이라 로그인 불가.
     * 좌표(location)는 JPA에 매핑하지 않으므로 UserRepository.clearLocation이 지운다.
     */
    public void withdraw(LocalDateTime now) {
        if (isDeleted()) {
            throw new IllegalStateException("이미 탈퇴한 회원: " + id);
        }
        this.email = "deleted-" + id + "@deleted.banting.invalid";
        this.nickname = "탈퇴회원" + id;
        this.passwordHash = WITHDRAWN_PASSWORD_HASH;
        this.neighborhoodName = null;
        this.deletedAt = now;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
