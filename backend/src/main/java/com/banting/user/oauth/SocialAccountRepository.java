package com.banting.user.oauth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {

    Optional<SocialAccount> findByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);

    List<SocialAccount> findByUserId(Long userId);

    /** 탈퇴 (T22·T23): 연결을 끊어 같은 소셜 계정으로 다시 가입할 수 있게. */
    @Modifying
    @Query("DELETE FROM SocialAccount s WHERE s.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
