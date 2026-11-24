package com.banting.inquiry;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import com.banting.notification.NotificationService;
import com.banting.user.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문의 게시판·업데이트 노트 (T17, tech-design 4장 "문의·업데이트 노트").
 * 답변과 답변 알림은 한 트랜잭션: 답변이 저장되면 알림도, 실패하면 둘 다 없다 (T15 알림과 같은 방식).
 */
@Service
@RequiredArgsConstructor
public class InquiryService {

    static final int TITLE_MAX = 100;
    static final int CONTENT_MAX = 2_000;
    static final int DAILY_LIMIT = 10;
    private static final int LIST_LIMIT = 100;

    private final InquiryRepository inquiryRepository;
    private final NoticeRepository noticeRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final Clock clock;

    public record InquiryView(Long id, InquiryType type, String title, String content, String pagePath,
                              InquiryStatus status, String answer, String issueUrl, LocalDateTime createdAt,
                              LocalDateTime answeredAt) {

        static InquiryView of(Inquiry i) {
            return new InquiryView(i.getId(), i.getType(), i.getTitle(), i.getContent(), i.getPagePath(), i.getStatus(),
                    i.getAnswer(), i.getIssueUrl(), i.getCreatedAt(), i.getAnsweredAt());
        }
    }

    /** 관리자 목록 항목: 문의 + 작성자 닉네임 (이메일 등 다른 개인정보는 주지 않음). */
    public record AdminInquiryView(Long id, InquiryType type, String title, String content, String pagePath,
                                   InquiryStatus status,
                                   String answer, String issueUrl, LocalDateTime createdAt, LocalDateTime answeredAt,
                                   String authorNickname) {
    }

    public record NoticeView(Long id, String title, String content, LocalDateTime createdAt) {

        static NoticeView of(Notice n) {
            return new NoticeView(n.getId(), n.getTitle(), n.getContent(), n.getCreatedAt());
        }
    }

    // ───── 회원 ─────

    /** 같은 회원의 동시 요청은 회원 행 잠금으로 한 줄로 세워 하루 제한을 넘지 않게. */
    @Transactional
    public InquiryView create(Long userId, InquiryType type, String title, String content, String pagePath) {
        userRepository.lockById(userId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (inquiryRepository.countByUserIdAndCreatedAtGreaterThanEqual(userId, now.minusDays(1)) >= DAILY_LIMIT) {
            throw new BusinessException(ErrorCode.INQUIRY_DAILY_LIMIT);
        }
        return InquiryView.of(inquiryRepository.save(Inquiry.open(userId, type, title, content, pagePath)));
    }

    @Transactional(readOnly = true)
    public List<InquiryView> mine(Long userId) {
        return inquiryRepository.findByUserIdOrderByIdDesc(userId, PageRequest.ofSize(LIST_LIMIT)).stream()
                .map(InquiryView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<NoticeView> notices() {
        return noticeRepository.findAllByOrderByIdDesc(PageRequest.ofSize(LIST_LIMIT)).stream()
                .map(NoticeView::of).toList();
    }

    // ───── 관리자 ─────

    @Transactional(readOnly = true)
    public List<AdminInquiryView> listForAdmin(InquiryStatus status) {
        return inquiryRepository.findForAdmin(status, PageRequest.ofSize(LIST_LIMIT)).stream()
                .map(row -> {
                    Inquiry i = (Inquiry) row[0];
                    return new AdminInquiryView(i.getId(), i.getType(), i.getTitle(), i.getContent(), i.getPagePath(),
                            i.getStatus(),
                            i.getAnswer(), i.getIssueUrl(), i.getCreatedAt(), i.getAnsweredAt(), (String) row[1]);
                })
                .toList();
    }

    /** 답변 또는 답변 고치기. 처음 답변일 때만 문의한 사람에게 알림. */
    @Transactional
    public InquiryView answer(Long inquiryId, String answer, String issueUrl) {
        Inquiry inquiry = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INQUIRY_NOT_FOUND));
        if (inquiry.answer(answer, issueUrl, LocalDateTime.now(clock))) {
            notificationService.inquiryAnswered(inquiry.getUserId(), inquiry.getTitle());
        }
        return InquiryView.of(inquiry);
    }

    @Transactional
    public NoticeView createNotice(String title, String content) {
        return NoticeView.of(noticeRepository.save(Notice.of(title, content)));
    }
}
