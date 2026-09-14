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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
    private final String internalToken;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;

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
                          @Value("${app.docker.host}") String backendBaseUrl,
                          @Value("${mm.internal.token:}") String internalToken,
                          JdbcTemplate jdbc,
                          ApplicationEventPublisher events) {
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
        this.internalToken = internalToken;
        this.jdbc = jdbc;
        this.events = events;
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

        MmIndexVersion existing = mmIndexVersionMapper.selectLatestByDatasetVersionId(versionId);
        Map<String,Object> config = com.fwdrobo.sirius.util.IndexProcessingConfig.validate(req.indexConfig());
        String buildModelName = req.modelName() + "-" + com.fwdrobo.sirius.util.IndexProcessingConfig.fingerprint(config).substring(0,12);
        if (!req.rebuildOrDefault() && existing != null
                && req.indexType().equals(existing.getIndexType())
                && buildModelName.equals(existing.getModelName())
                && List.of("QUEUED", "BUILDING", "PREWARMING", "READY").contains(existing.getIndexStatus())) {
            return Map.of(
                    "indexVersionId", existing.getIndexVersionId(),
                    "indexStatus", existing.getIndexStatus(),
                    "reused", true,
                    "message", "idempotent request reused the existing index version");
        }

        MmMetadataImportService.ImportSummary summary = mmMetadataImportService.importFromDatasetVersion(dataset, version);
        version.setSampleCount((long) summary.assetCount());
        version.setImageCount((long) summary.imageCount());
        version.setTextCount((long) summary.textCount());
        mmDatasetVersionMapper.update(version);

        UUID previousIndexCommitId = jdbc.query("""
                        SELECT i.index_commit_id FROM mm_dataset_version candidate
                        JOIN mm_index_version i ON i.index_version_id=candidate.active_index_version_id
                        WHERE candidate.dataset_id=? AND i.index_status='READY'
                        ORDER BY i.ready_at DESC NULLS LAST LIMIT 1
                        """, resultSet -> resultSet.next() ? resultSet.getObject(1, UUID.class) : null, datasetId);
        ArtifactCommitResponse indexCommit = artifactCommitService.getOrCreateDraft(dataset.getIndexRepoId());

        MmIndexVersion indexVersion = new MmIndexVersion();
        indexVersion.setIndexVersionId(UUID.randomUUID());
        indexVersion.setDatasetId(datasetId);
        indexVersion.setDatasetVersionId(versionId);
        indexVersion.setIndexRepoId(dataset.getIndexRepoId());
        indexVersion.setIndexCommitId(indexCommit.getCommitId());
        indexVersion.setIndexStatus("QUEUED");
        indexVersion.setIndexType(req.indexType());
        indexVersion.setModelName(buildModelName);
        indexVersion.setImageCount((long) summary.imageCount());
        indexVersion.setTextCount((long) summary.textCount());
        Map<String, Object> initialIndexMeta = new HashMap<>();
        if (summary.annotationsPath() != null) {
            initialIndexMeta.put("annotationsPath", summary.annotationsPath());
        }
        initialIndexMeta.put("assetCount", summary.assetCount());
        initialIndexMeta.put("indexConfig",config);
        indexVersion.setMeta(JsonbTypeHandler.toJsonNode(initialIndexMeta));
        mmIndexVersionMapper.insert(indexVersion);

        Map<String, Object> jobParams = new HashMap<>();
        Map<String, String> jobEnvironment = new HashMap<>();
        jobEnvironment.put("datasetId", datasetId.toString());
        jobEnvironment.put("versionId", versionId.toString());
        jobEnvironment.put("indexVersionId", indexVersion.getIndexVersionId().toString());
        jobEnvironment.put("modelName", buildModelName);
        jobEnvironment.put("INDEX_PROCESSING_CONFIG_JSON",com.fwdrobo.sirius.util.IndexProcessingConfig.json(config));
        jobEnvironment.put("indexType", req.indexType());
        jobEnvironment.put("BACKEND_BASE_URL", backendBaseUrl);
        jobEnvironment.put("BACKEND_BEARER_TOKEN", token == null ? "" : token);
        jobEnvironment.put("MM_INTERNAL_TOKEN", internalToken);
        jobEnvironment.put("OMP_NUM_THREADS", "4");
        jobEnvironment.put("OPENBLAS_NUM_THREADS", "4");
        jobEnvironment.put("MKL_NUM_THREADS", "4");
        jobEnvironment.put("IMAGE_BATCH_SIZE", "4");
        jobEnvironment.put("TEXT_BATCH_SIZE", "16");
        if (previousIndexCommitId != null && !previousIndexCommitId.equals(indexCommit.getCommitId())) {
            jobEnvironment.put("PREVIOUS_INDEX_COMMIT_ID", previousIndexCommitId.toString());
            jobEnvironment.put("PREVIOUS_ASSET_DIR", "/tmp/previous/professional/assets");
        }
        jobEnvironment.put("backendCallbackReady", "/api/mm/internal/datasets/" + datasetId + "/versions/" + versionId + "/indexes/" + indexVersion.getIndexVersionId() + "/ready");
        jobEnvironment.put("backendCallbackFailed", "/api/mm/internal/datasets/" + datasetId + "/versions/" + versionId + "/indexes/" + indexVersion.getIndexVersionId() + "/failed");
        jobParams.put("env", jobEnvironment);
        jobParams.put("resources", Map.of(
                "cpus", "4",
                "memoryMb", "6144",
                "memorySwapMb", "6144",
                "pidsLimit", "512"
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
                "importedAssetCount", summary.assetCount(),
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
                                        String unifiedIndexPath,
                                        String unifiedMetadataPath,
                                        String representationManifestPath,
                                        String manifestPath,
                                        Integer embeddingDim,
                                        Long imageCount,
                                        Long textCount,
                                        Long unifiedCount,
                                        List<Map<String, Object>> indexes,
                                        String professionalManifestPath) {
        MmDatasetVersion version = mmDatasetVersionService.getVersionOrNotFound(versionId);
        if (!datasetId.equals(version.getDatasetId())) {
            throw ExceptionUtils.badRequest("dataset 与 version 不匹配");
        }
        MmIndexVersion owned = mmIndexVersionMapper.selectById(indexVersionId);
        if (owned == null || !datasetId.equals(owned.getDatasetId()) || !versionId.equals(owned.getDatasetVersionId())) {
            throw ExceptionUtils.notFound("index version 不存在或不属于当前数据版本");
        }
        mmIndexVersionMapper.markReady(indexVersionId, indexCommitId, imageIndexPath, textIndexPath, imageMetadataPath, manifestPath, embeddingDim, imageCount, textCount);
        MmIndexVersion indexVersion = mmIndexVersionMapper.selectById(indexVersionId);
        Map<String, Object> meta = new HashMap<>();
        meta.put("imageMetadataPath", imageMetadataPath);
        meta.put("textMetadataPath", textMetadataPath == null ? imageMetadataPath : textMetadataPath);
        if (unifiedIndexPath != null) {
            meta.put("unifiedIndexPath", unifiedIndexPath);
        }
        if (unifiedMetadataPath != null) {
            meta.put("unifiedMetadataPath", unifiedMetadataPath);
        }
        if (representationManifestPath != null) {
            meta.put("representationManifestPath", representationManifestPath);
        }
        if (unifiedCount != null) {
            meta.put("unifiedCount", unifiedCount);
        }
        meta.put("indexes", indexes == null ? List.of() : indexes);
        if (professionalManifestPath != null) {
            meta.put("professionalManifestPath", professionalManifestPath);
        }
        indexVersion.setMeta(JsonbTypeHandler.toJsonNode(meta));
        mmIndexVersionMapper.update(indexVersion);
        persistRegistrations(indexVersionId, indexes);
        events.publishEvent(new MmIndexPreparedEvent(datasetId, versionId, indexVersionId));
        return toResp(mmIndexVersionMapper.selectById(indexVersionId));
    }

    @Transactional
    public MmIndexVersionResp markFailed(UUID datasetId, UUID versionId, UUID indexVersionId, String errorMessage) {
        MmDatasetVersion version = mmDatasetVersionService.getVersionOrNotFound(versionId);
        if (!datasetId.equals(version.getDatasetId())) {
            throw ExceptionUtils.badRequest("dataset 与 version 不匹配");
        }
        MmIndexVersion owned = mmIndexVersionMapper.selectById(indexVersionId);
        if (owned == null || !datasetId.equals(owned.getDatasetId()) || !versionId.equals(owned.getDatasetVersionId())) {
            throw ExceptionUtils.notFound("index version 不存在或不属于当前数据版本");
        }
        mmIndexVersionMapper.markFailed(indexVersionId, errorMessage);
        version.setVersionStatus(version.getActiveIndexVersionId() == null ? "FAILED" : "READY");
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
        if (indexVersion == null || !datasetId.equals(indexVersion.getDatasetId())
                || !versionId.equals(indexVersion.getDatasetVersionId())) {
            throw ExceptionUtils.notFound("index version 不存在");
        }
        if (!List.of("PREWARMING", "READY").contains(indexVersion.getIndexStatus())) {
            throw ExceptionUtils.badRequest("index version 尚未就绪");
        }
        String imageMetadataPath = indexVersion.getMetadataPath();
        String textMetadataPath = imageMetadataPath;
        String unifiedIndexPath = null;
        String unifiedMetadataPath = null;
        String representationManifestPath = null;
        if (indexVersion.getMeta() != null) {
            if (indexVersion.getMeta().hasNonNull("imageMetadataPath")) {
                imageMetadataPath = indexVersion.getMeta().get("imageMetadataPath").asText();
            }
            if (indexVersion.getMeta().hasNonNull("textMetadataPath")) {
                textMetadataPath = indexVersion.getMeta().get("textMetadataPath").asText();
            }
            if (indexVersion.getMeta().hasNonNull("unifiedIndexPath")) {
                unifiedIndexPath = indexVersion.getMeta().get("unifiedIndexPath").asText();
            }
            if (indexVersion.getMeta().hasNonNull("unifiedMetadataPath")) {
                unifiedMetadataPath = indexVersion.getMeta().get("unifiedMetadataPath").asText();
            }
            if (indexVersion.getMeta().hasNonNull("representationManifestPath")) {
                representationManifestPath = indexVersion.getMeta().get("representationManifestPath").asText();
            }
        }
        Duration expire = Duration.ofSeconds(previewExpireSeconds);
        String unifiedIndexUrl = unifiedIndexPath == null ? null
                : fileService.downloadUrl(dataset.getIndexRepoId(), indexVersion.getIndexCommitId(), unifiedIndexPath, expire).getUrl();
        String unifiedMetadataUrl = unifiedMetadataPath == null ? null
                : fileService.downloadUrl(dataset.getIndexRepoId(), indexVersion.getIndexCommitId(), unifiedMetadataPath, expire).getUrl();
        String representationManifestUrl = representationManifestPath == null ? null
                : fileService.downloadUrl(dataset.getIndexRepoId(), indexVersion.getIndexCommitId(), representationManifestPath, expire).getUrl();
        List<Map<String, Object>> indexFiles = new ArrayList<>();
        LinkedHashSet<String> signedPaths = new LinkedHashSet<>();
        if (indexVersion.getMeta() != null && indexVersion.getMeta().has("indexes")) {
            for (var registration : indexVersion.getMeta().get("indexes")) {
                if (!registration.has("files") || !registration.get("files").isArray()) continue;
                for (var file : registration.get("files")) {
                    if (!file.hasNonNull("path")) continue;
                    String path = file.get("path").asText();
                    if (!signedPaths.add(path)) continue;
                    String url = fileService.downloadUrl(dataset.getIndexRepoId(), indexVersion.getIndexCommitId(), path, expire).getUrl();
                    indexFiles.add(Map.of("path", path, "url", url));
                }
            }
        }
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
                unifiedIndexPath,
                unifiedMetadataPath,
                representationManifestPath,
                fileService.downloadUrl(dataset.getIndexRepoId(), indexVersion.getIndexCommitId(), indexVersion.getManifestPath(), expire).getUrl(),
                fileService.downloadUrl(dataset.getIndexRepoId(), indexVersion.getIndexCommitId(), indexVersion.getImageIndexPath(), expire).getUrl(),
                fileService.downloadUrl(dataset.getIndexRepoId(), indexVersion.getIndexCommitId(), indexVersion.getTextIndexPath(), expire).getUrl(),
                fileService.downloadUrl(dataset.getIndexRepoId(), indexVersion.getIndexCommitId(), imageMetadataPath, expire).getUrl(),
                fileService.downloadUrl(dataset.getIndexRepoId(), indexVersion.getIndexCommitId(), textMetadataPath, expire).getUrl(),
                unifiedIndexUrl,
                unifiedMetadataUrl,
                representationManifestUrl,
                indexVersion.getModelName(),
                indexVersion.getIndexType(),
                indexFiles
        );
    }

    private MmIndexVersionResp toResp(MmIndexVersion indexVersion) {
        return new MmIndexVersionResp(indexVersion.getIndexVersionId(), indexVersion.getDatasetId(), indexVersion.getDatasetVersionId(), indexVersion.getIndexStatus(), indexVersion.getIndexType(), indexVersion.getModelName(), indexVersion.getBuildJobId(), indexVersion.getBuildRunId(), indexVersion.getReadyAt(), indexVersion.getErrorMessage());
    }

    private void persistRegistrations(UUID indexVersionId, List<Map<String, Object>> indexes) {
        if (indexes == null) return;
        for (Map<String, Object> item : indexes) {
            String semanticSpace = String.valueOf(item.getOrDefault("semanticSpace", ""));
            String indexType = String.valueOf(item.getOrDefault("indexType", ""));
            String algorithm = String.valueOf(item.getOrDefault("algorithmVersion", "unknown"));
            String checksum = String.valueOf(item.getOrDefault("contentChecksum",
                    "0000000000000000000000000000000000000000000000000000000000000000"));
            if (semanticSpace.isBlank() || indexType.isBlank()) continue;
            Integer dimension = item.get("dimension") instanceof Number number ? number.intValue() : null;
            jdbc.update("""
                    INSERT INTO mm_index_registration(registration_id,index_version_id,semantic_space,index_type,
                      algorithm_version,model_version,dimension,normalization,distance_definition,
                      build_parameters,file_manifest,content_checksum,coverage,registration_status)
                    VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?::jsonb,'PREWARMING')
                    ON CONFLICT (index_version_id,semantic_space) DO UPDATE SET
                      index_type=excluded.index_type,algorithm_version=excluded.algorithm_version,
                      model_version=excluded.model_version,dimension=excluded.dimension,
                      normalization=excluded.normalization,distance_definition=excluded.distance_definition,
                      build_parameters=excluded.build_parameters,file_manifest=excluded.file_manifest,
                      content_checksum=excluded.content_checksum,coverage=excluded.coverage,registration_status='PREWARMING'
                    """, UUID.randomUUID(), indexVersionId, semanticSpace, indexType, algorithm,
                    item.get("modelVersion"), dimension, String.valueOf(item.getOrDefault("normalization", "none")),
                    String.valueOf(item.getOrDefault("distance", "unknown")),
                    JsonbTypeHandler.toJsonNode(item.getOrDefault("buildParameters", Map.of())).toString(),
                    JsonbTypeHandler.toJsonNode(item.getOrDefault("files", List.of())).toString(), checksum,
                    JsonbTypeHandler.toJsonNode(item.getOrDefault("coverage", Map.of())).toString());
        }
    }
}
