package com.banting.inquiry;

import com.banting.inquiry.InquiryService.InquiryView;
import com.banting.inquiry.InquiryService.NoticeView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class InquiryController {

    private final InquiryService inquiryService;

    public record CreateRequest(
            @NotNull(message = "유형을 골라 주세요") InquiryType type,
            @NotBlank(message = "제목을 적어 주세요")
            @Size(max = InquiryService.TITLE_MAX, message = "제목은 100자까지예요") String title,
            @NotBlank(message = "내용을 적어 주세요")
            @Size(max = InquiryService.CONTENT_MAX, message = "내용은 2,000자까지예요") String content) {
    }

    @PostMapping("/api/inquiries")
    public ResponseEntity<InquiryView> create(@AuthenticationPrincipal Jwt jwt,
                                              @Valid @RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inquiryService.create(
                Long.valueOf(jwt.getSubject()), request.type(), request.title(), request.content()));
    }

    /** 내 문의 최신순 (답변 포함). */
    @GetMapping("/api/inquiries/me")
    public List<InquiryView> mine(@AuthenticationPrincipal Jwt jwt) {
        return inquiryService.mine(Long.valueOf(jwt.getSubject()));
    }

    /** 업데이트 노트 최신순. 로그인 없이 볼 수 있다 (SecurityConfig permitAll). */
    @GetMapping("/api/notices")
    public List<NoticeView> notices() {
        return inquiryService.notices();
    }
}
