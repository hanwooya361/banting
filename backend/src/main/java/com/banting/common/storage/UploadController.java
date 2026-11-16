package com.banting.common.storage;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사진 업로드 URL 발급. 파일은 우리 서버를 거치지 않고 브라우저 → S3로 바로 간다 (1GB 서버의 메모리·대역폭 절약).
 * 키에 회원 id를 넣어, 공구 저장 시 "자기가 올린 사진인지" 확인한다.
 */
@RestController
@RequiredArgsConstructor
public class UploadController {

    static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Duration URL_TTL = Duration.ofMinutes(5);
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

    private final ImageStorage imageStorage;

    @PostMapping("/api/uploads/presigned")
    public ImageStorage.PresignedUpload presign(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody PresignRequest request) {
        String extension = EXTENSIONS.get(request.contentType());
        if (extension == null || request.contentLength() < 1 || request.contentLength() > MAX_BYTES) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_IMAGE);
        }
        String key = ImageStorage.TMP_PREFIX + jwt.getSubject() + "/" + UUID.randomUUID() + "." + extension;
        return imageStorage.presignUpload(key, request.contentType(), request.contentLength(), URL_TTL);
    }

    public record PresignRequest(@NotBlank String contentType, @NotNull Long contentLength) {
    }
}
