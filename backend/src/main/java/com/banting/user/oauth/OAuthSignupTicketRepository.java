package com.banting.user.oauth;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OAuthSignupTicketRepository extends JpaRepository<OAuthSignupTicket, Long> {

    Optional<OAuthSignupTicket> findByTokenHash(String tokenHash);

    /** 가입 완료는 대기표를 잠그고 한다 (같은 대기표로 동시에 두 번 가입하지 않게). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM OAuthSignupTicket t WHERE t.tokenHash = :hash")
    Optional<OAuthSignupTicket> findByTokenHashForUpdate(@Param("hash") String hash);

    /** 하루 지난 대기표 정리 (refresh 토큰 정리와 함께). */
    @Modifying
    @Query("DELETE FROM OAuthSignupTicket t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
