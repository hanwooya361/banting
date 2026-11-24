package com.banting.user.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banting.TestcontainersConfiguration;
import com.banting.support.DatabaseCleaner;
import com.banting.common.geo.GeoPoint;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** 동네 설정 API. 카카오 대신 가짜 RegionClient를 써서 네트워크 없이 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, LocationIntegrationTest.FakeRegionConfig.class})
class LocationIntegrationTest {

    @TestConfiguration
    static class FakeRegionConfig {
        @Bean
        @Primary
        RegionClient fakeRegionClient() {
            return new RegionClient() {
                @Override
                public Optional<String> findRegionName(GeoPoint point) {
                    // 바다 위 좌표처럼 행정동이 없는 곳을 흉내: 위도 34.0
                    return point.latitude() == 34.0 ? Optional.empty() : Optional.of("중구 명동");
                }

                @Override
                public List<Place> search(String query) {
                    return List.of(new Place("서울시청", "서울 중구 세종대로 110", 37.56668, 126.97843));
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private String accessToken;

    @BeforeEach
    void signupAndLogin() throws Exception {
        DatabaseCleaner.clean(jdbc);
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"loc@example.com\",\"password\":\"password123\",\"nickname\":\"동네주민\",\"agreePrivacy\":true}"));
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"loc@example.com\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        accessToken = JsonPath.read(body, "$.accessToken");
    }

    @Test
    void 좌표를_보내면_서버가_정한_동네_이름과_반올림한_좌표를_저장하고_응답엔_좌표가_없다() throws Exception {
        mvc.perform(putLocation("{\"latitude\":37.566535,\"longitude\":126.977969,\"radiusM\":3000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.neighborhoodName").value("중구 명동"))
                .andExpect(jsonPath("$.searchRadiusM").value(3000))
                .andExpect(jsonPath("$.latitude").doesNotExist())
                .andExpect(jsonPath("$.longitude").doesNotExist());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT ST_Latitude(location) AS lat, ST_Longitude(location) AS lng, neighborhood_name "
                        + "FROM users WHERE email = 'loc@example.com'");
        assertThat(row.get("lat")).isEqualTo(37.567);     // 소수점 셋째 자리 (약 100m)
        assertThat(row.get("lng")).isEqualTo(126.978);
        assertThat(row.get("neighborhood_name")).isEqualTo("중구 명동");
    }

    @Test
    void 좌표_없이_보내면_반경만_바꾸고_동네가_없으면_먼저_고르라고_한다() throws Exception {
        mvc.perform(putLocation("{\"radiusM\":500}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOCATION_REQUIRED"));

        mvc.perform(putLocation("{\"latitude\":37.5665,\"longitude\":126.978,\"radiusM\":1000}"))
                .andExpect(status().isOk());
        mvc.perform(putLocation("{\"radiusM\":500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.neighborhoodName").value("중구 명동"))
                .andExpect(jsonPath("$.searchRadiusM").value(500));
    }

    @Test
    void 허용하지_않는_반경이나_한쪽만_온_좌표는_400() throws Exception {
        mvc.perform(putLocation("{\"latitude\":37.5665,\"longitude\":126.978,\"radiusM\":2000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("radiusAllowed"));
        mvc.perform(putLocation("{\"latitude\":37.5665,\"radiusM\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("coordinatePair"));
    }

    @Test
    void 국내가_아니거나_동네를_못_찾으면_저장하지_않는다() throws Exception {
        mvc.perform(putLocation("{\"latitude\":35.6762,\"longitude\":139.6503,\"radiusM\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOCATION_OUT_OF_SERVICE_AREA"));
        mvc.perform(putLocation("{\"latitude\":34.0,\"longitude\":127.0,\"radiusM\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REGION_NOT_FOUND"));

        Integer saved = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE location IS NOT NULL", Integer.class);
        assertThat(saved).isZero();
    }

    @Test
    void 동네_미리보기와_주소_검색() throws Exception {
        mvc.perform(get("/api/locations/region").param("latitude", "37.5665").param("longitude", "126.978")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.neighborhoodName").value("중구 명동"));

        mvc.perform(get("/api/locations/search").param("query", "서울시청")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("서울시청"))
                .andExpect(jsonPath("$[0].latitude").value(37.56668));

        mvc.perform(get("/api/locations/search").param("query", "a")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(get("/api/locations/region").param("latitude", "abc").param("longitude", "126.978")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 로그인하지_않으면_동네_API를_쓸_수_없다() throws Exception {
        mvc.perform(get("/api/locations/search").param("query", "서울시청"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/users/me/location").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"radiusM\":500}"))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.RequestBuilder putLocation(String json) {
        return put("/api/users/me/location")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }
}
