package com.banting.demo;

import com.banting.demo.TrialService.TrialStart;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class TrialController {

    private final TrialService trialService;

    public record StartRequest(@NotNull(message = "예시 공구를 골라 주세요") Long seedGroupBuyId) {
    }

    /** 체험 시작: 새로 만들면 201, 진행 중이던 체험으로 보내면 200. 응답의 groupBuyId 상세 화면에서 참여·결제. */
    @PostMapping("/api/trials")
    public ResponseEntity<TrialStart> start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StartRequest request) {
        TrialStart start = trialService.start(Long.valueOf(jwt.getSubject()), request.seedGroupBuyId());
        return ResponseEntity.status(start.resumed() ? HttpStatus.OK : HttpStatus.CREATED).body(start);
    }
}
