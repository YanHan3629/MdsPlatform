package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.dto.mm.MmFolderFileItemReq;
import com.fwdrobo.sirius.dto.mm.MmFolderUploadTargetResp;
import com.fwdrobo.sirius.dto.mm.MmUploadSessionFileResultReq;
import com.fwdrobo.sirius.dto.mm.MmUploadSessionSummaryResp;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.PathUtils;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MmFolderUploadSessionService {

    private final FileService fileService;
    private final Map<UUID, SessionState> sessions = new ConcurrentHashMap<>();

    public MmFolderUploadSessionService(FileService fileService) {
        this.fileService = fileService;
    }

    public MmUploadSessionSummaryResp createSession(UUID datasetId,
                                                    UUID versionId,
                                                    UUID repoId,
                                                    UUID commitId,
                                                    UUID userId,
                                                    String basePath,
                                                    int expireSeconds,
                                                    Integer expectedFileCount) {
        String normalizedBasePath = PathUtils.normalizePath(basePath);
        UUID sessionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        SessionState state = new SessionState(
                sessionId,
                datasetId,
                versionId,
                repoId,
                commitId,
                userId,
                normalizedBasePath,
                expireSeconds,
                expectedFileCount,
                now,
                now.plusSeconds(expireSeconds)
        );
        sessions.put(sessionId, state);
        return toSummary(state);
    }

    public List<MmFolderUploadTargetResp> beginBatch(UUID sessionId,
                                                     UUID datasetId,
                                                     UUID versionId,
                                                     UUID userId,
                                                     List<MmFolderFileItemReq> files) throws Exception {
        if (files == null || files.isEmpty()) {
            throw ExceptionUtils.badRequest("files 不能为空");
        }
        SessionState state = getActiveSession(sessionId, datasetId, versionId, userId);

        List<MmFolderUploadTargetResp> targets = new ArrayList<>();
        Map<String, String> dedupFinalPathInRequest = new LinkedHashMap<>();

        synchronized (state) {
            ensureActive(state);
            for (MmFolderFileItemReq file : files) {
                String relativePath = PathUtils.normalizeRelativePath(file.relativePath());
                String finalPath = PathUtils.resolveChildPath(state.basePath, relativePath);
                String existedRelativePath = dedupFinalPathInRequest.putIfAbsent(finalPath, relativePath);
                if (existedRelativePath != null) {
                    throw ExceptionUtils.badRequest("同一批次存在重复目标路径: " + finalPath);
                }

                var presign = fileService.beginWrite(
                        state.repoId,
                        state.commitId,
                        finalPath,
                        file.contentType(),
                        Duration.ofSeconds(state.expireSeconds),
                        null
                );

                FileState fileState = state.filesByFinalPath.get(finalPath);
                if (fileState == null) {
                    fileState = new FileState(relativePath, finalPath, file.contentType());
                    state.filesByFinalPath.put(finalPath, fileState);
                } else {
                    fileState.relativePath = relativePath;
                    fileState.contentType = file.contentType();
                }
                fileState.status = FileUploadStatus.PREPARED;
                fileState.errorMessage = null;
                fileState.updatedAt = OffsetDateTime.now();

                targets.add(new MmFolderUploadTargetResp(
                        relativePath,
                        finalPath,
                        presign.getObjectKey(),
                        presign.getUrl(),
                        "PUT"
                ));
            }
        }

        return targets;
    }

    public MmUploadSessionSummaryResp markFilesComplete(UUID sessionId,
                                                        UUID datasetId,
                                                        UUID versionId,
                                                        UUID userId,
                                                        List<MmUploadSessionFileResultReq> results) {
        if (results == null || results.isEmpty()) {
            throw ExceptionUtils.badRequest("results 不能为空");
        }
        SessionState state = getActiveSession(sessionId, datasetId, versionId, userId);

        synchronized (state) {
            ensureActive(state);
            for (MmUploadSessionFileResultReq result : results) {
                String relativePath = PathUtils.normalizeRelativePath(result.relativePath());
                String finalPath = PathUtils.resolveChildPath(state.basePath, relativePath);
                FileState fileState = state.filesByFinalPath.get(finalPath);
                if (fileState == null) {
                    throw ExceptionUtils.badRequest("文件未在会话中预签名: " + relativePath);
                }
                if (result.success()) {
                    fileState.status = FileUploadStatus.UPLOADED;
                    fileState.errorMessage = null;
                } else {
                    fileState.status = FileUploadStatus.FAILED;
                    fileState.errorMessage = result.errorMessage();
                }
                fileState.updatedAt = OffsetDateTime.now();
            }
            return toSummary(state);
        }
    }

    public MmUploadSessionSummaryResp getSession(UUID sessionId,
                                                 UUID datasetId,
                                                 UUID versionId,
                                                 UUID userId) {
        SessionState state = getActiveSession(sessionId, datasetId, versionId, userId);
        synchronized (state) {
            if (isExpired(state) && state.status == UploadSessionStatus.ACTIVE) {
                state.status = UploadSessionStatus.EXPIRED;
            }
            return toSummary(state);
        }
    }

    public MmUploadSessionSummaryResp completeSession(UUID sessionId,
                                                      UUID datasetId,
                                                      UUID versionId,
                                                      UUID userId) {
        SessionState state = getActiveSession(sessionId, datasetId, versionId, userId);
        synchronized (state) {
            ensureActive(state);
            int prepared = 0;
            for (FileState fs : state.filesByFinalPath.values()) {
                if (fs.status == FileUploadStatus.PREPARED) {
                    prepared++;
                }
            }
            if (prepared > 0) {
                throw ExceptionUtils.conflict("仍有文件未确认上传结果: " + prepared);
            }
            if (state.expectedFileCount != null && state.filesByFinalPath.size() < state.expectedFileCount) {
                throw ExceptionUtils.conflict("当前会话文件数不足，expected=" + state.expectedFileCount + ", actual=" + state.filesByFinalPath.size());
            }
            state.status = UploadSessionStatus.COMPLETED;
            state.completedAt = OffsetDateTime.now();
            return toSummary(state);
        }
    }

    private SessionState getActiveSession(UUID sessionId,
                                          UUID datasetId,
                                          UUID versionId,
                                          UUID userId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            throw ExceptionUtils.notFound("upload session 不存在: " + sessionId);
        }
        if (!state.datasetId.equals(datasetId) || !state.versionId.equals(versionId)) {
            throw ExceptionUtils.badRequest("upload session 与 dataset/version 不匹配");
        }
        if (!state.userId.equals(userId)) {
            throw ExceptionUtils.forbidden("无权访问该 upload session");
        }
        if (isExpired(state) && state.status == UploadSessionStatus.ACTIVE) {
            synchronized (state) {
                if (isExpired(state) && state.status == UploadSessionStatus.ACTIVE) {
                    state.status = UploadSessionStatus.EXPIRED;
                }
            }
        }
        return state;
    }

    private void ensureActive(SessionState state) {
        if (isExpired(state)) {
            state.status = UploadSessionStatus.EXPIRED;
            throw ExceptionUtils.conflict("upload session 已过期");
        }
        if (state.status != UploadSessionStatus.ACTIVE) {
            throw ExceptionUtils.conflict("upload session 当前状态为: " + state.status.name());
        }
    }

    private boolean isExpired(SessionState state) {
        return OffsetDateTime.now().isAfter(state.expiredAt);
    }

    private MmUploadSessionSummaryResp toSummary(SessionState state) {
        int prepared = 0;
        int uploaded = 0;
        int failed = 0;
        for (FileState fs : state.filesByFinalPath.values()) {
            if (fs.status == FileUploadStatus.PREPARED) {
                prepared++;
            } else if (fs.status == FileUploadStatus.UPLOADED) {
                uploaded++;
            } else if (fs.status == FileUploadStatus.FAILED) {
                failed++;
            }
        }
        return new MmUploadSessionSummaryResp(
                state.sessionId,
                state.status.name(),
                state.basePath,
                state.expireSeconds,
                state.expectedFileCount,
                state.filesByFinalPath.size(),
                prepared,
                uploaded,
                failed,
                state.createdAt,
                state.expiredAt,
                state.completedAt
        );
    }

    private enum UploadSessionStatus {
        ACTIVE,
        COMPLETED,
        EXPIRED
    }

    private enum FileUploadStatus {
        PREPARED,
        UPLOADED,
        FAILED
    }

    private static class SessionState {
        private final UUID sessionId;
        private final UUID datasetId;
        private final UUID versionId;
        private final UUID repoId;
        private final UUID commitId;
        private final UUID userId;
        private final String basePath;
        private final int expireSeconds;
        private final Integer expectedFileCount;
        private final OffsetDateTime createdAt;
        private final OffsetDateTime expiredAt;
        private final Map<String, FileState> filesByFinalPath = new LinkedHashMap<>();
        private UploadSessionStatus status = UploadSessionStatus.ACTIVE;
        private OffsetDateTime completedAt;

        private SessionState(UUID sessionId,
                             UUID datasetId,
                             UUID versionId,
                             UUID repoId,
                             UUID commitId,
                             UUID userId,
                             String basePath,
                             int expireSeconds,
                             Integer expectedFileCount,
                             OffsetDateTime createdAt,
                             OffsetDateTime expiredAt) {
            this.sessionId = sessionId;
            this.datasetId = datasetId;
            this.versionId = versionId;
            this.repoId = repoId;
            this.commitId = commitId;
            this.userId = userId;
            this.basePath = basePath;
            this.expireSeconds = expireSeconds;
            this.expectedFileCount = expectedFileCount;
            this.createdAt = createdAt;
            this.expiredAt = expiredAt;
        }
    }

    private static class FileState {
        private String relativePath;
        private final String finalPath;
        private String contentType;
        private FileUploadStatus status = FileUploadStatus.PREPARED;
        private String errorMessage;
        private OffsetDateTime updatedAt = OffsetDateTime.now();

        private FileState(String relativePath, String finalPath, String contentType) {
            this.relativePath = relativePath;
            this.finalPath = finalPath;
            this.contentType = contentType;
        }
    }
}
