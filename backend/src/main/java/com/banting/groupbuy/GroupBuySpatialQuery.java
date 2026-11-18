package com.banting.groupbuy;

import com.banting.common.geo.GeoPoint;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 공구 목록 = 반경 검색 + 카테고리·키워드·정렬 (tech-design 4장 "반경 검색", T07·T09).
 *
 * <p>ST_Distance_Sphere만 쓰면 모든 행의 거리를 계산해야 해서 공간 인덱스를 못 탄다.
 * 그래서 2단계로 거른다.
 * <ol>
 *   <li>반경을 감싸는 사각형(MBR)에 들어오는 행만 공간 인덱스(sp_group_buys_location)로 빠르게 고른다</li>
 *   <li>그중 실제 구면 거리가 반경 이내인 것만 남긴다 (사각형 모서리 부분 제거)</li>
 * </ol>
 * 카테고리·키워드는 반경으로 이미 좁혀진 소수의 행에 거는 조건이라 별도 인덱스(FULLTEXT 등) 없이 LIKE로 충분하다.
 * 카드에 필요한 값(대표 사진 포함)을 쿼리 한 번으로 가져와 N+1을 피한다.
 * 테스트(GroupBuySpatialQueryTest)에서 EXPLAIN으로 인덱스 사용을 확인한다.
 *
 * <p>거리는 응답에 넣지 않는다. 거리 값이 있으면 여러 지점에서 검색해 개설자 위치를 좁혀 낼 수 있기 때문.
 */
@Repository
@RequiredArgsConstructor
public class GroupBuySpatialQuery {

    private static final double METERS_PER_DEGREE_LAT = 111_320.0;
    private static final char LIKE_ESCAPE = '!';

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * @param category null이면 전체
     * @param keyword  null이면 검색 안 함 (앞뒤 공백은 호출 쪽에서 정리)
     * @param now      마감이 지난 공구 제외 기준 (마감 스케줄러 T14 전에도 목록에 안 보이게)
     */
    public record Condition(GeoPoint center, int radiusM, Category category, String keyword,
                            GroupBuySort sort, LocalDateTime now, int limit, int offset) {
    }

    /** 목록 카드 한 줄. thumbnailKey는 첫 번째 사진의 S3 키 (없으면 null). */
    public record Row(long id, String title, Category category, PriceType priceType, long totalPrice,
                      int totalQuantity, String unitLabel, int capacityUnits, int minUnitsPerPerson,
                      int reservedUnits, int paidUnits, String neighborhoodName, LocalDateTime deadlineAt,
                      LocalDateTime createdAt, String thumbnailKey) {
    }

    public List<Row> search(Condition condition) {
        return jdbc.query(sql(condition), params(condition), (rs, i) -> new Row(
                rs.getLong("id"), rs.getString("title"), Category.valueOf(rs.getString("category")),
                PriceType.valueOf(rs.getString("price_type")), rs.getLong("total_price"),
                rs.getInt("total_quantity"), rs.getString("unit_label"), rs.getInt("capacity_units"),
                rs.getInt("min_units_per_person"), rs.getInt("reserved_units"), rs.getInt("paid_units"),
                rs.getString("neighborhood_name"), rs.getObject("deadline_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class), rs.getString("thumbnail_key")));
    }

    /** 조건에 따라 WHERE·ORDER BY만 바뀐다. 사용자 입력은 모두 바인딩 파라미터로만 들어간다. */
    static String sql(Condition condition) {
        StringBuilder sql = new StringBuilder("""
                SELECT g.id, g.title, g.category, g.price_type, g.total_price, g.total_quantity, g.unit_label,
                       g.capacity_units, g.min_units_per_person, g.reserved_units, g.paid_units,
                       g.neighborhood_name, g.deadline_at, g.created_at,
                       (SELECT i.url FROM group_buy_images i WHERE i.group_buy_id = g.id
                        ORDER BY i.sort_order LIMIT 1) AS thumbnail_key
                FROM group_buys g
                WHERE g.status = 'OPEN'
                  AND g.deadline_at > :now
                  AND MBRContains(ST_GeomFromText(:box, 4326), g.location)
                  AND ST_Distance_Sphere(g.location, ST_PointFromText(:center, 4326)) <= :radius
                """);
        if (condition.category() != null) {
            sql.append("  AND g.category = :category\n");
        }
        if (condition.keyword() != null) {
            sql.append("  AND g.title LIKE :keyword ESCAPE '").append(LIKE_ESCAPE).append("'\n");
        }
        sql.append(condition.sort() == GroupBuySort.LATEST
                ? "ORDER BY g.created_at DESC, g.id DESC\n"
                : "ORDER BY g.deadline_at ASC, g.id ASC\n");
        sql.append("LIMIT :limit OFFSET :offset");
        return sql.toString();
    }

    static MapSqlParameterSource params(Condition condition) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("now", condition.now())
                .addValue("center", condition.center().toWkt())
                .addValue("box", boundingBoxWkt(condition.center(), condition.radiusM()))
                .addValue("radius", condition.radiusM())
                .addValue("limit", condition.limit())
                .addValue("offset", condition.offset());
        if (condition.category() != null) {
            params.addValue("category", condition.category().name());
        }
        if (condition.keyword() != null) {
            params.addValue("keyword", "%" + escapeLike(condition.keyword()) + "%");
        }
        return params;
    }

    /** "100%" 검색이 "100으로 시작하는 모든 것"이 되지 않게 LIKE 특수문자(%, _)를 글자 그대로 취급. */
    static String escapeLike(String keyword) {
        StringBuilder escaped = new StringBuilder();
        for (char c : keyword.toCharArray()) {
            if (c == '%' || c == '_' || c == LIKE_ESCAPE) {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(c);
        }
        return escaped.toString();
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
