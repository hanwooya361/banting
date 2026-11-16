package com.banting.common.storage;

import java.time.Duration;

/**
 * 상품 사진 저장소. 구현은 S3, 테스트는 가짜.
 *
 * <p>흐름: 브라우저가 presigned URL로 {@code images/tmp/{회원}/…}에 직접 업로드 → 공구 저장 시 서버가
 * {@code images/group-buys/…}로 복사(publish) → CloudFront {@code /images/*}로 서비스.
 * tmp는 S3 수명 주기 규칙이 1일 후 지우므로, 올리고 버린 사진이 쌓이지 않는다.
 */
public interface ImageStorage {

    String TMP_PREFIX = "images/tmp/";
    String PUBLISHED_PREFIX = "images/group-buys/";

    /** 업로드용 presigned PUT URL. 파일 형식과 크기가 서명에 포함돼 다른 파일로 바꿔 올릴 수 없다. */
    PresignedUpload presignUpload(String key, String contentType, long contentLength, Duration ttl);

    boolean exists(String key);

    /** tmp → 공개 위치로 복사. */
    void copy(String sourceKey, String targetKey);

    void delete(String key);

    record PresignedUpload(String url, String key, String contentType) {
    }
}
