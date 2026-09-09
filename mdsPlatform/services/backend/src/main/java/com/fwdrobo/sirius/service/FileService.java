package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.dto.file.CommitManifest;
import com.fwdrobo.sirius.dto.file.FileResp;
import com.fwdrobo.sirius.dto.file.FileStream;
import com.fwdrobo.sirius.dto.file.ListFilesQuery;
import com.fwdrobo.sirius.dto.file.ListGetResp;
import com.fwdrobo.sirius.dto.file.PresignGetResp;
import com.fwdrobo.sirius.dto.file.PresignPutResp;
import com.fwdrobo.sirius.dto.file.UploadFileResp;
import com.fwdrobo.sirius.entity.artifact.ArtifactCommit;
import com.fwdrobo.sirius.entity.artifact.CommitStatus;
import com.fwdrobo.sirius.entity.file.AddOrReplaceFileResult;
import com.fwdrobo.sirius.mapper.ArtifactCommitMapper;
import com.fwdrobo.sirius.mapper.ArtifactFileMapper;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.DataFileFormatClassifier;
import com.fwdrobo.sirius.util.GlobUtils;
import com.fwdrobo.sirius.util.PathUtils;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class FileService {

    private final MinioService minioService;
    private final ArtifactCommitMapper artifactCommitMapper;
    private final ArtifactFileMapper artifactFileMapper;
    private final ManifestService manifestService;
    private final TrialLimitService trialLimitService;

    private static final String CODE_NO_SUCH_KEY = "NoSuchKey";
    private static final String CODE_NO_SUCH_OBJECT = "NoSuchObject";
    private static final int UPLOAD_METADATA_MAX_RETRIES = 3;
    private static final long UPLOAD_METADATA_RETRY_BACKOFF_MS = 100L;

    public FileService(
            MinioService minioService,
            ArtifactCommitMapper artifactCommitMapper,
            ArtifactFileMapper artifactFileMapper,
            ManifestService manifestService,
            TrialLimitService trialLimitService
    ) {
        this.minioService = minioService;
        this.artifactCommitMapper = artifactCommitMapper;
        this.artifactFileMapper = artifactFileMapper;
        this.manifestService = manifestService;
        this.trialLimitService = trialLimitService;
    }

    // PUT：draft 覆盖写/新增
    public PresignPutResp beginWrite(
            UUID artifactId,
            UUID commitId,
            String path,
            String contentType,
            Duration expire,
            UUID deviceId
    ) throws Exception {
        log.info("beginWrite: artifactId={}, commitId={}, path={}, contentType={}, expire={}, deviceId={}",
                artifactId, commitId, path, contentType, expire, deviceId);

        // draft 才允许写/删
        ensureCommitStatusIs(artifactId, commitId, CommitStatus.DRAFT);

        String logicalPath = PathUtils.normalizePath(path);
        FileResp existingFile = artifactFileMapper.selectByCommitIdAndPath(commitId, logicalPath);
        String objectKey = existingFile == null
                ? buildWorkspaceKey(artifactId, commitId, logicalPath, contentType)
                : existingFile.getFileKey();
        validateExpire(expire);

        // upsert 文件元信息，再写入 manifest
        AddOrReplaceFileResult fileResult = artifactFileMapper.addOrReplaceFile(
                commitId,
                logicalPath,
                minioService.getBucket(),
                objectKey,
                contentType,
                deviceId
        );
        UUID fileId = fileResult.getFileId();
        manifestService.addOrReplaceFile(artifactId, commitId, logicalPath, fileId);

        // 返回预签名 URL 给客户端上传
        String url = minioService.presignPut(objectKey, expire);
        return new PresignPutResp(objectKey, url);
    }

    /**
     * PUT：后端转发上传（draft 覆盖写/新增）。
     */
    public UploadFileResp uploadContent(
            UUID artifactId,
            UUID commitId,
            String path,
            String contentType,
            InputStream inputStream,
            long size,
            UUID deviceId
    ) throws Exception {
        log.info("uploadContent: artifactId={}, commitId={}, path={}, contentType={}, size={}, deviceId={}",
                artifactId, commitId, path, contentType, size, deviceId);

        // draft 才允许写
        ensureCommitStatusIs(artifactId, commitId, CommitStatus.DRAFT);

        String logicalPath = PathUtils.normalizePath(path);
        FileResp existingFile = artifactFileMapper.selectByCommitIdAndPath(commitId, logicalPath);
        String objectKey = existingFile == null
                ? buildWorkspaceKey(artifactId, commitId, logicalPath, contentType)
                : existingFile.getFileKey();
        long previousSize = normalizeSize(existingFile == null ? null : existingFile.getSizeBytes());
        long reservedBytes = trialLimitService.reserveUploadQuota(size, previousSize);
        boolean quotaSettled = false;
        try {
            // 先上传对象，失败则直接抛错
            String etag = minioService.putObject(objectKey, inputStream, size, contentType);

            // 上传完毕后从MinIO取元信息
            StatObjectResponse meta = minioService.statObject(objectKey);
            size = requireUploadedSize(meta.size(), logicalPath);
            contentType = meta.contentType();
            String versionId = meta.versionId();

            // upsert 文件元信息，再写入 manifest（失败时做短重试，降低瞬时抖动导致的不一致）
            UUID fileId = null;
            UUID firstInsertedFileId = null;
            boolean insertedEver = false;
            Exception lastError = null;
            for (int attempt = 1; attempt <= UPLOAD_METADATA_MAX_RETRIES; attempt++) {
                try {
                    AddOrReplaceFileResult fileResult = artifactFileMapper.addOrReplaceFileFull(
                            commitId,
                            logicalPath,
                            minioService.getBucket(),
                            objectKey,
                            versionId,
                            etag,
                            size,
                            contentType,
                            deviceId
                    );
                    fileId = fileResult.getFileId();
                    if (fileResult.isInserted()) {
                        insertedEver = true;
                        if (firstInsertedFileId == null) {
                            firstInsertedFileId = fileId;
                        }
                    }
                    manifestService.addOrReplaceFile(artifactId, commitId, logicalPath, fileId);
                    lastError = null;
                    break;
                } catch (Exception e) {
                    lastError = e;
                    log.warn("uploadContent metadata write failed: artifactId={}, commitId={}, path={}, attempt={}/{}",
                            artifactId, commitId, logicalPath, attempt, UPLOAD_METADATA_MAX_RETRIES, e);
                    if (attempt < UPLOAD_METADATA_MAX_RETRIES) {
                        sleepForRetry(attempt);
                    }
                }
            }
            if (lastError != null) {
                log.warn("uploadContent failed, cleanup object and db: artifactId={}, commitId={}, path={}",
                        artifactId, commitId, logicalPath, lastError);
                // 无论是否写入 DB，都尝试清理对象，避免遗留孤儿对象。
                try {
                    minioService.removeObject(objectKey);
                } catch (Exception cleanup) {
                    log.warn("uploadContent cleanup failed: objectKey={}", objectKey, cleanup);
                }
                if (insertedEver && firstInsertedFileId != null) {
                    try {
                        artifactFileMapper.deleteByIdAndCommitId(firstInsertedFileId, commitId);
                    } catch (Exception cleanupDb) {
                        log.warn("uploadContent db cleanup failed: fileId={}", firstInsertedFileId, cleanupDb);
                    }
                }
                throw lastError;
            }

            long normalizedSize = requireUploadedSize(size, logicalPath);
            long sizeDelta = normalizedSize - previousSize;
            long adjustDelta = sizeDelta - reservedBytes;
            if (adjustDelta > 0) {
                trialLimitService.addUsedStorageAfterSuccess(adjustDelta);
            } else if (adjustDelta < 0) {
                trialLimitService.reduceUsedStorageAfterSuccess(-adjustDelta);
            }
            quotaSettled = true;
            return new UploadFileResp(artifactId, commitId, fileId, deviceId, logicalPath, normalizedSize, contentType);
        } catch (Exception ex) {
            if (!quotaSettled) {
                trialLimitService.rollbackReservedUploadQuota(reservedBytes);
            }
            throw ex;
        }
    }

    private void sleepForRetry(int attempt) {
        try {
            Thread.sleep(attempt * UPLOAD_METADATA_RETRY_BACKOFF_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * DELETE：draft 删除。
     */
    public void deleteFile(UUID artifactId, UUID commitId, String path) throws Exception {
        log.info("deleteFile: artifactId={}, commitId={}, path={}", artifactId, commitId, path);
        // 校验状态,只有状态为 draft 才能下载
        ensureCommitStatusIs(artifactId, commitId, CommitStatus.DRAFT);

        String logicalPath = PathUtils.normalizePath(path);
        // 先改 manifest，再删除 DB/对象
        UUID fileId = manifestService.removeFileEntry(artifactId, commitId, logicalPath);
        if (fileId == null) {
            return;
        }
        FileResp existingFile = artifactFileMapper.selectFilesByIds(List.of(fileId)).stream().findFirst().orElse(null);
        long deletedSize = normalizeSize(existingFile == null ? null : existingFile.getSizeBytes());
        artifactFileMapper.deleteByIdAndCommitId(fileId, commitId);
        if (existingFile != null) {
            minioService.removeObject(existingFile.getFileKey());
        }
        trialLimitService.reduceUsedStorageAfterSuccess(deletedSize);
    }

    // GET: 下载
    public PresignGetResp downloadUrl(
            UUID artifactId,
            UUID commitId,
            String path,
            Duration expire
    ) throws Exception {
        log.info("downloadUrl: artifactId={}, commitId={}, path={}, expire={}", artifactId, commitId, path, expire);
        ensureCommitExists(artifactId, commitId);
        validateExpire(expire);
        String logicalPath = PathUtils.normalizePath(path);
        FileResp file = getFile(artifactId, commitId, logicalPath);

        String objectKey = file.getFileKey();
        // 返回预签名 URL 给客户端下载
        String url = minioService.presignGet(objectKey, expire);

        return new PresignGetResp(objectKey, url);
    }

    // GET: 流式转发下载
    public FileStream downloadContent(UUID artifactId, UUID commitId, String path) throws Exception {
        log.info("downloadContent: artifactId={}, commitId={}, path={}", artifactId, commitId, path);

        String logicalPath = PathUtils.normalizePath(path);

        FileResp file = getFile(artifactId, commitId, logicalPath);
        String objectKey = file.getFileKey();
        return getFileStream(file.getContentType(), objectKey, logicalPath);
    }

    // GET: 流式转发下载（通过 fileId）
    public FileStream downloadContentById(UUID artifactId, UUID commitId, UUID fileId) throws Exception {
        log.info("downloadContentById: artifactId={}, commitId={}, fileId={}", artifactId, commitId, fileId);

        ensureCommitExists(artifactId, commitId);

        // 确认这个仓库的 commit 有这个 file
        CommitManifest manifest = manifestService.getManifest(artifactId, commitId);
        boolean exist = manifest.getFiles().stream().anyMatch(f -> f.getFileId().equals(fileId));
        if (!exist) {
            throw ExceptionUtils.notFound("file not found: " + fileId);
        }

        List<FileResp> fileRespList = artifactFileMapper.selectFilesByIds(List.of(fileId));
        FileResp file;
        if (fileRespList != null && !fileRespList.isEmpty()) {
            file = fileRespList.get(0);
        } else {
            throw ExceptionUtils.notFound("file not found: " + fileId);
        }
        String logicalPath = file.getLogicalPath();
        String objectKey = file.getFileKey();

        return getFileStream(file.getContentType(), objectKey, logicalPath);
    }


    // 获取从开始到某个 commit 下的全部文件列表
    public ListGetResp listFiles(ListFilesQuery query) throws Exception {
        UUID artifactId = query.getRepoId();
        UUID commitId = query.getCommitId();

        log.debug("listFiles: artifactId={}, commitId={}, globLike={}, pageIdx={}, pageSize={}",
                artifactId, commitId, query.getGlobPattern(), query.getPageIdx(), query.getPageSize());


        ArtifactCommit commit = artifactCommitMapper.selectByRepoAndCommit(artifactId, commitId);
        if (commit == null) {
            throw ExceptionUtils.notFound("commit不存在");
        }

        // 以 manifest 为索引，按 fileId 反查文件详情
        CommitManifest manifest = manifestService.getManifest(artifactId, commitId);

        // 按 glob 过滤
        PathMatcher glob;
        try {
            glob = GlobUtils.parseGlob(query.getGlobPattern());
        } catch (IllegalArgumentException e) {
            throw ExceptionUtils.badRequest(e.getMessage());
        }

        // 过滤 + 按 path 排序(用于目录展示)
        List<CommitManifest.File> fileList = manifest.getFiles().stream()
                .filter(f -> GlobUtils.matches(glob, f.getPath()))
                .sorted(Comparator.comparing(CommitManifest.File::getPath))
                .toList();

        // 避免数据太多,做分页
        if (query.getPageSize() == null || query.getPageSize() <= 0) {
            query.setPageSize(100);
        }
        if (query.getPageIdx() == null || query.getPageIdx() < 1) {
            query.setPageIdx(1);
        }
        long total = fileList.size();
        int pageSize = query.getPageSize();
        int pageIdx = query.getPageIdx();

        int from = Math.max(0, (pageIdx - 1) * pageSize);
        int to = Math.min(fileList.size(), from + pageSize);
        List<FileResp> fileRespList = new ArrayList<>();
        if (from < to) {
            List<UUID> uuids = fileList.subList(from, to).stream()
                    .map(CommitManifest.File::getFileId).toList();
            fileRespList = artifactFileMapper.selectFilesByIds(uuids);
            fileRespList.sort(Comparator.comparing(FileResp::getLogicalPath));
            if (fileRespList.size() != uuids.size()) {
                log.error("manifest/db inconsistent: artifactId={}, commitId={}, missingCount={},may missing uuids={}...",
                        artifactId, commitId, uuids.size() - fileRespList.size(), uuids.stream().limit(20).toList());
                throw ExceptionUtils.conflict("file list inconsistent, please retry");
            }
        }

        log.debug("listFiles result: artifactId={}, commitId={}, total={}, returned={}",
                artifactId, commitId, total, fileRespList.size());
        return new ListGetResp(
                artifactId,
                commitId,
                fileRespList,
                commit.getCommitStatus(),
                commit.getCreatedAt(),
                commit.getPublishedAt(),
                query.getPageIdx(),
                query.getPageSize(),
                total
        );
    }

    /**
     * 按仓库与提交查询全部文件列表（不分页）。
     */
    public List<FileResp> listFilesByRepoAndCommit(UUID repoId, UUID commitId) {
        ensureCommitExists(repoId, commitId);
        CommitManifest manifest;
        try {
            manifest = manifestService.getManifest(repoId, commitId);
        } catch (Exception e) {
            throw ExceptionUtils.internalError("查询提交文件列表失败");
        }
        if (manifest.getFiles().isEmpty()) {
            return List.of();
        }
        List<UUID> fileIds = manifest.getFiles().stream()
                .map(CommitManifest.File::getFileId)
                .toList();
        List<FileResp> files = artifactFileMapper.selectFilesByIds(fileIds);
        files.sort(Comparator.comparing(FileResp::getLogicalPath));
        return files;
    }

    // 统一获取对象存储中的文件流与元信息
    private FileStream getFileStream(String contentType, String objectKey, String logicalPath) throws Exception {
        try {
            StatObjectResponse stat = minioService.statObject(objectKey);
            if (contentType == null || contentType.isBlank()) {
                contentType = stat.contentType();
            }
            return new FileStream(
                    logicalPath,
                    contentType,
                    objectKey,
                    stat.size(),
                    minioService.getObject(objectKey)
            );
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? "" : e.errorResponse().code();
            if (CODE_NO_SUCH_KEY.equals(code) || CODE_NO_SUCH_OBJECT.equals(code)) {
                throw ExceptionUtils.notFound("file not found: " + logicalPath);
            }
            throw e;
        }
    }

    private FileResp getFile(UUID artifactId, UUID commitId, String logicalPath) throws Exception {
        ensureCommitExists(artifactId, commitId);

        CommitManifest manifest = manifestService.getManifest(artifactId, commitId);
        UUID fileId = manifest.getFiles().stream()
                .filter(f -> f.getPath().equals(logicalPath))
                .map(CommitManifest.File::getFileId)
                .findFirst()
                .orElseThrow(() -> ExceptionUtils.notFound("file not found: " + logicalPath));

        FileResp file = artifactFileMapper.selectFilesByIds(List.of(fileId)).stream()
                .findFirst()
                .orElseThrow(() -> ExceptionUtils.notFound("file not found: " + logicalPath));

        return file;
    }

    // 校验 commit 是否属于当前 artifact
    private void ensureCommitExists(UUID artifactId, UUID commitId) {
        String status = artifactCommitMapper.selectStatusByRepoAndId(artifactId, commitId);
        if (status == null) {
            throw ExceptionUtils.notFound("commit 不存在");
        }
    }

    // 生成存储文件的 key
    String buildWorkspaceKey(UUID artifactId, UUID commitId, String path, String contentType) {
        String p = PathUtils.normalizePath(path).replace('\\', '/');
        // 去掉前导 "/"，避免拼接时产生重复分隔符
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        String category = DataFileFormatClassifier.classify(p, contentType).storageCategory();
        return "workspace/" + artifactId + "/" + commitId + "/" + category + "/" + p;
    }


    // 查询 commit_status 并校验（repoId + commitId）
    private void ensureCommitStatusIs(UUID artifactId, UUID commitId, CommitStatus expected) {
        String status = artifactCommitMapper.selectStatusByRepoAndId(artifactId, commitId);
        if (status == null) {
            throw ExceptionUtils.notFound("commit 不存在");
        }

        CommitStatus actual;
        try {
            actual = CommitStatus.fromString(status); // 严格解析：大小写兼容，未知值直接报错
        } catch (IllegalArgumentException e) {
            throw ExceptionUtils.internalError("invalid commit_status in DB: " + status);
        }
        if (actual != expected) {
            throw ExceptionUtils.conflict("当前状态为: " + actual.name());
        }
    }

    private void validateExpire(Duration expire) {
        if (expire == null || expire.isZero() || expire.isNegative()) {
            throw ExceptionUtils.badRequest("illegal expire");
        }
    }

    /**
     * 校验上传后的对象大小，未知大小直接拒绝，避免配额统计遗漏。
     */
    private long requireUploadedSize(long sizeBytes, String logicalPath) {
        if (sizeBytes < 0) {
            throw ExceptionUtils.internalError("无法获取上传文件大小: " + logicalPath);
        }
        return sizeBytes;
    }

    /**
     * 归一化文件大小，空值按0处理。
     */
    private long normalizeSize(Long sizeBytes) {
        if (sizeBytes == null || sizeBytes < 0) {
            return 0L;
        }
        return sizeBytes;
    }
}
