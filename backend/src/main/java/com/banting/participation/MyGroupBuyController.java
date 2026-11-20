package com.banting.participation;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MyGroupBuyController {

    private final MyGroupBuyService myGroupBuyService;

    /** 내 공구 (S07): 참여 중 / 개설한 / 지난 공구. */
    @GetMapping("/api/users/me/group-buys")
    public MyGroupBuyService.Response list(@AuthenticationPrincipal Jwt jwt) {
        return myGroupBuyService.list(Long.valueOf(jwt.getSubject()));
    }
}
