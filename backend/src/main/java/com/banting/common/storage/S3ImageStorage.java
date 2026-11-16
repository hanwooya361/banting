package com.banting.common.storage;

import com.banting.common.BusinessException;
import com.banting.common.ErrorCode;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProcessCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * S3 구현. 서버 코드나 설정 파일에 AWS 키를 두지 않는다.
 * <ul>
 *   <li>운영: SDK 기본 체인 → EC2 역할(IAM Role)의 임시 자격 증명</li>
 *   <li>로컬: {@code banting.images.credentials-process}에 지정한 명령(aws configure export-credentials)으로
 *       개발자의 aws login 세션에서 임시 자격 증명을 받는다. 이 키는 15분짜리라, SDK가 만료 전에 명령을 다시 실행해 갱신한다.
 *       (SDK가 aws login 세션을 직접 읽지 못해서 CLI를 통해 꺼내 쓰는 방식)</li>
 * </ul>
 */
@Slf4j
@Component
public class S3ImageStorage implements ImageStorage {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3ImageStorage(@Value("${banting.images.bucket}") String bucket,
                          @Value("${banting.images.region:ap-southeast-2}") String region,
                          @Value("${banting.images.credentials-process:}") String credentialsProcess) {
        Region awsRegion = Region.of(region);
        AwsCredentialsProvider credentials = credentialsProcess.isBlank()
                ? DefaultCredentialsProvider.builder().build()
                : ProcessCredentialsProvider.builder().command(credentialsProcess).build();
        // 클라이언트는 첫 사용 때 자격 증명을 찾으므로, 로컬에 AWS 로그인이 없어도 앱은 뜬다 (사진 기능만 503)
        this.s3 = S3Client.builder().region(awsRegion).credentialsProvider(credentials).build();
        this.presigner = S3Presigner.builder().region(awsRegion).credentialsProvider(credentials).build();
        this.bucket = bucket;
    }

    @Override
    public PresignedUpload presignUpload(String key, String contentType, long contentLength, Duration ttl) {
        try {
            PutObjectRequest put = PutObjectRequest.builder()
                    .bucket(bucket).key(key).contentType(contentType).contentLength(contentLength)
                    .build();
            String url = presigner.presignPutObject(PutObjectPresignRequest.builder()
                            .signatureDuration(ttl).putObjectRequest(put).build())
                    .url().toString();
            return new PresignedUpload(url, key, contentType);
        } catch (SdkException e) {
            throw unavailable("presign", e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw unavailable("head", e);
        } catch (SdkException e) {
            throw unavailable("head", e);
        }
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        try {
            s3.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket).sourceKey(sourceKey)
                    .destinationBucket(bucket).destinationKey(targetKey)
                    .build());
        } catch (SdkException e) {
            throw unavailable("copy", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException e) {
            throw unavailable("delete", e);
        }
    }

    private BusinessException unavailable(String action, Exception e) {
        log.warn("S3 {} 실패: {}", action, e.getMessage());
        return new BusinessException(ErrorCode.STORAGE_UNAVAILABLE);
    }
}
