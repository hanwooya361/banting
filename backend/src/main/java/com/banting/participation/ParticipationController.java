package com.banting.participation;

import com.banting.participation.ParticipationService.PaymentOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ParticipationController {

    private final ParticipationService participationService;

    public record ParticipateRequest(
            @Min(value = 1, message = "수량은 1 이상이에요") @Max(value = 1000, message = "수량을 확인해 주세요") int units) {
    }

    /** 참여(개설자는 내 몫 결제 시작) → 결제창에 넘길 주문번호·금액. 개설자의 units는 개설 때 정한 값을 쓴다. */
    @PostMapping("/api/group-buys/{id}/participations")
    public ResponseEntity<PaymentOrder> participate(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                                    @Valid @RequestBody ParticipateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(participationService.participate(Long.valueOf(jwt.getSubject()), id, request.units()));
    }
}
