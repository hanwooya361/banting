package com.banting.user;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Valid;
import com.banting.user.oauth.OAuthProvider;
import com.banting.user.oauth.SocialAccount;
import com.banting.user.oauth.SocialAccountRepository;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final WithdrawalService withdrawalService;
    private final SocialAccountRepository socialAccountRepository;

    /** 비밀번호 회원은 password, 간편 가입 회원(T23)은 confirmText("탈퇴"). */
    public record WithdrawalRequest(String password, String confirmText) {
    }

    /** 회원 탈퇴 (T22). 성공하면 204, 화면은 바로 로그아웃한다. */
    @PostMapping("/me/withdrawal")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WithdrawalRequest request) {
        withdrawalService.withdraw(Long.valueOf(jwt.getSubject()), request.password(), request.confirmText());
        return ResponseEntity.noContent().build();
    }

    /** 내 정보. JWT의 sub에 회원 id가 들어 있다. */
    @GetMapping("/me")
    @Transactional(readOnly = true)
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        User user = userRepository.findById(userId)
                .filter(u -> !u.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        List<OAuthProvider> providers = socialAccountRepository.findByUserId(userId).stream()
                .map(SocialAccount::getProvider).sorted().toList();
        return MeResponse.from(user, providers);
    }

    /** 좌표는 개인정보라 내려주지 않고 동네 이름만 (CLAUDE.md 원칙). */
    /**
     * @param email           간편 가입(카카오)이면 null일 수 있다 (T23)
     * @param hasPassword     비밀번호 로그인이 되는 회원인지. 탈퇴 화면이 비밀번호 대신 "탈퇴" 입력을 받을지 정한다
     * @param socialProviders 연결된 간편 로그인
     */
    public record MeResponse(Long id, String email, String nickname, Role role,
                             String neighborhoodName, int searchRadiusM, boolean hasPassword,
                             List<OAuthProvider> socialProviders) {

        public static MeResponse from(User user, List<OAuthProvider> socialProviders) {
            return new MeResponse(user.getId(), user.getEmail(), user.getNickname(), user.getRole(),
                    user.getNeighborhoodName(), user.getSearchRadiusM(), user.hasPassword(), socialProviders);
        }
    }
}
