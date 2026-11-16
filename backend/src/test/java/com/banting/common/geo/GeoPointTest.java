package com.banting.common.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banting.common.BusinessException;
import org.junit.jupiter.api.Test;

class GeoPointTest {

    @Test
    void WKT는_위도_경도_순서다() {
        // MySQL SRID 4326 축 순서. 경도부터 쓰면 좌표가 뒤바뀐다
        assertThat(new GeoPoint(37.5665, 126.978).toWkt()).isEqualTo("POINT(37.5665 126.978)");
    }

    @Test
    void 저장용_좌표는_소수점_셋째_자리로_반올림한다() {
        GeoPoint rounded = new GeoPoint(37.566535, 126.977969).rounded();
        assertThat(rounded.latitude()).isEqualTo(37.567);
        assertThat(rounded.longitude()).isEqualTo(126.978);
        assertThat(rounded.toWkt()).isEqualTo("POINT(37.567 126.978)");
    }

    @Test
    void 지수_표기_없이_WKT를_만든다() {
        assertThat(new GeoPoint(33.0, 124.5).toWkt()).isEqualTo("POINT(33.0 124.5)");
    }

    @Test
    void 국내_범위_밖이나_위도_경도가_뒤바뀐_값은_거부한다() {
        assertThatThrownBy(() -> new GeoPoint(35.6762, 139.6503))   // 도쿄
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new GeoPoint(126.978, 37.5665))    // 위도·경도 뒤바뀜
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new GeoPoint(Double.NaN, 126.978))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 제주와_독도도_서비스_지역이다() {
        new GeoPoint(33.2, 126.27);      // 제주 마라도 부근
        new GeoPoint(37.24, 131.87);     // 독도
    }
}
