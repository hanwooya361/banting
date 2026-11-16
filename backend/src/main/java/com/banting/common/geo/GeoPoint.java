package com.banting.common.geo;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 위도·경도 좌표 값 객체.
 *
 * <p>MySQL의 SRID 4326은 축 순서가 (위도, 경도)다. 흔히 쓰는 (x=경도, y=위도)와 반대라서
 * WKT 문자열은 반드시 {@link #toWkt()}로만 만든다 (축 순서 실수를 한 곳에서 막음).
 *
 * <p>개인정보 보호를 위해 저장 전에 {@link #rounded()}로 소수점 셋째 자리(약 100m)까지만 남긴다.
 * 정밀 좌표가 있으면 반경 검색을 반복해 개설자 집 위치를 좁혀 낼 수 있기 때문.
 */
public record GeoPoint(double latitude, double longitude) {

    // 서비스 지역: 대한민국 (제주 마라도 ~ 독도를 포함하는 사각형)
    private static final double MIN_LAT = 33.0;
    private static final double MAX_LAT = 38.7;
    private static final double MIN_LNG = 124.5;
    private static final double MAX_LNG = 132.0;

    private static final int STORED_SCALE = 3;

    public GeoPoint {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)
                || latitude < MIN_LAT || latitude > MAX_LAT || longitude < MIN_LNG || longitude > MAX_LNG) {
            throw new BusinessException(ErrorCode.LOCATION_OUT_OF_SERVICE_AREA);
        }
    }

    /** 저장용: 소수점 셋째 자리로 반올림 (위도 0.001° ≈ 111m, 경도 0.001° ≈ 89m @ 37°N). */
    public GeoPoint rounded() {
        return new GeoPoint(round(latitude), round(longitude));
    }

    /** MySQL SRID 4326용 WKT. 순서는 (위도 경도). */
    public String toWkt() {
        return "POINT(" + plain(latitude) + " " + plain(longitude) + ")";
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(STORED_SCALE, RoundingMode.HALF_UP).doubleValue();
    }

    private static String plain(double value) {
        return BigDecimal.valueOf(value).toPlainString();
    }
}
