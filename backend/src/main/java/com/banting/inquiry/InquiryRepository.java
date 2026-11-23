package com.banting.inquiry;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

    /** 내 문의 최신순. 인덱스 idx_inquiries_user. */
    List<Inquiry> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    long countByUserIdAndCreatedAtGreaterThanEqual(Long userId, LocalDateTime since);

    /** 관리자 목록: 상태별(없으면 전체) 최신순, 작성자 닉네임 함께. 반환 [Inquiry, String nickname]. */
    @Query("""
            SELECT i, u.nickname FROM Inquiry i, com.banting.user.User u
            WHERE u.id = i.userId AND (:status IS NULL OR i.status = :status)
            ORDER BY i.id DESC
            """)
    List<Object[]> findForAdmin(@Param("status") InquiryStatus status, Pageable pageable);
}
