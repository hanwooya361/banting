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

    public static User signUp(String email, String passwordHash, String nickname) {
        User user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.nickname = nickname;
        user.role = Role.USER;
        user.searchRadiusM = DEFAULT_SEARCH_RADIUS_M;
        return user;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
