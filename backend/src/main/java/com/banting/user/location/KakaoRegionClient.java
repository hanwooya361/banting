package com.banting.user.location;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import com.banting.common.geo.GeoPoint;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 카카오 로컬 API (https://developers.kakao.com/docs/latest/ko/local/dev-guide).
 * - 좌표 → 행정구역: /v2/local/geo/coord2regioncode.json (region_type H = 행정동)
 * - 주소 검색: /v2/local/search/address.json, 결과가 없으면 장소 검색 /v2/local/search/keyword.json
 * 카카오 API는 x = 경도, y = 위도. 응답 좌표는 문자열.
 * 외부 호출이 느리거나 실패해도 우리 서버 스레드가 묶이지 않게 타임아웃을 짧게(연결 2초, 응답 3초) 둔다.
 */
@Slf4j
@Component
public class KakaoRegionClient implements RegionClient {

    private static final int MAX_RESULTS = 5;

    private final RestClient restClient;
    private final boolean configured;

    public KakaoRegionClient(@Value("${banting.kakao.rest-api-key:}") String restApiKey,
                             @Value("${banting.kakao.base-url:https://dapi.kakao.com}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "KakaoAK " + restApiKey)
                .build();
        this.configured = !restApiKey.isBlank();
        if (!configured) {
            log.warn("banting.kakao.rest-api-key가 비어 있어 동네 설정·주소 검색이 동작하지 않습니다");
        }
    }

    @Override
    public Optional<String> findRegionName(GeoPoint point) {
        RegionResponse response = call(() -> restClient.get()
                .uri(uri -> uri.path("/v2/local/geo/coord2regioncode.json")
                        .queryParam("x", point.longitude())
                        .queryParam("y", point.latitude())
                        .build())
                .retrieve()
                .body(RegionResponse.class));
        return response.documents().stream()
                .filter(doc -> "H".equals(doc.regionType()))
                .findFirst()
                .map(doc -> (doc.region2() + " " + doc.region3()).trim());
    }

    @Override
    public List<Place> search(String query) {
        List<Place> byAddress = call(() -> restClient.get()
                .uri(uri -> uri.path("/v2/local/search/address.json")
                        .queryParam("query", query).queryParam("size", MAX_RESULTS).build())
                .retrieve()
                .body(AddressResponse.class))
                .documents().stream()
                .map(doc -> new Place(doc.addressName(), doc.addressName(), doc.lat(), doc.lng()))
                .toList();
        if (!byAddress.isEmpty()) {
            return byAddress;
        }
        return call(() -> restClient.get()
                .uri(uri -> uri.path("/v2/local/search/keyword.json")
                        .queryParam("query", query).queryParam("size", MAX_RESULTS).build())
                .retrieve()
                .body(KeywordResponse.class))
                .documents().stream()
                .map(doc -> new Place(doc.placeName(),
                        doc.roadAddressName() == null || doc.roadAddressName().isBlank()
                                ? doc.addressName() : doc.roadAddressName(),
                        doc.lat(), doc.lng()))
                .toList();
    }

    private <T> T call(java.util.function.Supplier<T> request) {
        if (!configured) {
            throw new BusinessException(ErrorCode.LOCATION_SERVICE_UNAVAILABLE);
        }
        try {
            T body = request.get();
            if (body == null) {
                throw new BusinessException(ErrorCode.LOCATION_SERVICE_UNAVAILABLE);
            }
            return body;
        } catch (RestClientException e) {
            log.warn("카카오 로컬 API 호출 실패: {}", e.getMessage());
            throw new BusinessException(ErrorCode.LOCATION_SERVICE_UNAVAILABLE);
        }
    }

    // ───── 카카오 응답 (필요한 필드만) ─────

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegionResponse(List<RegionDoc> documents) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegionDoc(@JsonProperty("region_type") String regionType,
                     @JsonProperty("region_2depth_name") String region2,
                     @JsonProperty("region_3depth_name") String region3) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AddressResponse(List<AddressDoc> documents) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AddressDoc(@JsonProperty("address_name") String addressName, String x, String y) {
        double lat() { return Double.parseDouble(y); }
        double lng() { return Double.parseDouble(x); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record KeywordResponse(List<KeywordDoc> documents) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record KeywordDoc(@JsonProperty("place_name") String placeName,
                      @JsonProperty("address_name") String addressName,
                      @JsonProperty("road_address_name") String roadAddressName,
                      String x, String y) {
        double lat() { return Double.parseDouble(y); }
        double lng() { return Double.parseDouble(x); }
    }
}
