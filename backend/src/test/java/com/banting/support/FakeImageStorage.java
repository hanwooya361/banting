package com.banting.support;

import com.banting.common.storage.ImageStorage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** S3 대신 메모리에 키만 기록하는 가짜 저장소. 테스트에서 "업로드됨/복사됨/삭제됨"을 확인한다. */
public class FakeImageStorage implements ImageStorage {

    private final Set<String> objects = ConcurrentHashMap.newKeySet();
    private final List<String> deleted = new ArrayList<>();
    private volatile boolean failCopy;

    /** 브라우저가 presigned URL로 업로드를 마친 상태를 흉내. */
    public void uploaded(String key) {
        objects.add(key);
    }

    public boolean has(String key) {
        return objects.contains(key);
    }

    public synchronized List<String> deleted() {
        return List.copyOf(deleted);
    }

    public void failCopy(boolean fail) {
        this.failCopy = fail;
    }

    public synchronized void reset() {
        objects.clear();
        deleted.clear();
        failCopy = false;
    }

    @Override
    public PresignedUpload presignUpload(String key, String contentType, long contentLength, Duration ttl) {
        return new PresignedUpload("https://fake-s3.local/" + key + "?signed", key, contentType);
    }

    @Override
    public boolean exists(String key) {
        return objects.contains(key);
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        if (failCopy) {
            throw new IllegalStateException("copy 실패 (테스트)");
        }
        objects.add(targetKey);
    }

    @Override
    public synchronized void delete(String key) {
        objects.remove(key);
        deleted.add(key);
    }
}
