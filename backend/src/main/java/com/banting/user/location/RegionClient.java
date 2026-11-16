package com.banting.user.location;

import com.banting.common.geo.GeoPoint;
import java.util.List;
import java.util.Optional;

/**
 * 좌표 ↔ 동네 이름·주소 변환 (외부 지도 API).
 * 인터페이스로 분리해 테스트에서는 네트워크 없이 가짜 구현을 쓴다.
 */
public interface RegionClient {

    /** 좌표가 속한 행정동 이름 (예: "강남구 역삼1동"). 찾지 못하면 빈 값. */
    Optional<String> findRegionName(GeoPoint point);

    /** 주소·장소 이름으로 검색 (최대 5건). */
    List<Place> search(String query);

    record Place(String name, String address, double latitude, double longitude) {
    }
}
