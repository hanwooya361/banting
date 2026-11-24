package com.banting.user;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import com.banting.notification.NotificationRepository;
import com.banting.participation.ParticipationRepository;
import com.banting.user.auth.RefreshTokenRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴 (T22, tech-design 4장 "회원 탈퇴").
 *
 * <ol>
 *   <li>비밀번호 재확인 (자리를 비운 사이 다른 사람이 누르는 것 방지)</li>
 *   <li>진행 중인 공구·환불이 있으면 거부 — 돈이나 수령이 걸린 상태에서 상대가 연락할 사람이 사라지지 않게</li>
 *   <li>개인정보 익명화(이메일·닉네임·비밀번호·좌표·동네), 알림 삭제, refresh 토큰 전부 폐기</li>
 * </ol>
 * 회원 행은 지우지 않는다: 참여·결제·정산이 회원을 가리키고, 거래 이력은 남겨야 하기 때문 (개인정보처리방침 3항).
 * 한 트랜잭션이라 중간에 실패하면 아무것도 바뀌지 않는다. 이미 발급된 access 토큰(최대 30분)은 서버가 기억하지 않아
 * 바로 막지 못하지만, 내 정보 조회는 탈퇴 회원을 404로 막고 화면은 탈퇴 즉시 로그아웃한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private final UserRepository userRepository;
    private final ParticipationRepository participationRepository;
    private final NotificationRepository notificationRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Transactional
    public void withdraw(Long userId, String password) {
        userRepository.lockById(userId);   // 같은 회원의 동시 탈퇴 요청을 한 줄로
        User user = userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }
        if (participationRepository.existsBlockingWithdrawal(userId)) {
            throw new BusinessException(ErrorCode.WITHDRAWAL_BLOCKED);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        user.withdraw(now);
        userRepository.clearLocation(userId);
        notificationRepository.deleteByUserId(userId);
        refreshTokenRepository.revokeAllOfUser(userId, now);
        log.info("회원 탈퇴 userId={}", userId);
    }
}
