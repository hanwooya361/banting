package com.banting.user.location;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import com.banting.common.geo.GeoPoint;
import com.banting.user.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 동네 설정.
 * 외부 API(카카오) 호출은 트랜잭션 밖에서 먼저 끝내고, DB 저장은 짧은 UPDATE 한 번으로 한다.
 * 외부 응답이 느려도 DB 커넥션을 붙잡고 있지 않게 하기 위함 (CLAUDE.md: 외부 호출과 DB 트랜잭션을 섞지 않는다).
 */
@Service
@RequiredArgsConstructor
public class LocationService {

    private final RegionClient regionClient;
    private final UserRepository userRepository;
    private final Clock clock;

    /** 저장 전 미리보기: 이 좌표의 동네 이름. */
    public String previewRegionName(GeoPoint point) {
        return regionClient.findRegionName(point.rounded())
                .orElseThrow(() -> new BusinessException(ErrorCode.REGION_NOT_FOUND));
    }

    public List<RegionClient.Place> search(String query) {
        return regionClient.search(query.trim());
    }

    /**
     * 좌표가 있으면 좌표·동네 이름·반경을 저장하고, 없으면 반경만 바꾼다.
     * 동네 이름은 클라이언트가 보낸 값이 아니라 서버가 좌표로 직접 조회한 값만 저장한다 (가짜 동네 이름 방지).
     */
    public void updateMyLocation(Long userId, GeoPoint point, int radiusM) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (point == null) {
            if (userRepository.updateRadius(userId, radiusM, now) == 0) {
                throw new BusinessException(ErrorCode.LOCATION_REQUIRED);
            }
            return;
        }
        GeoPoint stored = point.rounded();
        String regionName = regionClient.findRegionName(stored)
                .orElseThrow(() -> new BusinessException(ErrorCode.REGION_NOT_FOUND));
        if (userRepository.updateLocation(userId, stored.toWkt(), regionName, radiusM, now) == 0) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
    }
}
