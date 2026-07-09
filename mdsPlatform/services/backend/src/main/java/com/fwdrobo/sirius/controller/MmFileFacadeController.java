package com.fwdrobo.sirius.controller;

import com.fwdrobo.sirius.dto.file.ListFilesResp;
import com.fwdrobo.sirius.dto.file.ListGetResp;
import com.fwdrobo.sirius.dto.file.ListFilesQuery;
import com.fwdrobo.sirius.dto.file.UploadFileResp;
import com.fwdrobo.sirius.dto.mm.MmBeginFolderUploadReq;
import com.fwdrobo.sirius.dto.mm.MmBeginFolderUploadResp;
import com.fwdrobo.sirius.dto.mm.MmBeginUploadReq;
import com.fwdrobo.sirius.dto.mm.MmBeginUploadResp;
import com.fwdrobo.sirius.dto.mm.MmCreateUploadSessionReq;
import com.fwdrobo.sirius.entity.mm.MmDataset;
import com.fwdrobo.sirius.dto.mm.MmFolderFileItemReq;
import com.fwdrobo.sirius.dto.mm.MmFolderUploadTargetResp;
import com.fwdrobo.sirius.dto.mm.MmUploadArchiveResp;
import com.fwdrobo.sirius.dto.mm.MmUploadFolderFileResp;
import com.fwdrobo.sirius.dto.mm.MmUploadFolderResp;
import com.fwdrobo.sirius.dto.mm.MmUploadSessionBatchBeginReq;
import com.fwdrobo.sirius.dto.mm.MmUploadSessionBatchBeginResp;
import com.fwdrobo.sirius.dto.mm.MmUploadSessionFilesCompleteReq;
import com.fwdrobo.sirius.dto.mm.MmUploadSessionSummaryResp;
import com.fwdrobo.sirius.entity.mm.MmDatasetVersion;
import com.fwdrobo.sirius.service.FileService;
import com.fwdrobo.sirius.service.MmArchiveUploadService;
import com.fwdrobo.sirius.service.MmDatasetService;
import com.fwdrobo.sirius.service.MmDatasetVersionService;
import com.fwdrobo.sirius.service.MmFolderUploadSessionService;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.PathUtils;
import com.fwdrobo.sirius.util.SecurityUtils;
import com.fwdrobo.sirius.validation.ValidLogicalPath;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/mm/datasets/{datasetId}/versions/{versionId}")
@Validated
public class MmFileFacadeController {

    private final MmDatasetService mmDatasetService;
    private final MmDatasetVersionService mmDatasetVersionService;
    private final MmArchiveUploadService mmArchiveUploadService;
    private final MmFolderUploadSessionService mmFolderUploadSessionService;
    private final FileService fileService;

    public MmFileFacadeController(MmDatasetService mmDatasetService,
                                  MmDatasetVersionService mmDatasetVersionService,
                                  MmArchiveUploadService mmArchiveUploadService,
                                  MmFolderUploadSessionService mmFolderUploadSessionService,
                                  FileService fileService) {
        this.mmDatasetService = mmDatasetService;
        this.mmDatasetVersionService = mmDatasetVersionService;
        this.mmArchiveUploadService = mmArchiveUploadService;
        this.mmFolderUploadSessionService = mmFolderUploadSessionService;
        this.fileService = fileService;
    }

    @PostMapping("/files:begin-upload")
    public MmBeginUploadResp beginUpload(@PathVariable UUID datasetId,
                                         @PathVariable UUID versionId,
                                         @Valid @RequestBody MmBeginUploadReq req) throws Exception {
        MmDataset dataset = mmDatasetService.getDatasetOrNotFound(datasetId);
        MmDatasetVersion version = assertVersionBelongsToDataset(datasetId, versionId);
        var presign = fileService.beginWrite(
                dataset.getRawRepoId(),
                version.getRawCommitId(),
                req.path(),
                req.contentType(),
                Duration.ofSeconds(req.expireSecondsOrDefault()),
                null
        );
        String logicalPath = req.path().startsWith("/") ? req.path() : "/" + req.path();
        return new MmBeginUploadResp(logicalPath, presign.getObjectKey(), presign.getUrl(), "PUT");
    }

