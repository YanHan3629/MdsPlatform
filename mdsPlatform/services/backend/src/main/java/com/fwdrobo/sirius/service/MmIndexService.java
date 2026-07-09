package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.container.ContainerService;
import com.fwdrobo.sirius.dto.artifact.ArtifactCommitResponse;
import com.fwdrobo.sirius.dto.job.JobRequest;
import com.fwdrobo.sirius.dto.mm.MmBuildIndexReq;
import com.fwdrobo.sirius.dto.mm.MmIndexVersionResp;
import com.fwdrobo.sirius.dto.mm.MmResolvedIndexBundleResp;
import com.fwdrobo.sirius.entity.job.JobRun;
import com.fwdrobo.sirius.entity.mm.MmDataset;
import com.fwdrobo.sirius.entity.mm.MmDatasetVersion;
import com.fwdrobo.sirius.entity.mm.MmIndexVersion;
import com.fwdrobo.sirius.handler.JsonbTypeHandler;
import com.fwdrobo.sirius.mapper.MmDatasetVersionMapper;
import com.fwdrobo.sirius.mapper.MmIndexVersionMapper;
import com.fwdrobo.sirius.util.ExceptionUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MmIndexService {

    private final MmDatasetService mmDatasetService;
    private final MmDatasetVersionService mmDatasetVersionService;
    private final MmDatasetVersionMapper mmDatasetVersionMapper;
    private final MmIndexVersionMapper mmIndexVersionMapper;
    private final ArtifactCommitService artifactCommitService;
    private final JobService jobService;
    private final ContainerService containerService;
    private final MmMetadataImportService mmMetadataImportService;
    private final FileService fileService;
    private final int previewExpireSeconds;
    private final String indexBuilderImage;
    private final String indexBuilderCmd;
    private final String backendBaseUrl;

    public MmIndexService(MmDatasetService mmDatasetService,
                          MmDatasetVersionService mmDatasetVersionService,
                          MmDatasetVersionMapper mmDatasetVersionMapper,
                          MmIndexVersionMapper mmIndexVersionMapper,
                          ArtifactCommitService artifactCommitService,
                          JobService jobService,
                          ContainerService containerService,
                          MmMetadataImportService mmMetadataImportService,
                          FileService fileService,
                          @Value("${mm.search.preview-expire-seconds:600}") int previewExpireSeconds,
                          @Value("${mm.index-builder.image:sirius-mm-index-builder:latest}") String indexBuilderImage,
                          @Value("${mm.index-builder.cmd:python /app/build_index.py}") String indexBuilderCmd,
                          @Value("${app.docker.host}") String backendBaseUrl) {
        this.mmDatasetService = mmDatasetService;
        this.mmDatasetVersionService = mmDatasetVersionService;
        this.mmDatasetVersionMapper = mmDatasetVersionMapper;
        this.mmIndexVersionMapper = mmIndexVersionMapper;
        this.artifactCommitService = artifactCommitService;
        this.jobService = jobService;
        this.containerService = containerService;
        this.mmMetadataImportService = mmMetadataImportService;
        this.fileService = fileService;
        this.previewExpireSeconds = previewExpireSeconds;
        this.indexBuilderImage = indexBuilderImage;
        this.indexBuilderCmd = indexBuilderCmd;
        this.backendBaseUrl = backendBaseUrl;
    }

    @Transactional
    public Map<String, Object> buildIndex(UUID datasetId, UUID versionId, MmBuildIndexReq req, String token) throws Exception {
        MmDataset dataset = mmDatasetService.getDatasetOrNotFound(datasetId);
        MmDatasetVersion version = mmDatasetVersionService.getVersionOrNotFound(versionId);
        if (!datasetId.equals(version.getDatasetId())) {
            throw ExceptionUtils.badRequest("dataset 与 version 不匹配");
        }
        if (!"RAW_READY".equals(version.getVersionStatus()) && !"READY".equals(version.getVersionStatus()) && !"PUBLISHED".equals(version.getVersionStatus())) {
            throw ExceptionUtils.badRequest("只有 RAW_READY/READY/PUBLISHED 版本才能构建索引");
        }

        MmMetadataImportService.ImportSummary summary = mmMetadataImportService.importFromDatasetVersion(dataset, version);
        version.setSampleCount((long) summary.imageCount());
        version.setImageCount((long) summary.imageCount());
        version.setTextCount((long) summary.textCount());
        mmDatasetVersionMapper.update(version);

        ArtifactCommitResponse indexCommit = artifactCommitService.getOrCreateDraft(dataset.getIndexRepoId());

        MmIndexVersion indexVersion = new MmIndexVersion();
        indexVersion.setIndexVersionId(UUID.randomUUID());
        indexVersion.setDatasetId(datasetId);
        indexVersion.setDatasetVersionId(versionId);
        indexVersion.setIndexRepoId(dataset.getIndexRepoId());
        indexVersion.setIndexCommitId(indexCommit.getCommitId());
        indexVersion.setIndexStatus("QUEUED");
        indexVersion.setIndexType(req.indexType());
        indexVersion.setModelName(req.modelName());
        indexVersion.setImageCount((long) summary.imageCount());
        indexVersion.setTextCount((long) summary.textCount());
        indexVersion.setMeta(JsonbTypeHandler.toJsonNode(Map.of("annotationsPath", summary.annotationsPath())));
        mmIndexVersionMapper.insert(indexVersion);

        Map<String, Object> jobParams = new HashMap<>();
        jobParams.put("env", Map.of(
                "datasetId", datasetId.toString(),
                "versionId", versionId.toString(),
                "indexVersionId", indexVersion.getIndexVersionId().toString(),
                "modelName", req.modelName(),
                "indexType", req.indexType(),
                "BACKEND_BASE_URL", backendBaseUrl,
                "BACKEND_BEARER_TOKEN", token == null ? "" : token,
                "backendCallbackReady", "/api/mm/internal/datasets/" + datasetId + "/versions/" + versionId + "/indexes/" + indexVersion.getIndexVersionId() + "/ready",
                "backendCallbackFailed", "/api/mm/internal/datasets/" + datasetId + "/versions/" + versionId + "/indexes/" + indexVersion.getIndexVersionId() + "/failed"
        ));

        JobRequest jobRequest = new JobRequest(
                "mm-build-index-" + version.getVersionName(),
                "TRANSFORM",
                List.of(new JobRequest.Input(dataset.getRawRepoId(), List.of())),
                List.of(dataset.getIndexRepoId()),
                indexBuilderImage,
                indexBuilderCmd,
                false,
                jobParams
        );
        UUID jobId = jobService.createJob(jobRequest, null);
        JobRun run = containerService.runJob(jobId, token);
        indexVersion.setBuildJobId(jobId);
        indexVersion.setBuildRunId(run.getRunId());
        indexVersion.setIndexStatus("BUILDING");
        mmIndexVersionMapper.update(indexVersion);

        version.setVersionStatus("INDEXING");
        mmDatasetVersionMapper.update(version);

        return Map.of(
                "indexVersionId", indexVersion.getIndexVersionId(),
                "buildJobId", jobId,
                "buildRunId", run.getRunId(),
                "indexStatus", indexVersion.getIndexStatus(),
                "importedImageCount", summary.imageCount(),
                "importedTextCount", summary.textCount()
        );
    }

    public MmIndexVersionResp getIndexStatus(UUID datasetId, UUID versionId) {
        MmDatasetVersion version = mmDatasetVersionService.getVersionOrNotFound(versionId);
        if (!datasetId.equals(version.getDatasetId())) {
            throw ExceptionUtils.badRequest("dataset 与 version 不匹配");
        }
        MmIndexVersion indexVersion = mmIndexVersionMapper.selectLatestByDatasetVersionId(versionId);
        if (indexVersion == null) {
            throw ExceptionUtils.notFound("index version 不存在");
        }
        return toResp(indexVersion);
    }

    @Transactional
    public MmIndexVersionResp markReady(UUID datasetId, UUID versionId, UUID indexVersionId,
                                        UUID indexCommitId,
                                        String imageIndexPath,
                                        String textIndexPath,
                                        String imageMetadataPath,
                                        String textMetadataPath,
                                        String manifestPath,
                                        Integer embeddingDim,
                                        Long imageCount,
                                        Long textCount) {
        MmDatasetVersion version = mmDatasetVersionService.getVersionOrNotFound(versionId);
        if (!datasetId.equals(version.getDatasetId())) {
            throw ExceptionUtils.badRequest("dataset 与 version 不匹配");
        }
        mmIndexVersionMapper.markReady(indexVersionId, indexCommitId, imageIndexPath, textIndexPath, imageMetadataPath, manifestPath, embeddingDim, imageCount, textCount);
        MmIndexVersion indexVersion = mmIndexVersionMapper.selectById(indexVersionId);
        Map<String, Object> meta = new HashMap<>();
        meta.put("imageMetadataPath", imageMetadataPath);
        meta.put("textMetadataPath", textMetadataPath == null ? imageMetadataPath : textMetadataPath);
        indexVersion.setMeta(JsonbTypeHandler.toJsonNode(meta));
        mmIndexVersionMapper.update(indexVersion);
        version.setActiveIndexVersionId(indexVersionId);
        version.setVersionStatus("READY");
        mmDatasetVersionMapper.update(version);
        return toResp(mmIndexVersionMapper.selectById(indexVersionId));
    }

    @Transactional
    public MmIndexVersionResp markFailed(UUID datasetId, UUID versionId, UUID indexVersionId, String errorMessage) {
        MmDatasetVersion version = mmDatasetVersionService.getVersionOrNotFound(versionId);
        if (!datasetId.equals(version.getDatasetId())) {
            throw ExceptionUtils.badRequest("dataset 与 version 不匹配");
        }
        mmIndexVersionMapper.markFailed(indexVersionId, errorMessage);
        version.setVersionStatus("FAILED");
        mmDatasetVersionMapper.update(version);
        return toResp(mmIndexVersionMapper.selectById(indexVersionId));
    }

    public MmResolvedIndexBundleResp resolveBundle(UUID datasetId, UUID versionId, UUID indexVersionId) throws Exception {
        MmDataset dataset = mmDatasetService.getDatasetOrNotFound(datasetId);
        MmDatasetVersion version = mmDatasetVersionService.getVersionOrNotFound(versionId);
        if (!datasetId.equals(version.getDatasetId())) {
            throw ExceptionUtils.badRequest("dataset 与 version 不匹配");
        }
        MmIndexVersion indexVersion = mmIndexVersionMapper.selectById(indexVersionId);
        if (indexVersion == null || !indexVersionId.equals(indexVersion.getIndexVersionId())) {
            throw ExceptionUtils.notFound("index version 不存在");
        }
        if (!"READY".equals(indexVersion.getIndexStatus())) {
            throw ExceptionUtils.badRequest("index version 尚未就绪");
        }
        String imageMetadataPath = indexVersion.getMetadataPath();
        String textMetadataPath = imageMetadataPath;
        if (indexVersion.getMeta() != null) {
            if (indexVersion.getMeta().hasNonNull("imageMetadataPath")) {
                imageMetadataPath = indexVersion.getMeta().get("imageMetadataPath").asText();
            }
            if (indexVersion.getMeta().hasNonNull("textMetadataPath")) {
                textMetadataPath = indexVersion.getMeta().get("textMetadataPath").asText();
            }
        }
        Duration expire = Duration.ofSeconds(previewExpireSeconds);
        return new MmResolvedIndexBundleResp(
                datasetId,
                versionId,
                indexVersionId,
                dataset.getIndexRepoId(),
                indexVersion.getIndexCommitId(),
                indexVersion.getManifestPath(),
                indexVersion.getImageIndexPath(),
                indexVersion.getTextIndexPath(),
                imageMetadataPath,
                textMetadataPath,
                fileService.downloadUrl(dataset.getIndexRepoId(), indexVersion.getIndexCommitId(), indexVersion.getManifestPath(), expire).getUrl(),
                fileService.downloadUrl(dataset.getIndexRepoId(), indexVersion.getIndexCommitId(), indexVersion.getImageIndexPath(), expire).getUrl(),
                fileService.downloadUrl(dataset.getIndexRepoId(), indexVersion.getIndexCommitId(), indexVersion.getTextIndexPath(), expire).getUrl(),
                fileService.downloadUrl(dataset.getIndexRepoId(), indexVersion.getIndexCommitId(), imageMetadataPath, expire).getUrl(),
                fileService.downloadUrl(dataset.getIndexRepoId(), indexVersion.getIndexCommitId(), textMetadataPath, expire).getUrl(),
                indexVersion.getModelName(),
                indexVersion.getIndexType()
        );
    }

    private MmIndexVersionResp toResp(MmIndexVersion indexVersion) {
        return new MmIndexVersionResp(indexVersion.getIndexVersionId(), indexVersion.getDatasetId(), indexVersion.getDatasetVersionId(), indexVersion.getIndexStatus(), indexVersion.getIndexType(), indexVersion.getModelName(), indexVersion.getBuildJobId(), indexVersion.getBuildRunId(), indexVersion.getReadyAt(), indexVersion.getErrorMessage());
    }
}
