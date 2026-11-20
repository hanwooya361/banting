package com.banting.notification;

import com.banting.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 인앱 알림 (T15). 문장은 만들 때 완성해 저장한다. */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(name = "group_buy_id", nullable = false)
    private Long groupBuyId;

    @Column(nullable = false, length = 200)
    private String message;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    public static Notification of(Long userId, NotificationType type, Long groupBuyId, String message) {
        Notification n = new Notification();
        n.userId = userId;
        n.type = type;
        n.groupBuyId = groupBuyId;
        n.message = message;
        return n;
    }

    public void read(LocalDateTime now) {
        if (readAt == null) {
            readAt = now;
        }
    }

    public boolean isRead() {
        return readAt != null;
    }
}
