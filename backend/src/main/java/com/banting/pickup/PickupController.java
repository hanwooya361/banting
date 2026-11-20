package com.banting.pickup;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PickupController {

    private final PickupService pickupService;

    public record VoteRequest(@NotNull(message = "수령 후보를 골라 주세요") Long optionId) {
    }

    /** 수령 후보 투표. 다시 보내면 표를 바꾼다. 응답은 후보별 득표와 내 표. */
    @PostMapping("/api/group-buys/{id}/pickup-votes")
    public PickupService.VoteSummary vote(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                          @Valid @RequestBody VoteRequest request) {
        return pickupService.vote(Long.valueOf(jwt.getSubject()), id, request.optionId());
    }
}
