package com.fwdrobo.sirius.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.minio.MinioProperties;
import com.fwdrobo.sirius.util.ExceptionUtils;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class MinioService {

    private final MinioClient internalClient;
    private final MinioClient presignClient;
    private final MinioProperties props;
    private final ObjectMapper mapper;
    private static final long DEFAULT_PART_SIZE = 16L * 1024 * 1024;

    public MinioService(@Qualifier("minioInternalClient") MinioClient internalClient,
                        @Qualifier("minioPresignClient") MinioClient presignClient,
                        MinioProperties props,
                        ObjectMapper mapper) {
        this.internalClient = internalClient;
        this.presignClient = presignClient;
        this.props = props;
        this.mapper = mapper;
    }

    // 获取当前 MinIO bucket 名称
    public String getBucket() {
        return props.getBucket();
    }

    // 获取预签名上传 URL (PUT)
    public String presignPut(String objectKey, Duration expire) throws Exception {
        log.debug("presignPut: objectKey={}, expire={}", objectKey, expire);
        int expirySeconds = normalizeExpire(expire);
        // 生成 PUT 预签名 URL
        return presignClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .bucket(props.getBucket())
                        .object(objectKey)
                        .method(Method.PUT)
                        .expiry(expirySeconds, TimeUnit.SECONDS)
                        .build()
        );
    }

    // 获取预签名下载 URL (GET)
    public String presignGet(String objectKey, Duration expire) throws Exception {
        log.debug("presignGet: objectKey={}, expire={}", objectKey, expire);
        int expirySeconds = normalizeExpire(expire);
        // 生成 GET 预签名 URL
        return presignClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .bucket(props.getBucket())
                        .object(objectKey)
                        .method(Method.GET)
                        .expiry(expirySeconds, TimeUnit.SECONDS)
                        .build()
        );
    }

    // 删除对象
    public void removeObject(String objectKey) throws Exception {
        log.debug("removeObject: objectKey={}", objectKey);
        internalClient.removeObject(
                RemoveObjectArgs.builder()
                        .bucket(props.getBucket())
                        .object(objectKey)
                        .build()
        );
    }

    // 保存任意对象为 JSON 到 MinIO
    public <T> String writeJson(String objectKey, T value) throws Exception {
        log.debug("writeJson: objectKey={}", objectKey);
        if (objectKey == null || objectKey.isBlank()) {
            throw ExceptionUtils.badRequest("illegal objectKey");
        }
        if (value == null) {
            throw ExceptionUtils.badRequest("illegal value");
        }

        byte[] bytes = mapper.writeValueAsBytes(value);
        // 直接写入 JSON 对象
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            ObjectWriteResponse resp = internalClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(props.getBucket())
                            .object(objectKey)
                            .stream(in, bytes.length, -1)
                            .contentType("application/json")
                            .build()
            );
            return resp.etag();
        }
    }

    // 获取对象 ETag
    public String statEtag(String objectKey) throws Exception {
        log.debug("statEtag: objectKey={}", objectKey);
        if (objectKey == null || objectKey.isBlank()) {
            throw ExceptionUtils.badRequest("illegal objectKey");
        }
        // 获取对象 ETag
        return internalClient.statObject(
                StatObjectArgs.builder()
                        .bucket(props.getBucket())
                        .object(objectKey)
                        .build()
        ).etag();
    }

    // 读取 JSON 为对象（普通类型）
    public <T> T readJson(String objectKey, Class<T> type) throws Exception {
        log.debug("readJson: objectKey={}, type={}", objectKey, type);
        if (objectKey == null || objectKey.isBlank()) {
            throw ExceptionUtils.badRequest("illegal objectKey");
        }
        if (type == null) {
            throw ExceptionUtils.badRequest("illegal type");
        }

        // 读取 JSON 并反序列化
        try (InputStream in = internalClient.getObject(
                GetObjectArgs.builder()
                        .bucket(props.getBucket())
                        .object(objectKey)
                        .build()
        )) {
            return mapper.readValue(in, type);
        }
    }

    // 读取 JSON 为集合（如 List/Foo<Map<..>>）
    public <T> T readJson(String objectKey, TypeReference<T> typeRef) throws Exception {
        log.debug("readJson: objectKey={}, typeRef={}", objectKey, typeRef);
        if (objectKey == null || objectKey.isBlank()) {
            throw ExceptionUtils.badRequest("illegal objectKey");
        }
        if (typeRef == null) {
            throw ExceptionUtils.badRequest("illegal typeRef");
        }

        // 读取 JSON 并反序列化
        try (InputStream in = internalClient.getObject(
                GetObjectArgs.builder()
                        .bucket(props.getBucket())
                        .object(objectKey)
                        .build()
        )) {
            return mapper.readValue(in, typeRef);
        }
    }

    // 带 If-Match 的条件写：只有 etag 匹配才允许覆盖写
    public <T> String writeJsonIfMatch(String objectKey, T value, String ifMatchEtag) throws Exception {
        log.debug("writeJsonIfMatch: objectKey={}, ifMatchEtag={}", objectKey, ifMatchEtag);
        if (objectKey == null || objectKey.isBlank()) {
            throw ExceptionUtils.badRequest("illegal objectKey");
        }
        if (value == null) {
            throw ExceptionUtils.badRequest("illegal value");
        }
        if (ifMatchEtag == null || ifMatchEtag.isBlank()) {
            throw ExceptionUtils.badRequest("illegal ifMatchEtag");
        }

        byte[] bytes = mapper.writeValueAsBytes(value);
        // 仅当 ETag 匹配时覆盖写
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            ObjectWriteResponse resp = internalClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(props.getBucket())
                            .object(objectKey)
                            .stream(in, bytes.length, -1)
                            .contentType("application/json")
                            .extraHeaders(Map.of("If-Match", ifMatchEtag))
                            .build()
            );
            return resp.etag();
        }
    }

    // 获取对象元信息
    public StatObjectResponse statObject(String objectKey) throws Exception {
        log.debug("statObject: objectKey={}", objectKey);
        if (objectKey == null || objectKey.isBlank()) {
            throw ExceptionUtils.badRequest("illegal objectKey");
        }
        // 获取对象元信息
        return internalClient.statObject(
                StatObjectArgs.builder()
                        .bucket(props.getBucket())
                        .object(objectKey)
                        .build()
        );
    }

    public InputStream getObject(String objectKey) throws Exception {
        log.debug("getObject: objectKey={}", objectKey);
        if (objectKey == null || objectKey.isBlank()) {
            throw ExceptionUtils.badRequest("illegal objectKey");
        }
        return internalClient.getObject(
                GetObjectArgs.builder()
                        .bucket(props.getBucket())
                        .object(objectKey)
                        .build()
        );
    }

    public String putObject(String objectKey, InputStream in, long size, String contentType) throws Exception {
        log.debug("putObject: objectKey={}, size={}, contentType={}", objectKey, size, contentType);
        if (objectKey == null || objectKey.isBlank()) {
            throw ExceptionUtils.badRequest("illegal objectKey");
        }
        if (in == null) {
            throw ExceptionUtils.badRequest("illegal inputStream");
        }
        String resolvedType = (contentType == null || contentType.isBlank())
                ? "application/octet-stream"
                : contentType;
        long objectSize = size >= 0 ? size : -1;
        long partSize = objectSize >= 0 ? -1 : DEFAULT_PART_SIZE;

        ObjectWriteResponse resp = internalClient.putObject(
                PutObjectArgs.builder()
                        .bucket(props.getBucket())
                        .object(objectKey)
                        .stream(in, objectSize, partSize)
                        .contentType(resolvedType)
                        .build()
        );
        return resp.etag();
    }

    /**
     * 列出指定前缀下的所有对象
     *
     * @param prefix 对象前缀，例如 "job/jobId/runId/logs/"
     * @return 对象名称列表，按字母顺序排序
     */
    public List<String> listObjects(String prefix) throws Exception {
        log.debug("listObjects: prefix={}", prefix);
        if (prefix == null) {
            prefix = "";
        }

        List<String> objectNames = new ArrayList<>();
        Iterable<Result<Item>> results = internalClient.listObjects(
                ListObjectsArgs.builder()
                        .bucket(props.getBucket())
                        .prefix(prefix)
                        .recursive(true)
                        .build()
        );

        for (Result<Item> result : results) {
            Item item = result.get();
            objectNames.add(item.objectName());
        }

        // 按名称排序（确保 part-001, part-002... 的顺序）
        objectNames.sort(String::compareTo);

        log.debug("listObjects: found {} objects with prefix={}", objectNames.size(), prefix);
        return objectNames;
    }

    // 校验 expire
    private int normalizeExpire(Duration expire) {
        if (expire == null || expire.isZero() || expire.isNegative()) {
            throw ExceptionUtils.badRequest("illegal expire");
        }
        // 上限为 12h
        long seconds = expire.getSeconds();
        long normalized = Math.min(seconds, 12 * 60 * 60);
        return Math.toIntExact(normalized);
    }
}
