package com.banting.user;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);

    /**
     * 동네 좌표·이름·반경 저장. 좌표(POINT)는 엔티티에 매핑하지 않고 네이티브 SQL로만 다룬다
     * (JTS 등 라이브러리는 (경도, 위도), MySQL SRID 4326은 (위도, 경도)라 축이 뒤바뀌는 버그를 원천 차단).
     * wkt는 GeoPoint.toWkt()로 만든 값만 넘긴다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE users SET location = ST_PointFromText(:wkt, 4326), neighborhood_name = :name, "
            + "search_radius_m = :radius, updated_at = :now WHERE id = :id", nativeQuery = true)
    int updateLocation(@Param("id") Long id, @Param("wkt") String wkt, @Param("name") String name,
                       @Param("radius") int radius, @Param("now") LocalDateTime now);

    /** 좌표는 그대로 두고 반경만 변경. 동네가 아직 없으면 0건. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE users SET search_radius_m = :radius, updated_at = :now "
            + "WHERE id = :id AND location IS NOT NULL", nativeQuery = true)
    int updateRadius(@Param("id") Long id, @Param("radius") int radius, @Param("now") LocalDateTime now);
}
