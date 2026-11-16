package com.banting.groupbuy;

import com.banting.groupbuy.GroupBuyDtos.CreateRequest;
import com.banting.groupbuy.GroupBuyDtos.Detail;
import com.banting.groupbuy.GroupBuyDtos.UpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/group-buys")
@RequiredArgsConstructor
public class GroupBuyController {

    private final GroupBuyService groupBuyService;

    /** 개설: DRAFT + 개설자 자리 예약. 개설자 결제(orderId 발급)는 T11에서 이 응답에 붙는다. */
    @PostMapping
    public ResponseEntity<Detail> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(groupBuyService.create(userId(jwt), request));
    }

    /** 공개 API. 로그인했으면 내 공구 여부(mine)와 내 결제 금액을 함께 준다. */
    @GetMapping("/{id}")
    public Detail get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return groupBuyService.get(id, jwt == null ? null : userId(jwt));
    }

    @PatchMapping("/{id}")
    public Detail update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                         @Valid @RequestBody UpdateRequest request) {
        return groupBuyService.update(userId(jwt), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        groupBuyService.delete(userId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
