package com.banting.user;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

    public record WithdrawalRequest(@NotBlank(message = "비밀번호를 입력해 주세요") String password) {
    }

    /** 회원 탈퇴 (T22). 성공하면 204, 화면은 바로 로그아웃한다. */
    @PostMapping("/me/withdrawal")
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WithdrawalRequest request) {
        withdrawalService.withdraw(Long.valueOf(jwt.getSubject()), request.password());
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
        return MeResponse.from(user);
    }

    /** 좌표는 개인정보라 내려주지 않고 동네 이름만 (CLAUDE.md 원칙). */
    public record MeResponse(Long id, String email, String nickname, Role role,
                             String neighborhoodName, int searchRadiusM) {

        public static MeResponse from(User user) {
            return new MeResponse(user.getId(), user.getEmail(), user.getNickname(), user.getRole(),
                    user.getNeighborhoodName(), user.getSearchRadiusM());
        }
    }
}
