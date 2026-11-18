package com.banting.payment;

import com.banting.payment.PaymentService.ConfirmResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /** 토스 결제창 성공 후 successUrl로 받은 값 그대로. amount는 서버 금액과 비교만 한다. */
    public record ConfirmRequest(
            @NotBlank @Size(max = 200) String paymentKey,
            @NotBlank @Size(max = 64) String orderId,
            @Positive long amount) {
    }

    @PostMapping("/api/payments/confirm")
    public ConfirmResponse confirm(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ConfirmRequest request) {
        return paymentService.confirm(Long.valueOf(jwt.getSubject()), request.paymentKey(), request.orderId(),
                request.amount());
    }
}
