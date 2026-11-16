package com.banting.groupbuy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banting.TestcontainersConfiguration;
import com.banting.common.storage.ImageStorage;
import com.banting.support.DatabaseCleaner;
import com.banting.support.FakeImageStorage;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 공구 개설·조회·수정·삭제와 사진 업로드 (T08). S3는 가짜 저장소. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupBuyIntegrationTest.FakeStorageConfig.class})
class GroupBuyIntegrationTest {

    @TestConfiguration
    static class FakeStorageConfig {
        @Bean
        @Primary
        FakeImageStorage fakeImageStorage() {
            return new FakeImageStorage();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeImageStorage storage;

    private Long hostId;
    private String hostToken;
    private String otherToken;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseCleaner.clean(jdbc);
        storage.reset();
        hostToken = signupAndLogin("host@example.com", "개설자");
        otherToken = signupAndLogin("other@example.com", "이웃");
        hostId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'host@example.com'", Long.class);
        // 동네 설정 (카카오 호출 없이 DB에 직접): 서울시청 근처
        jdbc.update("UPDATE users SET location = ST_PointFromText('POINT(37.567 126.978)', 4326), "
                + "neighborhood_name = '중구 명동' WHERE id = ?", hostId);
    }

    // ───── 개설 ─────

    @Test
    void 균등_공구를_개설하면_DRAFT와_개설자_결제대기_참여가_생기고_사진이_공개_위치로_복사된다() throws Exception {
        String photo = uploadedPhoto(hostId);

        String body = create(hostToken, equalJson(3, 10_000, "https://www.coupang.com/vp/products/1",
                "[\"" + photo + "\"]"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.capacityUnits").value(3))
                .andExpect(jsonPath("$.reservedUnits").value(1))
                .andExpect(jsonPath("$.unitPrice").value(3_333))
                .andExpect(jsonPath("$.myAmount").value(3_334))          // 남는 1원은 개설자
                .andExpect(jsonPath("$.mine").value(true))
                .andExpect(jsonPath("$.neighborhoodName").value("중구 명동"))
                .andExpect(jsonPath("$.pickupOptions.length()").value(2))
                .andExpect(jsonPath("$.images.length()").value(1))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        String publishedKey = JsonPath.read(body, "$.images[0].key");
        assertThat(publishedKey).startsWith(ImageStorage.PUBLISHED_PREFIX);
        assertThat(storage.has(publishedKey)).isTrue();
        assertThat(JsonPath.<String>read(body, "$.images[0].url")).endsWith("/" + publishedKey);

        Map<String, Object> host = jdbc.queryForMap(
                "SELECT is_host, units, amount, status FROM participations WHERE user_id = ?", hostId);
        assertThat(host).containsEntry("units", 1).containsEntry("amount", 3_334L)
                .containsEntry("status", "PENDING_PAYMENT");
        assertThat((Boolean) host.get("is_host")).isTrue();
        // 공구 좌표는 개설자 동네 좌표 복사
        Double lat = jdbc.queryForObject("SELECT ST_Latitude(location) FROM group_buys", Double.class);
        assertThat(lat).isEqualTo(37.567);
    }

    @Test
    void 상품_링크가_없거나_http_https가_아니면_개설할_수_없다() throws Exception {
        create(hostToken, equalJson(3, 10_000, "", "[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        create(hostToken, equalJson(3, 10_000, "javascript:alert(document.cookie)", "[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PRODUCT_URL"));
        assertThat(count("group_buys")).isZero();
    }

    @Test
    void 동네를_정하지_않았으면_개설할_수_없다() throws Exception {
        create(otherToken, equalJson(3, 10_000, "https://e.com/p", "[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOCATION_REQUIRED"));
    }

    @Test
    void 남이_올린_사진이나_올리지_않은_사진은_쓸_수_없다() throws Exception {
        Long otherId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'other@example.com'", Long.class);
        String othersPhoto = uploadedPhoto(otherId);
        create(hostToken, equalJson(3, 10_000, "https://e.com/p", "[\"" + othersPhoto + "\"]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));

        String notUploaded = ImageStorage.TMP_PREFIX + hostId + "/" + UUID.randomUUID() + ".jpg";
        create(hostToken, equalJson(3, 10_000, "https://e.com/p", "[\"" + notUploaded + "\"]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));

        create(hostToken, equalJson(3, 10_000, "https://e.com/p", "[\"images/group-buys/../../etc/passwd\"]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));
        assertThat(count("group_buys")).isZero();
    }

    @Test
    void 수령_일정이_마감_전이면_거부() throws Exception {
        LocalDateTime deadline = inDays(3);
        String json = """
                {"productUrl":"https://e.com/p","title":"휴지 30롤","description":"","category":"LIVING",
                 "priceType":"EQUAL","totalPrice":30000,"totalQuantity":30,"unitLabel":"롤","people":3,
                 "deadlineAt":"%s","pickupOptions":[{"pickupAt":"%s","placeName":"명동역 2번 출구"}]}
                """.formatted(deadline, deadline.minusHours(1));
        create(hostToken, json)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PICKUP_OPTION"));
    }

    @Test
    void 수량별_공구는_개설자_몫_수량만큼_자리를_잡는다() throws Exception {
        String json = """
                {"productUrl":"https://e.com/egg","title":"계란 30구","category":"FOOD","priceType":"BY_QUANTITY",
                 "totalPrice":25900,"totalQuantity":30,"unitLabel":"구","minUnitsPerPerson":5,"hostUnits":10,
                 "deadlineAt":"%s","pickupOptions":[{"pickupAt":"%s","placeName":"아파트 정문"}]}
                """.formatted(inDays(2), inDays(3));
        create(hostToken, json)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.capacityUnits").value(30))
                .andExpect(jsonPath("$.reservedUnits").value(10))
                .andExpect(jsonPath("$.unitPrice").value(863))
                .andExpect(jsonPath("$.myAmount").value(8_640));
    }

    @Test
    void 로그인하지_않으면_개설할_수_없다() throws Exception {
        mvc.perform(post("/api/group-buys").contentType(MediaType.APPLICATION_JSON)
                        .content(equalJson(3, 10_000, "https://e.com/p", "[]")))
                .andExpect(status().isUnauthorized());
    }

    // ───── 조회 ─────

    @Test
    void 결제_전_공구는_개설자만_보고_남에게는_없는_공구처럼_보인다() throws Exception {
        long id = createdId(equalJson(3, 10_000, "https://e.com/p", "[]"));

        mvc.perform(get("/api/group-buys/" + id).header(HttpHeaders.AUTHORIZATION, bearer(hostToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mine").value(true));
        mvc.perform(get("/api/group-buys/" + id).header(HttpHeaders.AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GROUP_BUY_NOT_FOUND"));
        mvc.perform(get("/api/group-buys/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    void 모집중인_공구는_로그인_없이_볼_수_있고_좌표는_내려주지_않는다() throws Exception {
        long id = createdId(equalJson(3, 10_000, "https://e.com/p", "[]"));
        jdbc.update("UPDATE group_buys SET status = 'OPEN' WHERE id = ?", id);   // 결제(T11) 대신

        mvc.perform(get("/api/group-buys/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mine").value(false))
                .andExpect(jsonPath("$.myAmount").doesNotExist())
                .andExpect(jsonPath("$.host.nickname").value("개설자"))
                .andExpect(jsonPath("$.locationWkt").doesNotExist())
                .andExpect(jsonPath("$.latitude").doesNotExist());
    }

    // ───── 수정 ─────

    @Test
    void 개설자는_설명성_항목과_마감_연장과_사진을_바꿀_수_있다() throws Exception {
        String firstPhoto = uploadedPhoto(hostId);
        String body = create(hostToken, equalJson(3, 10_000, "https://e.com/p", "[\"" + firstPhoto + "\"]"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();
        String oldPublished = JsonPath.read(body, "$.images[0].key");
        String newPhoto = uploadedPhoto(hostId);

        String patchJson = """
                {"title":"휴지 30롤 (3겹)","category":"ETC","deadlineAt":"%s","imageKeys":["%s"]}
                """.formatted(inDays(3).plusHours(12), newPhoto);   // 수령 일정(4일 뒤)보다는 앞이어야 함
        mvc.perform(patchRequest(id, hostToken, patchJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("휴지 30롤 (3겹)"))
                .andExpect(jsonPath("$.category").value("ETC"))
                .andExpect(jsonPath("$.totalPrice").value(10_000))   // 금액은 그대로
                .andExpect(jsonPath("$.images.length()").value(1));

        // 빠진 기존 사진은 커밋 후 S3에서 삭제
        assertThat(storage.deleted()).contains(oldPublished);
    }

    @Test
    void 마감을_앞당길_수는_없다() throws Exception {
        long id = createdId(equalJson(3, 10_000, "https://e.com/p", "[]"));
        mvc.perform(patchRequest(id, hostToken, "{\"deadlineAt\":\"" + inDays(1) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DEADLINE_ONLY_EXTEND"));
    }

    @Test
    void 개설자가_아니면_수정_삭제할_수_없다() throws Exception {
        long id = createdId(equalJson(3, 10_000, "https://e.com/p", "[]"));
        mvc.perform(patchRequest(id, otherToken, "{\"title\":\"남의 공구\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_GROUP_BUY_HOST"));
        mvc.perform(delete("/api/group-buys/" + id).header(HttpHeaders.AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 개설자_외_참여자가_있으면_수정도_삭제도_막힌다() throws Exception {
        long id = createdId(equalJson(3, 10_000, "https://e.com/p", "[]"));
        Long otherId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'other@example.com'", Long.class);
        // 참여(T11) 대신 결제 대기 참여를 직접 넣음
        jdbc.update("INSERT INTO participations (group_buy_id, user_id, is_host, units, amount, status, expires_at, "
                + "created_at, updated_at) VALUES (?, ?, FALSE, 1, 3333, 'PENDING_PAYMENT', NOW(6) + INTERVAL 10 MINUTE, "
                + "NOW(6), NOW(6))", id, otherId);

        mvc.perform(patchRequest(id, hostToken, "{\"title\":\"바꿀래요\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_BUY_HAS_PARTICIPANTS"));
        mvc.perform(delete("/api/group-buys/" + id).header(HttpHeaders.AUTHORIZATION, bearer(hostToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_BUY_HAS_PARTICIPANTS"));

        // 만료·결제 실패로 취소된 참여는 자리를 차지하지 않으므로 다시 수정 가능
        jdbc.update("UPDATE participations SET status = 'CANCELED' WHERE user_id = ?", otherId);
        mvc.perform(patchRequest(id, hostToken, "{\"title\":\"바꿀래요\"}"))
                .andExpect(status().isOk());
    }

    // ───── 삭제 ─────

    @Test
    void 결제_전_공구를_삭제하면_관련_데이터와_사진이_모두_지워진다() throws Exception {
        String photo = uploadedPhoto(hostId);
        String body = create(hostToken, equalJson(3, 10_000, "https://e.com/p", "[\"" + photo + "\"]"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();
        String published = JsonPath.read(body, "$.images[0].key");

        mvc.perform(delete("/api/group-buys/" + id).header(HttpHeaders.AUTHORIZATION, bearer(hostToken)))
                .andExpect(status().isNoContent());

        assertThat(count("group_buys")).isZero();
        assertThat(count("participations")).isZero();
        assertThat(count("pickup_options")).isZero();
        assertThat(count("group_buy_images")).isZero();
        assertThat(storage.deleted()).contains(published);
    }

    @Test
    void 결제가_끝난_공구는_아직_삭제할_수_없다() throws Exception {
        long id = createdId(equalJson(3, 10_000, "https://e.com/p", "[]"));
        jdbc.update("UPDATE group_buys SET status = 'OPEN' WHERE id = ?", id);
        mvc.perform(delete("/api/group-buys/" + id).header(HttpHeaders.AUTHORIZATION, bearer(hostToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GROUP_BUY_NOT_DELETABLE"));
    }

    // ───── 사진 업로드 URL ─────

    @Test
    void 사진_업로드_URL은_내_tmp_경로로_발급하고_형식과_크기를_제한한다() throws Exception {
        mvc.perform(post("/api/uploads/presigned").header(HttpHeaders.AUTHORIZATION, bearer(hostToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/jpeg\",\"contentLength\":120000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(org.hamcrest.Matchers.startsWith("images/tmp/" + hostId + "/")))
                .andExpect(jsonPath("$.key").value(org.hamcrest.Matchers.endsWith(".jpg")));

        mvc.perform(post("/api/uploads/presigned").header(HttpHeaders.AUTHORIZATION, bearer(hostToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/gif\",\"contentLength\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE"));
        mvc.perform(post("/api/uploads/presigned").header(HttpHeaders.AUTHORIZATION, bearer(hostToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/png\",\"contentLength\":5242881}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/uploads/presigned").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"image/png\",\"contentLength\":100}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 사진_복사에_실패하면_공구를_만들지_않는다() throws Exception {
        String photo = uploadedPhoto(hostId);
        storage.failCopy(true);
        create(hostToken, equalJson(3, 10_000, "https://e.com/p", "[\"" + photo + "\"]"))
                .andExpect(status().isInternalServerError());
        assertThat(count("group_buys")).isZero();
    }

    // ───── 헬퍼 ─────

    private String signupAndLogin(String email, String nickname) throws Exception {
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"password123\",\"nickname\":\"%s\"}".formatted(email, nickname)));
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"password123\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.accessToken");
    }

    private String uploadedPhoto(Long ownerId) {
        String key = ImageStorage.TMP_PREFIX + ownerId + "/" + UUID.randomUUID() + ".jpg";
        storage.uploaded(key);
        return key;
    }

    private static LocalDateTime inDays(int days) {
        return LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(days).truncatedTo(ChronoUnit.MINUTES);
    }

    private static String equalJson(int people, long totalPrice, String url, String imageKeysJson) {
        return """
                {"productUrl":"%s","title":"휴지 30롤","description":"3명이서 10롤씩","category":"LIVING",
                 "priceType":"EQUAL","totalPrice":%d,"totalQuantity":30,"unitLabel":"롤","people":%d,
                 "deadlineAt":"%s","imageKeys":%s,
                 "pickupOptions":[{"pickupAt":"%s","placeName":"명동역 2번 출구"},
                                  {"pickupAt":"%s","placeName":"을지로입구역 앞"}]}
                """.formatted(url, totalPrice, people, inDays(3), imageKeysJson, inDays(4), inDays(5));
    }

    private ResultActions create(String token, String json) throws Exception {
        return mvc.perform(post("/api/group-buys").header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long createdId(String json) throws Exception {
        String body = create(hostToken, json).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private MockHttpServletRequestBuilder patchRequest(long id, String token, String json) {
        return patch("/api/group-buys/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
