package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.mapper.ArtifactFileMapper;
import com.fwdrobo.sirius.mapper.MmAssetMapper;
import com.fwdrobo.sirius.util.MmDeterministicIdUtils;
import com.fwdrobo.sirius.util.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.*;

/** Resolve evidence against its authorized raw snapshot, not just the imported asset table. */
@Service
public class MmSourceFileService {
    private final MmDatasetService datasets;
    private final MmDatasetVersionService versions;
    private final ManifestService manifests;
    private final ArtifactFileMapper files;
    private final MmAssetMapper assets;
    private final FileService fileService;
    private final MinioService minio;
    private final DatasetAccessService access;

    public MmSourceFileService(MmDatasetService datasets, MmDatasetVersionService versions,
                               ManifestService manifests, ArtifactFileMapper files,
                               MmAssetMapper assets, FileService fileService, MinioService minio, DatasetAccessService access) {
        this.datasets = datasets; this.versions = versions; this.manifests = manifests;
        this.files = files; this.assets = assets; this.fileService = fileService;
        this.minio = minio;
        this.access=access;
    }

    public record Source(UUID assetId, String logicalPath, String contentType,
                         boolean available, String reason) {}

    public List<Source> resolve(UUID datasetId, UUID versionId, List<UUID> requested) throws Exception {
        access.requireVersionRead(datasetId,versionId,null);
        if (SecurityUtils.getUserOrgId() == null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号未绑定组织");
        var dataset = datasets.getReadableDatasetOrNotFound(datasetId);
        var version = versions.getVersionOrNotFound(versionId);
        if (!datasetId.equals(version.getDatasetId()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dataset 与 version 不匹配");
        if (requested == null || requested.size() > 2000 || requested.stream().anyMatch(Objects::isNull))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "来源 ID 列表无效或超过 2000 条");
        var snapshot = manifests.getManifest(dataset.getRawRepoId(), version.getRawCommitId());
        Map<UUID, String> paths = new HashMap<>();
        Map<String, UUID> fileIds = new HashMap<>();
        for (var entry : snapshot.getFiles()) {
            paths.put(MmDeterministicIdUtils.assetId(versionId, entry.getPath()), entry.getPath());
            fileIds.put(entry.getPath(), entry.getFileId());
        }
        // Legacy imported IDs are accepted only when their path is in this same snapshot.
        for (UUID id : requested) if (!paths.containsKey(id)) {
            var asset = assets.selectById(id);
            if (asset != null && versionId.equals(asset.getDatasetVersionId())
                    && fileIds.containsKey(asset.getLogicalPath())) paths.put(id, asset.getLogicalPath());
        }
        var ids = requested.stream().map(paths::get).filter(Objects::nonNull)
                .map(fileIds::get).distinct().toList();
        Map<UUID, String> types = new HashMap<>();
        if (!ids.isEmpty()) for (var file : files.selectFilesByIds(ids)) {
            try {
                minio.statObject(file.getFileKey());
                types.put(file.getFileId(), file.getContentType() == null ? "application/octet-stream" : file.getContentType());
            } catch (io.minio.errors.ErrorResponseException error) {
                if (!Set.of("NoSuchKey", "NoSuchObject", "NoSuchVersion").contains(error.errorResponse().code()))
                    throw error; // Storage outages must not be reported as deleted sources.
            }
        }
        List<Source> result = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(requested)) {
            String path = paths.get(id);
            boolean exists = path != null && types.containsKey(fileIds.get(path));
            result.add(new Source(id, path, exists ? types.get(fileIds.get(path)) : null, exists,
                    exists ? null : "来源文件不在该版本的可用快照中，可能已删除；请重新构建索引"));
        }
        return result;
    }

    public String downloadUrl(UUID datasetId, UUID versionId, Source source) throws Exception {
        access.requireVersionRead(datasetId,versionId,null);
        if (!source.available()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, source.reason());
        var dataset = datasets.getReadableDatasetOrNotFound(datasetId);
        var version = versions.getVersionOrNotFound(versionId);
        return fileService.downloadUrl(dataset.getRawRepoId(), version.getRawCommitId(),
                source.logicalPath(), Duration.ofMinutes(5)).getUrl();
    }

    public com.fwdrobo.sirius.dto.file.FileStream open(UUID datasetId,UUID versionId,Source source) throws Exception {
        access.requireVersionRead(datasetId,versionId,null);
        if (!source.available()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,source.reason());
        var dataset=datasets.getReadableDatasetOrNotFound(datasetId);
        var version=versions.getVersionOrNotFound(versionId);
        return fileService.downloadContent(dataset.getRawRepoId(),version.getRawCommitId(),source.logicalPath());
    }
}
