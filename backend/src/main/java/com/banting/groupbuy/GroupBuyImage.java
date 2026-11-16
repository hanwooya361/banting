package com.banting.groupbuy;

import com.banting.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 공구 사진. url 컬럼에는 S3 키(images/group-buys/…)를 저장하고, 응답할 때 공개 주소로 바꾼다. */
@Entity
@Table(name = "group_buy_images")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GroupBuyImage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_buy_id", nullable = false)
    private Long groupBuyId;

    @Column(name = "url", nullable = false, length = 500)
    private String key;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public static GroupBuyImage of(Long groupBuyId, String key, int sortOrder) {
        GroupBuyImage image = new GroupBuyImage();
        image.groupBuyId = groupBuyId;
        image.key = key;
        image.sortOrder = sortOrder;
        return image;
    }
}
