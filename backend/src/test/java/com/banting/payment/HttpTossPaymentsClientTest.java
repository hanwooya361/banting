package com.banting.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banting.payment.TossPaymentsClient.Approval;
import com.banting.payment.TossPaymentsClient.TossRejectedException;
import com.banting.payment.TossPaymentsClient.TossUnavailableException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 토스 요청 형식(인증·멱등 키·본문)과 응답·오류 해석. 실제 토스 대신 JDK 내장 HTTP 서버가 토스 형식으로 응답한다. */
class HttpTossPaymentsClientTest {

    private static final String SECRET = "test_gsk_dummy";

    private HttpServer server;
    private final Map<String, Captured> requests = new ConcurrentHashMap<>();
    private volatile int status = 200;
    private volatile String body = "{}";

    record Captured(String authorization, String idempotencyKey, String body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.put(exchange.getRequestURI().getPath(), new Captured(
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
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

    private HttpTossPaymentsClient client() {
        return new HttpTossPaymentsClient(SECRET, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void 승인_요청은_시크릿_키_Basic_인증과_주문별_멱등_키를_붙인다() {
        body = """
                {"paymentKey":"pk_1","orderId":"bt-1","status":"DONE","totalAmount":3333,
                 "approvedAt":"2026-11-18T15:00:00+09:00","method":"카드"}
                """;

        Approval approval = client().confirm("pk_1", "bt-1", 3333);

        assertThat(approval.status()).isEqualTo("DONE");
        assertThat(approval.totalAmount()).isEqualTo(3333);
        assertThat(approval.approvedAt().toInstant()).isEqualTo(java.time.Instant.parse("2026-11-18T06:00:00Z"));
        Captured request = requests.get("/v1/payments/confirm");
        assertThat(request.authorization()).isEqualTo(
                "Basic " + Base64.getEncoder().encodeToString((SECRET + ":").getBytes(StandardCharsets.UTF_8)));
        assertThat(request.idempotencyKey()).isEqualTo("confirm-bt-1");
        assertThat(request.body()).contains("\"paymentKey\":\"pk_1\"", "\"orderId\":\"bt-1\"", "\"amount\":3333");
    }

    @Test
    void 토스가_4xx로_거절하면_오류_코드를_담은_거절_예외() {
        status = 400;
        body = "{\"code\":\"REJECT_CARD_PAYMENT\",\"message\":\"한도초과 혹은 잔액부족으로 결제에 실패했습니다.\"}";

        assertThatThrownBy(() -> client().confirm("pk_1", "bt-1", 3333))
                .isInstanceOf(TossRejectedException.class)
                .satisfies(e -> assertThat(((TossRejectedException) e).getCode()).isEqualTo("REJECT_CARD_PAYMENT"));
    }

    @Test
    void 토스_5xx는_결과를_모르는_실패() {
        status = 500;
        body = "{\"code\":\"FAILED_INTERNAL_SYSTEM_PROCESSING\",\"message\":\"내부 시스템 처리 작업이 실패했습니다.\"}";

        assertThatThrownBy(() -> client().confirm("pk_1", "bt-1", 3333)).isInstanceOf(TossUnavailableException.class);
    }

    @Test
    void 연결이_안_되면_결과를_모르는_실패() {
        HttpTossPaymentsClient client = client();
        server.stop(0);

        assertThatThrownBy(() -> client.confirm("pk_1", "bt-1", 3333)).isInstanceOf(TossUnavailableException.class);
    }

    @Test
    void 취소는_결제_키_경로로_사유와_함께_보낸다() {
        body = "{\"paymentKey\":\"pk_1\",\"status\":\"CANCELED\"}";

        client().cancel("pk_1", "bt-1", "결제 반영 실패");

        Captured request = requests.get("/v1/payments/pk_1/cancel");
        assertThat(request.idempotencyKey()).isEqualTo("cancel-bt-1");
        assertThat(request.body()).contains("\"cancelReason\":\"결제 반영 실패\"");
    }

    @Test
    void 시크릿_키가_없으면_호출하지_않고_실패() {
        HttpTossPaymentsClient client = new HttpTossPaymentsClient("", "http://127.0.0.1:" + server.getAddress().getPort());

        assertThatThrownBy(() -> client.confirm("pk_1", "bt-1", 3333)).isInstanceOf(TossUnavailableException.class);
        assertThat(requests).isEmpty();
    }
}