    @PutMapping("/files/content")
    public UploadFileResp uploadContent(@PathVariable UUID datasetId,
                                        @PathVariable UUID versionId,
                                        @RequestParam @ValidLogicalPath String path,
                                        @RequestParam(required = false) String contentType,
                                        HttpServletRequest request) throws Exception {
        MmDataset dataset = mmDatasetService.getDatasetOrNotFound(datasetId);
        MmDatasetVersion version = assertVersionBelongsToDataset(datasetId, versionId);
        String resolvedType = (contentType == null || contentType.isBlank()) ? request.getContentType() : contentType;
        long size = request.getContentLengthLong();
        try (var inputStream = request.getInputStream()) {
            return fileService.uploadContent(
                    dataset.getRawRepoId(),
                    version.getRawCommitId(),
                    path,
                    resolvedType,
                    inputStream,
                    size,
                    null
            );
        }
    }

    @PostMapping("/folders:begin-upload")
    public MmBeginFolderUploadResp beginFolderUpload(@PathVariable UUID datasetId,
                                                     @PathVariable UUID versionId,
                                                     @Valid @RequestBody MmBeginFolderUploadReq req) throws Exception {
        MmDataset dataset = mmDatasetService.getDatasetOrNotFound(datasetId);
        MmDatasetVersion version = assertVersionBelongsToDataset(datasetId, versionId);
        List<MmFolderUploadTargetResp> targets = new ArrayList<>();
        List<ResolvedFolderFile> resolvedFiles = resolveFolderFiles(req.basePath(), req.files());
        Duration expire = Duration.ofSeconds(req.expireSecondsOrDefault());
        for (ResolvedFolderFile item : resolvedFiles) {
            var presign = fileService.beginWrite(
                    dataset.getRawRepoId(),
                    version.getRawCommitId(),
                    item.path(),
                    item.contentType(),
                    expire,
                    null
            );
            targets.add(new MmFolderUploadTargetResp(
                    item.relativePath(),
                    item.path(),
                    presign.getObjectKey(),
                    presign.getUrl(),
                    "PUT"
            ));
        }
        return new MmBeginFolderUploadResp(req.basePath(), targets.size(), targets);
    }

