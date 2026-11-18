package com.banting.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 토스페이먼츠 코어 API (https://docs.tosspayments.com/reference).
 * - 승인: POST /v1/payments/confirm {paymentKey, orderId, amount}
 * - 취소: POST /v1/payments/{paymentKey}/cancel {cancelReason}
 * 인증은 Basic base64("시크릿키:"). 시크릿 키는 서버에서만 쓰고 저장소에 두지 않는다 (local: application-secret.yml, prod: Parameter Store).
 * Idempotency-Key를 붙여 같은 요청을 다시 보내도 토스가 한 번만 처리하고 첫 응답을 돌려주게 한다.
 * 승인은 카드사까지 다녀오므로 응답 대기를 넉넉히(30초), 연결은 짧게(3초).
 */
@Slf4j
@Component
public class HttpTossPaymentsClient implements TossPaymentsClient {

    private final RestClient restClient;
    private final boolean configured;

    public HttpTossPaymentsClient(@Value("${banting.toss.secret-key:}") String secretKey,
                                  @Value("${banting.toss.base-url:https://api.tosspayments.com}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        String basic = Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .build();
        this.configured = !secretKey.isBlank();
        if (!configured) {
            log.warn("banting.toss.secret-key가 비어 있어 결제 승인이 동작하지 않습니다");
        }
    }

    @Override
    public Approval confirm(String paymentKey, String orderId, long amount) {
        requireConfigured();
        ConfirmResponse response = call(() -> restClient.post()
                .uri("/v1/payments/confirm")
                .header("Idempotency-Key", "confirm-" + orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount))
                .retrieve()
                .body(ConfirmResponse.class));
        if (response == null) {
            throw new TossUnavailableException("토스 승인 응답이 비어 있음", null);
        }
        return new Approval(response.paymentKey(), response.orderId(), response.status(),
                response.totalAmount(), response.approvedAt());
    }

    @Override
    public void cancel(String paymentKey, String orderId, String reason) {
        requireConfigured();
        call(() -> restClient.post()
                .uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                .header("Idempotency-Key", "cancel-" + orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("cancelReason", reason))
                .retrieve()
                .toBodilessEntity());
    }

    private void requireConfigured() {
        if (!configured) {
            throw new TossUnavailableException("토스 시크릿 키 미설정", null);
        }
    }

    /** 4xx는 토스의 확실한 거절(오류 코드 포함), 그 밖의 실패(5xx·타임아웃)는 결과를 모르는 실패. */
    private <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException e) {
            ErrorResponse error = parseError(e);
            throw new TossRejectedException(error.code(), error.message());
        } catch (RestClientException e) {
            throw new TossUnavailableException("토스 호출 실패: " + e.getMessage(), e);
        }
    }

    private static ErrorResponse parseError(HttpClientErrorException e) {
        try {
            ErrorResponse error = e.getResponseBodyAs(ErrorResponse.class);
            if (error != null && error.code() != null) {
                return error;
            }
        } catch (RuntimeException ignored) {
            // 본문이 JSON이 아니면 상태 코드로만 기록
        }
        return new ErrorResponse("HTTP_" + e.getStatusCode().value(), e.getStatusText());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConfirmResponse(String paymentKey, String orderId, String status, long totalAmount,
                           OffsetDateTime approvedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorResponse(String code, String message) {
    }
}
