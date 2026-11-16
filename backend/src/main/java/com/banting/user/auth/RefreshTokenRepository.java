package com.banting.user.auth;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 아직 폐기되지 않은 경우에만 폐기한다. 반환값 1 = 내가 폐기함, 0 = 이미 누군가 폐기함.
     * "조회 후 수정"으로 하면 같은 토큰으로 동시에 두 번 재발급 요청이 왔을 때 둘 다 성공해 토큰이 두 갈래로 갈라진다.
     * 조건부 UPDATE 한 문장은 DB가 원자적으로 처리하므로 둘 중 하나만 1을 받는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now, t.updatedAt = :now "
            + "WHERE t.id = :id AND t.revokedAt IS NULL")
    int revokeIfActive(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 탈취 의심(폐기된 토큰 재사용) 시 그 회원의 살아 있는 토큰을 전부 폐기 → 모든 기기 로그아웃. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now, t.updatedAt = :now "
            + "WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeAllOfUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
