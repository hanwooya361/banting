package com.banting.groupbuy;

import com.banting.common.geo.GeoPoint;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 반경 검색 (tech-design 4장 "반경 검색").
 *
 * <p>ST_Distance_Sphere만 쓰면 모든 행의 거리를 계산해야 해서 공간 인덱스를 못 탄다.
 * 그래서 2단계로 거른다.
 * <ol>
 *   <li>반경을 감싸는 사각형(MBR)에 들어오는 행만 공간 인덱스(sp_group_buys_location)로 빠르게 고른다</li>
 *   <li>그중 실제 구면 거리가 반경 이내인 것만 남긴다 (사각형 모서리 부분 제거)</li>
 * </ol>
 * 테스트(GroupBuySpatialQueryTest)에서 EXPLAIN으로 인덱스 사용을 확인한다.
 */
@Repository
@RequiredArgsConstructor
public class GroupBuySpatialQuery {

    private static final double METERS_PER_DEGREE_LAT = 111_320.0;

    private final NamedParameterJdbcTemplate jdbc;

    static final String NEARBY_OPEN_SQL = """
            SELECT id, ROUND(ST_Distance_Sphere(location, ST_PointFromText(:center, 4326))) AS distance_m
            FROM group_buys
            WHERE status = 'OPEN'
              AND MBRContains(ST_GeomFromText(:box, 4326), location)
              AND ST_Distance_Sphere(location, ST_PointFromText(:center, 4326)) <= :radius
            ORDER BY deadline_at ASC, id ASC
            LIMIT :limit OFFSET :offset
            """;

    public record Nearby(long id, long distanceM) {
    }

    /** 모집 중인 공구를 반경 안에서 마감 임박순으로. */
    public List<Nearby> findOpenWithin(GeoPoint center, int radiusM, int limit, int offset) {
        return jdbc.query(NEARBY_OPEN_SQL, params(center, radiusM, limit, offset),
                (rs, i) -> new Nearby(rs.getLong("id"), rs.getLong("distance_m")));
    }

    MapSqlParameterSource params(GeoPoint center, int radiusM, int limit, int offset) {
        return new MapSqlParameterSource()
                .addValue("center", center.toWkt())
                .addValue("box", boundingBoxWkt(center, radiusM))
                .addValue("radius", radiusM)
                .addValue("limit", limit)
                .addValue("offset", offset);
    }

    /**
     * 반경을 감싸는 사각형. 위도 1°는 어디서나 약 111km, 경도 1°는 위도가 높을수록 짧아진다(cos 위도).
     * 약간 넉넉하게(1.1배) 잡아 경계 근처를 놓치지 않게 하고, 정확한 판정은 2단계 거리 계산이 한다.
     * SRID 4326이라 꼭짓점도 (위도 경도) 순서.
     */
    static String boundingBoxWkt(GeoPoint center, int radiusM) {
        double margin = radiusM * 1.1;
        double dLat = margin / METERS_PER_DEGREE_LAT;
        double dLng = margin / (METERS_PER_DEGREE_LAT * Math.cos(Math.toRadians(center.latitude())));
        String minLat = plain(center.latitude() - dLat);
        String maxLat = plain(center.latitude() + dLat);
        String minLng = plain(center.longitude() - dLng);
        String maxLng = plain(center.longitude() + dLng);
        return "POLYGON((" + minLat + " " + minLng + ", " + maxLat + " " + minLng + ", "
                + maxLat + " " + maxLng + ", " + minLat + " " + maxLng + ", " + minLat + " " + minLng + "))";
    }

    private static String plain(double value) {
        return BigDecimal.valueOf(value).toPlainString();
    }
}