    @PostMapping(value = "/folders/content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MmUploadFolderResp uploadFolderContent(@PathVariable UUID datasetId,
                                                  @PathVariable UUID versionId,
                                                  @RequestParam @ValidLogicalPath String basePath,
                                                  @RequestParam("relativePaths") List<String> relativePaths,
                                                  @RequestPart("files") List<MultipartFile> files) throws Exception {
        if (files == null || files.isEmpty()) {
            throw ExceptionUtils.badRequest("files 不能为空");
        }
        if (relativePaths == null || relativePaths.isEmpty()) {
            throw ExceptionUtils.badRequest("relativePaths 不能为空");
        }
        if (files.size() != relativePaths.size()) {
            throw ExceptionUtils.badRequest("files 与 relativePaths 数量不一致");
        }

        MmDataset dataset = mmDatasetService.getDatasetOrNotFound(datasetId);
        MmDatasetVersion version = assertVersionBelongsToDataset(datasetId, versionId);
        List<MmFolderFileItemReq> fileItems = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            fileItems.add(new MmFolderFileItemReq(relativePaths.get(i), file.getContentType()));
        }
        List<ResolvedFolderFile> resolvedFiles = resolveFolderFiles(basePath, fileItems);
        List<MmUploadFolderFileResp> uploadedFiles = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile multipartFile = files.get(i);
            ResolvedFolderFile resolvedFile = resolvedFiles.get(i);
            try (var inputStream = multipartFile.getInputStream()) {
                UploadFileResp upload = fileService.uploadContent(
                        dataset.getRawRepoId(),
                        version.getRawCommitId(),
                        resolvedFile.path(),
                        resolvedFile.contentType(),
                        inputStream,
                        multipartFile.getSize(),
                        null
                );
                uploadedFiles.add(new MmUploadFolderFileResp(
                        resolvedFile.relativePath(),
                        resolvedFile.path(),
                        upload.getFileId(),
                        upload.getSize(),
                        upload.getContentType()
                ));
            }
        }
        return new MmUploadFolderResp(basePath, uploadedFiles.size(), uploadedFiles);
    }

    @PostMapping("/folders/upload-sessions")
    public MmUploadSessionSummaryResp createFolderUploadSession(@PathVariable UUID datasetId,
                                                                @PathVariable UUID versionId,
                                                                @Valid @RequestBody MmCreateUploadSessionReq req) {
        MmDataset dataset = mmDatasetService.getDatasetOrNotFound(datasetId);
        MmDatasetVersion version = assertVersionBelongsToDataset(datasetId, versionId);
        UUID userId = SecurityUtils.getUserId();
        return mmFolderUploadSessionService.createSession(
                datasetId,
                versionId,
                dataset.getRawRepoId(),
                version.getRawCommitId(),
                userId,
                req.basePathOrDefault(),
                req.expireSecondsOrDefault(),
                req.expectedFileCount()
        );
    }

    @PostMapping("/folders/upload-sessions/{sessionId}/batches:begin")
    public MmUploadSessionBatchBeginResp beginFolderUploadSessionBatch(@PathVariable UUID datasetId,
                                                                       @PathVariable UUID versionId,
                                                                       @PathVariable UUID sessionId,
                                                                       @Valid @RequestBody MmUploadSessionBatchBeginReq req) throws Exception {
        MmDataset dataset = mmDatasetService.getDatasetOrNotFound(datasetId);
        MmDatasetVersion version = assertVersionBelongsToDataset(datasetId, versionId);
        UUID userId = SecurityUtils.getUserId();
        List<MmFolderUploadTargetResp> files = mmFolderUploadSessionService.beginBatch(
                sessionId,
                datasetId,
                versionId,
                userId,
                req.files()
        );
        MmUploadSessionSummaryResp session = mmFolderUploadSessionService.getSession(
                sessionId,
                datasetId,
                versionId,
                userId
        );
        return new MmUploadSessionBatchBeginResp(session, files.size(), files);
    }

    @PostMapping("/folders/upload-sessions/{sessionId}/files:complete")
    public MmUploadSessionSummaryResp completeFolderUploadSessionFiles(@PathVariable UUID datasetId,
                                                                       @PathVariable UUID versionId,
                                                                       @PathVariable UUID sessionId,
                                                                       @Valid @RequestBody MmUploadSessionFilesCompleteReq req) {
        mmDatasetService.getDatasetOrNotFound(datasetId);
        assertVersionBelongsToDataset(datasetId, versionId);
        UUID userId = SecurityUtils.getUserId();
        return mmFolderUploadSessionService.markFilesComplete(
                sessionId,
                datasetId,
                versionId,
                userId,
                req.results()
        );
    }

    @GetMapping("/folders/upload-sessions/{sessionId}")
    public MmUploadSessionSummaryResp getFolderUploadSession(@PathVariable UUID datasetId,
                                                              @PathVariable UUID versionId,
                                                              @PathVariable UUID sessionId) {
        mmDatasetService.getDatasetOrNotFound(datasetId);
        assertVersionBelongsToDataset(datasetId, versionId);
        UUID userId = SecurityUtils.getUserId();
        return mmFolderUploadSessionService.getSession(
                sessionId,
                datasetId,
                versionId,
                userId
        );
    }

    @PostMapping("/folders/upload-sessions/{sessionId}:complete")
    public MmUploadSessionSummaryResp completeFolderUploadSession(@PathVariable UUID datasetId,
                                                                  @PathVariable UUID versionId,
                                                                  @PathVariable UUID sessionId) {
        mmDatasetService.getDatasetOrNotFound(datasetId);
        assertVersionBelongsToDataset(datasetId, versionId);
        UUID userId = SecurityUtils.getUserId();
        return mmFolderUploadSessionService.completeSession(
                sessionId,
                datasetId,
                versionId,
                userId
        );
    }

    @PostMapping(value = "/folders/archive", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MmUploadArchiveResp uploadFolderArchive(@PathVariable UUID datasetId,
                                                   @PathVariable UUID versionId,
                                                   @RequestParam(defaultValue = "/images") @ValidLogicalPath String basePath,
                                                   @RequestParam(defaultValue = "true") boolean imagesOnly,
                                                   @RequestParam(defaultValue = "50") @Min(1) @Max(500) int maxDetailItems,
                                                   @RequestPart("archive") MultipartFile archive) throws Exception {
        MmDataset dataset = mmDatasetService.getDatasetOrNotFound(datasetId);
        MmDatasetVersion version = assertVersionBelongsToDataset(datasetId, versionId);
        return mmArchiveUploadService.uploadArchive(
                dataset.getRawRepoId(),
                version.getRawCommitId(),
                basePath,
                imagesOnly,
                maxDetailItems,
                archive
        );
    }

    @DeleteMapping("/files")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFile(@PathVariable UUID datasetId,
                           @PathVariable UUID versionId,
                           @RequestParam @ValidLogicalPath String path) throws Exception {
        MmDataset dataset = mmDatasetService.getDatasetOrNotFound(datasetId);
        MmDatasetVersion version = assertVersionBelongsToDataset(datasetId, versionId);
        fileService.deleteFile(dataset.getRawRepoId(), version.getRawCommitId(), path);
    }

    @GetMapping("/files")
    public ListFilesResp listFiles(@PathVariable UUID datasetId,
                                   @PathVariable UUID versionId,
                                   @RequestParam(required = false) String glob,
                                   @RequestParam(required = false) @Min(1) Integer pageIdx,
                                   @RequestParam(required = false) @Max(1000) Integer pageSize) throws Exception {
        MmDataset dataset = mmDatasetService.getDatasetOrNotFound(datasetId);
        MmDatasetVersion version = assertVersionBelongsToDataset(datasetId, versionId);
        ListGetResp resp = fileService.listFiles(ListFilesQuery.builder()
                .repoId(dataset.getRawRepoId())
                .commitId(version.getRawCommitId())
                .globPattern(glob)
                .pageIdx(pageIdx)
                .pageSize(pageSize)
                .build());
        return resp.toListFilesResp();
    }

    @GetMapping("/files/content")
    public void downloadContent(@PathVariable UUID datasetId,
                                @PathVariable UUID versionId,
                                @RequestParam @ValidLogicalPath String path,
                                HttpServletResponse response) throws Exception {
        MmDataset dataset = mmDatasetService.getDatasetOrNotFound(datasetId);
        MmDatasetVersion version = assertVersionBelongsToDataset(datasetId, versionId);
        var stream = fileService.downloadContent(dataset.getRawRepoId(), version.getRawCommitId(), path);
        if (stream.contentType() != null && !stream.contentType().isBlank()) {
            response.setContentType(stream.contentType());
        }
        if (stream.size() >= 0) {
            response.setContentLengthLong(stream.size());
        }
        try (var in = stream.inputStream(); var out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    @GetMapping("/files/download-url")
    public Map<String, Object> downloadUrl(@PathVariable UUID datasetId,
                                           @PathVariable UUID versionId,
                                           @RequestParam @ValidLogicalPath String path,
                                           @RequestParam(defaultValue = "600") @Min(1) @Max(43200) Integer expireSeconds) throws Exception {
        MmDataset dataset = mmDatasetService.getDatasetOrNotFound(datasetId);
        MmDatasetVersion version = assertVersionBelongsToDataset(datasetId, versionId);
        var resp = fileService.downloadUrl(dataset.getRawRepoId(), version.getRawCommitId(), path, Duration.ofSeconds(expireSeconds));
        return Map.of(
                "path", PathUtils.normalizePath(path),
                "objectKey", resp.getObjectKey(),
                "url", resp.getUrl(),
                "downloadMethod", "GET"
        );
    }

    private MmDatasetVersion assertVersionBelongsToDataset(UUID datasetId, UUID versionId) {
        MmDatasetVersion version = mmDatasetVersionService.getVersionOrNotFound(versionId);
        if (!datasetId.equals(version.getDatasetId())) {
            throw ExceptionUtils.badRequest("datasetId 与 versionId 不匹配");
        }
        return version;
    }

    private List<ResolvedFolderFile> resolveFolderFiles(String basePath, List<MmFolderFileItemReq> files) {
        if (files == null || files.isEmpty()) {
            throw ExceptionUtils.badRequest("files 不能为空");
        }
        List<ResolvedFolderFile> resolvedFiles = new ArrayList<>();
        Set<String> finalPaths = new HashSet<>();
        for (MmFolderFileItemReq file : files) {
            String relativePath = PathUtils.normalizeRelativePath(file.relativePath());
            String finalPath = PathUtils.resolveChildPath(basePath, relativePath);
            if (!finalPaths.add(finalPath)) {
                throw ExceptionUtils.badRequest("存在重复目标路径: " + finalPath);
            }
            resolvedFiles.add(new ResolvedFolderFile(relativePath, finalPath, file.contentType()));
        }
        return resolvedFiles;
    }

    private record ResolvedFolderFile(String relativePath, String path, String contentType) {
    }
}
