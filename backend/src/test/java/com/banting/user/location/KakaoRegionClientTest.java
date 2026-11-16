package com.banting.user.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import com.banting.common.geo.GeoPoint;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 카카오 응답 파싱·오류 처리 검증. 실제 카카오 대신 JDK 내장 HTTP 서버가 카카오 형식으로 응답한다.
 */
class KakaoRegionClientTest {

    private HttpServer server;
    private final Map<String, String> requests = new ConcurrentHashMap<>();
    private final Map<String, Response> responses = new ConcurrentHashMap<>();

    record Response(int status, String body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.put(path, exchange.getRequestURI().getRawQuery() + " | "
                    + exchange.getRequestHeaders().getFirst("Authorization"));
            Response response = responses.getOrDefault(path, new Response(200, "{\"documents\":[]}"));
            byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(response.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private KakaoRegionClient client(String key) {
        return new KakaoRegionClient(key, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void 좌표로_행정동_이름을_찾는다_카카오는_x가_경도() {
        responses.put("/v2/local/geo/coord2regioncode.json", new Response(200, """
                {"documents":[
                  {"region_type":"B","region_2depth_name":"중구","region_3depth_name":"태평로1가","x":126.978,"y":37.566},
                  {"region_type":"H","region_2depth_name":"중구","region_3depth_name":"명동","x":126.978,"y":37.566}
                ]}"""));

        assertThat(client("test-key").findRegionName(new GeoPoint(37.566, 126.978))).contains("중구 명동");
        assertThat(requests.get("/v2/local/geo/coord2regioncode.json"))
                .contains("x=126.978", "y=37.566", "KakaoAK test-key");
    }

    @Test
    void 주소_검색_결과가_없으면_장소_검색으로_찾는다() {
        responses.put("/v2/local/search/keyword.json", new Response(200, """
                {"documents":[{"place_name":"서울시청","address_name":"서울 중구 태평로1가 31",
                  "road_address_name":"서울 중구 세종대로 110","x":"126.97843","y":"37.56668"}]}"""));

        List<RegionClient.Place> places = client("k").search("서울시청");

        assertThat(places).containsExactly(
                new RegionClient.Place("서울시청", "서울 중구 세종대로 110", 37.56668, 126.97843));
    }

    @Test
    void 주소_검색에_결과가_있으면_그대로_쓴다() {
        responses.put("/v2/local/search/address.json", new Response(200, """
                {"documents":[{"address_name":"서울 강남구 테헤란로 152","x":"127.0365","y":"37.5000"}]}"""));

        assertThat(client("k").search("테헤란로 152"))
                .extracting(RegionClient.Place::name).containsExactly("서울 강남구 테헤란로 152");
        assertThat(requests).doesNotContainKey("/v2/local/search/keyword.json");
    }

    @Test
    void 카카오가_오류를_주거나_키가_없으면_503() {
        responses.put("/v2/local/geo/coord2regioncode.json", new Response(401, "{\"code\":-401}"));
        assertThatThrownBy(() -> client("wrong").findRegionName(new GeoPoint(37.5, 127.0)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LOCATION_SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client("").search("서울시청"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LOCATION_SERVICE_UNAVAILABLE));
    }
}
