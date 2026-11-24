package com.banting.user.location;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import com.banting.common.geo.GeoPoint;
import com.banting.user.UserController.MeResponse;
import com.banting.user.UserRepository;
import com.banting.user.oauth.SocialAccount;
import com.banting.user.oauth.SocialAccountRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 동네 설정 API. 응답에는 좌표를 넣지 않고 동네 이름만 (CLAUDE.md 개인정보 원칙).
 * 검색 API는 카카오 무료 호출량을 아끼고 남용을 막기 위해 로그인 회원만.
 */
@RestController
@RequiredArgsConstructor
public class LocationController {

    private static final Set<Integer> ALLOWED_RADIUS = Set.of(500, 1000, 3000);

    private final LocationService locationService;
    private final UserRepository userRepository;
    private final SocialAccountRepository socialAccountRepository;

    @PutMapping("/api/users/me/location")
    public MeResponse updateMyLocation(@AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody UpdateLocationRequest request) {
        Long userId = Long.valueOf(jwt.getSubject());
        locationService.updateMyLocation(userId, request.toPoint(), request.radiusM());
        return userRepository.findById(userId)
                .map(user -> MeResponse.from(user, socialAccountRepository.findByUserId(userId).stream()
                        .map(SocialAccount::getProvider).sorted().toList()))
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    @GetMapping("/api/locations/region")
    public RegionResponse region(@RequestParam double latitude, @RequestParam double longitude) {
        return new RegionResponse(locationService.previewRegionName(new GeoPoint(latitude, longitude)));
    }

    @GetMapping("/api/locations/search")
    public List<RegionClient.Place> search(
            @RequestParam @NotBlank(message = "검색어를 입력해 주세요")
            @Size(min = 2, max = 50, message = "검색어는 2~50자로 입력해 주세요") String query) {
        return locationService.search(query);
    }

    public record RegionResponse(String neighborhoodName) {
    }

    /** 위도·경도를 둘 다 보내면 동네 변경, 둘 다 생략하면 반경만 변경. */
    public record UpdateLocationRequest(Double latitude, Double longitude,
                                        @NotNull(message = "반경을 골라 주세요") Integer radiusM) {

        @AssertTrue(message = "반경은 500m, 1km, 3km 중에서 골라 주세요")
        public boolean isRadiusAllowed() {
            return radiusM == null || ALLOWED_RADIUS.contains(radiusM);
        }

        @AssertTrue(message = "위도와 경도는 함께 보내 주세요")
        public boolean isCoordinatePair() {
            return (latitude == null) == (longitude == null);
        }

        GeoPoint toPoint() {
            return latitude == null ? null : new GeoPoint(latitude, longitude);
        }
    }
}
