package com.banting.inquiry;

import com.banting.common.BaseTimeEntity;
import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 문의·피드백 (T17). 운영형 프로젝트의 피드백 창구: 사용자 문의 → 관리자 답변(+ GitHub Issue 링크) → 업데이트 노트.
 * 상태 전이: OPEN → ANSWERED (답변을 고치는 건 ANSWERED에서 내용만 바뀜).
 */
@Entity
@Table(name = "inquiries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inquiry extends BaseTimeEntity {

    private static final int ISSUE_URL_MAX = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private InquiryType type;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private InquiryStatus status;

    @Column(columnDefinition = "TEXT")
    private String answer;

    @Column(name = "answered_at")
    private LocalDateTime answeredAt;

    @Column(name = "issue_url", length = ISSUE_URL_MAX)
    private String issueUrl;

    public static Inquiry open(Long userId, InquiryType type, String title, String content) {
        Inquiry inquiry = new Inquiry();
        inquiry.userId = userId;
        inquiry.type = type;
        inquiry.title = title.trim();
        inquiry.content = content.trim();
        inquiry.status = InquiryStatus.OPEN;
        return inquiry;
    }

    /**
     * 답변(또는 답변 고치기). @return 처음 답변이면 true — 알림은 처음 한 번만 보낸다.
     * 이슈 링크는 화면에서 링크로 열리므로 http/https만 받는다 (javascript: 링크로 클릭한 사람을 공격하지 못하게).
     */
    public boolean answer(String answer, String issueUrl, LocalDateTime now) {
        String validIssueUrl = validateIssueUrl(issueUrl);   // 검증이 실패하면 아무것도 바뀌지 않게 먼저
        boolean first = status == InquiryStatus.OPEN;
        this.answer = answer.trim();
        this.issueUrl = validIssueUrl;
        this.status = InquiryStatus.ANSWERED;
        if (first) {
            this.answeredAt = now;
        }
        return first;
    }

    static String validateIssueUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String url = raw.trim();
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null
                    && url.length() <= ISSUE_URL_MAX) {
                return url;
            }
        } catch (URISyntaxException e) {
            // 아래에서 거부
        }
        throw new BusinessException(ErrorCode.INVALID_ISSUE_URL);
    }
}
