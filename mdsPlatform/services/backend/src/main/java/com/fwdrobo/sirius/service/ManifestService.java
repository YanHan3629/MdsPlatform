package com.fwdrobo.sirius.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fwdrobo.sirius.dto.file.CommitManifest;
import com.fwdrobo.sirius.util.ExceptionUtils;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ManifestService {
    private final MinioService minio;
    private static final int MAX_RETRY = 3;
    private static final String CODE_NO_SUCH_KEY = "NoSuchKey";
    private static final String CODE_NO_SUCH_OBJECT = "NoSuchObject";
    private static final String CODE_PRECONDITION_FAILED = "PreconditionFailed";

    public ManifestService(MinioService minio) {
        this.minio = minio;
    }

    // 生成 manifest 存储 key
    private String manifestObjectKey(UUID artifactId, UUID commitId) {
        return "manifest/artifacts/%s/commits/%s/commit_manifest.json".formatted(artifactId, commitId);
    }

    // 初始化 DRAFT manifest ,总是继承 previous commit
    public CommitManifest initDraft(UUID artifactId, UUID commitId, UUID parentCommitIdOrNull) throws Exception {
        log.debug("initDraft: artifactId={}, commitId={}", artifactId, commitId);
        OffsetDateTime now = OffsetDateTime.now(Clock.systemUTC());
        CommitManifest manifest = CommitManifest.builder()
                .artifactId(artifactId)
                .commitId(commitId)
                .version(1)
                .createdAt(now)
                .updatedAt(now)
                .build();
        // 获取上一个 commitId ,由上一个 commit 作为父节点
        log.debug("previous commit: parentCommitIdOrNull={}", parentCommitIdOrNull);
        if (parentCommitIdOrNull != null) {
            CommitManifest parent = getManifest(artifactId, parentCommitIdOrNull);
            // 复制 files,后续 commit 复制前一个 commit 的 files
            // 深拷贝
            manifest.setFiles(parent.getFiles().stream()
                    .map(f -> new CommitManifest.File(f.getPath(), f.getFileId()))
                    .collect(Collectors.toCollection(ArrayList::new)));
            log.debug("initDraft copied files: artifactId={}, commitId={}, count={}",
                    artifactId, commitId, manifest.getFiles().size());
        }
        minio.writeJson(manifestObjectKey(artifactId, commitId), manifest);
        return manifest;
    }

    // 获取指定 commit 的 manifest
    public CommitManifest getManifest(UUID artifactId, UUID commitId) throws Exception {
        log.debug("getManifest: artifactId={}, commitId={}", artifactId, commitId);
        return readManifest(manifestObjectKey(artifactId, commitId), commitId);
    }

    // 在 manifest 中新增或替换文件
    public void addOrReplaceFile(UUID artifactId, UUID commitId, String path, UUID fileId) throws Exception {
        log.debug("addOrUpdateFile: artifactId={}, commitId={}, path={}, fileId={}",
                artifactId, commitId, path, fileId);
        String key = manifestObjectKey(artifactId, commitId);
        // 基于 ETag 的乐观并发控制，冲突则重试
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            ManifestWithEtag current = readManifestWithEtag(artifactId, commitId);
            CommitManifest manifest = current.manifest;

            boolean found = false;
            for (CommitManifest.File f : manifest.getFiles()) {
                if (path.equals(f.getPath())) {
                    f.setFileId(fileId);
                    found = true;
                    break;
                }
            }
            if (!found) {
                manifest.getFiles().add(new CommitManifest.File(path, fileId));
            }

            updateMeta(manifest);
            try {
                // 仅当 ETag 未变化时才覆盖写
                minio.writeJsonIfMatch(key, manifest, current.etag);
                log.debug("addOrUpdateFile success: artifactId={}, commitId={}, attempt={}",
                        artifactId, commitId, attempt);
                return;
            } catch (ErrorResponseException e) {
                if (isPreconditionFailed(e)) {
                    // 并发冲突，重试
                    log.debug("addOrUpdateFile conflict, retry: artifactId={}, commitId={}, attempt={}",
                            artifactId, commitId, attempt);
                    continue;
                }
                if (isPreconditionFailed(e)) {
                    log.warn("addOrUpdateFile conflict: artifactId={}, commitId={}, path={}",
                            artifactId, commitId, path);
                    throw ExceptionUtils.conflict("manifest update conflict");
                }
                throw e;
            }
        }
        throw ExceptionUtils.conflict("manifest update conflict");
    }

    // 从 manifest 中移除文件条目
    public UUID removeFileEntry(UUID artifactId, UUID commitId, String path) throws Exception {
        log.debug("removeFileEntry: artifactId={}, commitId={}, path={}", artifactId, commitId, path);
        String key = manifestObjectKey(artifactId, commitId);
        // 基于 ETag 的乐观并发控制，冲突则重试
        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            ManifestWithEtag current = readManifestWithEtag(artifactId, commitId);
            CommitManifest manifest = current.manifest;

            UUID removedFileId = null;
            for (var it = manifest.getFiles().iterator(); it.hasNext(); ) {
                CommitManifest.File f = it.next();
                if (path.equals(f.getPath())) {
                    removedFileId = f.getFileId();
                    it.remove();
                    break;
                }
            }

            if (removedFileId == null) {
                return null;
            }

            updateMeta(manifest);
            try {
                // 仅当 ETag 未变化时才覆盖写
                minio.writeJsonIfMatch(key, manifest, current.etag);
                log.debug("removeFileEntry success: artifactId={}, commitId={}, attempt={}",
                        artifactId, commitId, attempt);
                return removedFileId;
            } catch (ErrorResponseException e) {
                if (isPreconditionFailed(e)) {
                    // 并发冲突，重试
                    log.debug("removeFileEntry conflict, retry: artifactId={}, commitId={}, attempt={}",
                            artifactId, commitId, attempt);
                    continue;
                }
                if (isPreconditionFailed(e)) {
                    log.warn("removeFileEntry conflict: artifactId={}, commitId={}, path={}",
                            artifactId, commitId, path);
                    throw ExceptionUtils.conflict("manifest update conflict");
                }
                throw e;
            }
        }
        throw ExceptionUtils.conflict("manifest update conflict");
    }

    // 递增版本号并更新时间
    private void updateMeta(CommitManifest manifest) {
        manifest.setVersion(manifest.getVersion() + 1);
        manifest.setUpdatedAt(OffsetDateTime.now(Clock.systemUTC()));
    }

    // 读取 manifest 并附带 ETag，用于条件写
    private ManifestWithEtag readManifestWithEtag(UUID artifactId, UUID commitId) throws Exception {
        String key = manifestObjectKey(artifactId, commitId);
        String etag = statEtagOrThrow(key, commitId);
        CommitManifest manifest = readManifest(key, commitId);
        return new ManifestWithEtag(manifest, etag);
    }

    private CommitManifest readManifest(String key, UUID commitId) throws Exception {
        log.debug("readManifest: key={}, commitId={}", key, commitId);
        try {
            CommitManifest manifest = minio.readJson(key, CommitManifest.class);
            if (manifest.getFiles() == null) {
                manifest.setFiles(new ArrayList<>());
            }
            return manifest;
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? "" : e.errorResponse().code();
            if (CODE_NO_SUCH_KEY.equals(code) || CODE_NO_SUCH_OBJECT.equals(code)) {
                // manifest 缺失是数据/流程问题，返回 500
                throw ExceptionUtils.internalError("manifest missing: " + commitId);
            }
            throw e;
        } catch (JsonProcessingException e) {
            // JSON 损坏或不符合结构，返回 409
            throw ExceptionUtils.conflict("manifest corrupted: " + commitId);
        }
    }

    private String statEtagOrThrow(String key, UUID commitId) throws Exception {
        log.debug("statEtag: key={}, commitId={}", key, commitId);
        try {
            return minio.statEtag(key);
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? "" : e.errorResponse().code();
            if (CODE_NO_SUCH_KEY.equals(code) || CODE_NO_SUCH_OBJECT.equals(code)) {
                // manifest 缺失是数据/流程问题，返回 500
                throw ExceptionUtils.internalError("manifest missing: " + commitId);
            }
            throw e;
        }
    }

    // 判断是否为 If-Match 冲突
    private boolean isPreconditionFailed(ErrorResponseException e) {
        String code = e.errorResponse() == null ? "" : e.errorResponse().code();
        return CODE_PRECONDITION_FAILED.equals(code);
    }

    private record ManifestWithEtag(CommitManifest manifest, String etag) {
    }
}