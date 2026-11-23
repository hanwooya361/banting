package com.banting.inquiry;

import com.banting.inquiry.InquiryService.AdminInquiryView;
import com.banting.inquiry.InquiryService.InquiryView;
import com.banting.inquiry.InquiryService.NoticeView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 (T17). /api/admin/** 는 SecurityConfig에서 ROLE_ADMIN만 통과한다. */
@RestController
@RequiredArgsConstructor
public class AdminInquiryController {

    private final InquiryService inquiryService;

    public record AnswerRequest(
            @NotBlank(message = "답변을 적어 주세요")
            @Size(max = InquiryService.CONTENT_MAX, message = "답변은 2,000자까지예요") String answer,
            String issueUrl) {
    }

    public record NoticeRequest(
            @NotBlank(message = "제목을 적어 주세요")
            @Size(max = InquiryService.TITLE_MAX, message = "제목은 100자까지예요") String title,
            @NotBlank(message = "내용을 적어 주세요")
            @Size(max = 10_000, message = "내용이 너무 길어요") String content) {
    }

    /** status가 없으면 전체, OPEN이면 답변 대기만. */
    @GetMapping("/api/admin/inquiries")
    public List<AdminInquiryView> list(@RequestParam(required = false) InquiryStatus status) {
        return inquiryService.listForAdmin(status);
    }

    @PostMapping("/api/admin/inquiries/{id}/answer")
    public InquiryView answer(@PathVariable Long id, @Valid @RequestBody AnswerRequest request) {
        return inquiryService.answer(id, request.answer(), request.issueUrl());
    }

    @PostMapping("/api/admin/notices")
    public ResponseEntity<NoticeView> createNotice(@Valid @RequestBody NoticeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(inquiryService.createNotice(request.title(), request.content()));
    }
}
