package com.banting.groupbuy;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 구매·수령 확인 (T16). */
@RestController
@RequiredArgsConstructor
public class GroupBuyCompletionController {

    private final GroupBuyCompletionService completionService;

    public record ReceiveResponse(boolean groupBuyCompleted) {
    }

    /** 개설자 "구매했어요" (CONFIRMED → PICKUP). */
    @PostMapping("/api/group-buys/{id}/purchased")
    public ResponseEntity<Void> purchased(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        completionService.markPurchased(Long.valueOf(jwt.getSubject()), id);
        return ResponseEntity.noContent().build();
    }

    /** 참여자 "받았어요". 이걸로 전원이 받았으면 공구 완료·정산. */
    @PostMapping("/api/participations/{id}/receive")
    public ReceiveResponse receive(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return new ReceiveResponse(completionService.receive(Long.valueOf(jwt.getSubject()), id));
    }
}
