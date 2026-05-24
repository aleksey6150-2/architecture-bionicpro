package com.bionicpro.reports.s3;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;

/**
 * Хранение отчётов в Minio (S3-compatible) и формирование CDN-URL.
 *
 * Ключ объекта: <username>/<watermark>.json. Версионирование по дате
 * обработки ETL — когда watermark двигается, отчёт получает новый ключ,
 * а старые остаются (или вычистятся по LRU в CDN-кеше).
 *
 * Bucket-policy на reports-bucket выставлен в "download" (anonymous read)
 * через minio-init контейнер, поэтому CDN ходит в Minio без подписи.
 */
@Service
public class S3ReportService {

    private static final Logger log = LoggerFactory.getLogger(S3ReportService.class);

    private final MinioClient minio;
    private final String bucket;
    private final String cdnBaseUrl;

    public S3ReportService(
            @Value("${minio.endpoint}") String endpoint,
            @Value("${minio.access-key}") String accessKey,
            @Value("${minio.secret-key}") String secretKey,
            @Value("${minio.bucket}") String bucket,
            @Value("${cdn.base-url}") String cdnBaseUrl
    ) {
        this.minio = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.bucket = bucket;
        this.cdnBaseUrl = cdnBaseUrl.endsWith("/")
                ? cdnBaseUrl.substring(0, cdnBaseUrl.length() - 1)
                : cdnBaseUrl;
    }

    @PostConstruct
    public void ensureBucket() {
        try {
            boolean exists = minio.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build()
            );
            if (!exists) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created S3 bucket {}", bucket);
            } else {
                log.info("S3 bucket {} already exists", bucket);
            }
        } catch (Exception e) {
            // Не валим старт сервиса — minio-init может создать bucket после нас.
            log.warn("Could not verify S3 bucket {}: {}", bucket, e.toString());
        }
    }

    /**
     * Проверка наличия отчёта в S3. true — не нужно перегенерировать,
     * можно сразу отдать CDN-ссылку.
     */
    public boolean exists(String username, LocalDate watermark) {
        try {
            minio.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(key(username, watermark))
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            // NoSuchKey / NoSuchBucket
            return false;
        } catch (Exception e) {
            log.warn("S3 stat failed for {}/{}: {}",
                    username, watermark, e.toString());
            return false;
        }
    }

    /**
     * Залить JSON отчёта в S3 под версионированный ключ.
     */
    public void store(String username, LocalDate watermark, byte[] json) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(json)) {
            minio.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key(username, watermark))
                    .stream(in, json.length, -1)
                    .contentType("application/json")
                    .build());
            log.info("Stored report {}/{}.json ({} bytes)",
                    username, watermark, json.length);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to store report in S3: " + e.getMessage(), e);
        }
    }

    /**
     * Публичный URL отчёта через CDN (не прямой Minio).
     */
    public String cdnUrl(String username, LocalDate watermark) {
        return cdnBaseUrl + "/" + key(username, watermark);
    }

    private String key(String username, LocalDate watermark) {
        return username + "/" + watermark.toString() + ".json";
    }
}
