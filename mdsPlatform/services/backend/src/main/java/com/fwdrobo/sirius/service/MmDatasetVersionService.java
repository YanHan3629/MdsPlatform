package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.dto.PageRes;
import com.fwdrobo.sirius.dto.artifact.ArtifactCommitResponse;
import com.fwdrobo.sirius.dto.artifact.CreateCommitReq;
import com.fwdrobo.sirius.dto.file.FileResp;
import com.fwdrobo.sirius.dto.mm.MmDatasetVersionReq;
import com.fwdrobo.sirius.dto.mm.MmDatasetVersionResp;
import com.fwdrobo.sirius.entity.mm.MmDataset;
import com.fwdrobo.sirius.entity.mm.MmDatasetVersion;
import com.fwdrobo.sirius.handler.JsonbTypeHandler;
import com.fwdrobo.sirius.mapper.MmDatasetVersionMapper;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MmDatasetVersionService {

    private final MmDatasetService mmDatasetService;
    private final MmDatasetVersionMapper mmDatasetVersionMapper;
    private final ArtifactCommitService artifactCommitService;
    private final MmIndexOutboxService indexOutboxService;
    private final JdbcTemplate jdbc;
    private final FileService fileService;
    private final DatasetAccessService access;

    public MmDatasetVersionService(MmDatasetService mmDatasetService,
                                   MmDatasetVersionMapper mmDatasetVersionMapper,
                                   ArtifactCommitService artifactCommitService,
                                   MmIndexOutboxService indexOutboxService,
                                   JdbcTemplate jdbc,
                                   FileService fileService, DatasetAccessService access) {
        this.mmDatasetService = mmDatasetService;
        this.mmDatasetVersionMapper = mmDatasetVersionMapper;
        this.artifactCommitService = artifactCommitService;
        this.indexOutboxService = indexOutboxService;
        this.jdbc = jdbc;
        this.fileService = fileService;
        this.access=access;
    }

    @Transactional
    public MmDatasetVersionResp createVersion(UUID datasetId, MmDatasetVersionReq req) {
        MmDataset dataset = mmDatasetService.getDatasetOrNotFound(datasetId);
        MmDatasetVersion existed = mmDatasetVersionMapper.selectByDatasetIdAndVersionName(datasetId, req.versionName().trim());
        if (existed != null) {
            throw ExceptionUtils.conflict("dataset version 已存在: " + req.versionName());
        }

        CreateCommitReq createCommitReq = new CreateCommitReq();
        createCommitReq.setCommitSource("MM_UPLOAD");
        createCommitReq.setComment(req.comment());
        ArtifactCommitResponse commitResp = artifactCommitService.createCommit(dataset.getRawRepoId(), createCommitReq);

        MmDatasetVersion version = new MmDatasetVersion();
        version.setVersionId(UUID.randomUUID());
        version.setDatasetId(datasetId);
        version.setVersionName(req.versionName().trim());
        version.setRawCommitId(commitResp.getCommitId());
        version.setVersionStatus("DRAFT");
        version.setSampleCount(0L);
        version.setImageCount(0L);
        version.setTextCount(0L);
        version.setComment(req.comment());
        version.setCreatedBy(SecurityUtils.getUserId());
        version.setMeta(JsonbTypeHandler.toJsonNode(Map.of()));
        mmDatasetVersionMapper.insert(version);
        return toResp(version);
    }

    @Transactional
    public MmDatasetVersionResp publishVersion(UUID datasetId, UUID versionId) {
        return publishVersion(datasetId,versionId,true);
    }

    @Transactional
    public MmDatasetVersionResp publishVersion(UUID datasetId, UUID versionId,boolean activateOnReady) {
        MmDataset dataset = mmDatasetService.getDatasetOrNotFound(datasetId);
        MmDatasetVersion version = getVersionOrNotFound(versionId);
        if (!datasetId.equals(version.getDatasetId())) {
            throw ExceptionUtils.badRequest("dataset 与 version 不匹配");
        }

        String publishComment = version.getComment() == null || version.getComment().isBlank()
                ? "发布数据版本 " + version.getVersionName() : version.getComment();
        verifyRawSnapshotReady(dataset.getRawRepoId(), version.getRawCommitId());
        // Keep the commit-first lock order used by upload verification, then
        // freeze the activation policy before the durable automatic task exists.
        jdbc.queryForObject("SELECT version_id FROM mm_dataset_version WHERE version_id=? AND dataset_id=? FOR UPDATE",UUID.class,versionId,datasetId);
        version=getVersionOrNotFound(versionId);
        if(version.getMeta()!=null&&!version.getMeta().isObject())throw ExceptionUtils.conflict("版本配置必须为 JSON 对象");
        var metadata=(com.fasterxml.jackson.databind.node.ObjectNode)(version.getMeta()==null?JsonbTypeHandler.toJsonNode(Map.of()):version.getMeta().deepCopy());
        metadata.put("activateOnReady",activateOnReady);version.setMeta(metadata);
        artifactCommitService.publishCommit(dataset.getRawRepoId(), version.getRawCommitId(), publishComment);
        version.setVersionStatus("RAW_READY");
        version.setPublishedAt(OffsetDateTime.now());
        version.setPublishedBy(SecurityUtils.getUserId());
        mmDatasetVersionMapper.update(version);
        indexOutboxService.enqueueSnapshot(dataset.getOrgId(), datasetId, versionId,
                version.getRawCommitId(), SecurityUtils.getUserId());
        return toResp(version);
    }

    /** Do not enqueue a build for an empty, in-flight or unverified upload. */
    void verifyRawSnapshotReady(UUID repoId, UUID commitId) {
        // Pair this commit-row lock with the artifact_file trigger's FOR SHARE:
        // no concurrent upload can slip in between verification and publish.
        String commitStatus = jdbc.queryForObject("""
                SELECT commit_status FROM public.artifact_commit
                WHERE repo_id=? AND commit_id=? FOR UPDATE
                """, String.class, repoId, commitId);
        if (!"DRAFT".equals(commitStatus)) {
            throw ExceptionUtils.conflict("仅 DRAFT 数据版本可完成上传并发布");
        }
        List<Map<String, Object>> files = jdbc.queryForList("""
                SELECT logical_path,upload_state,sha256 FROM public.artifact_file
                WHERE commit_id=? ORDER BY logical_path FOR UPDATE
                """, commitId);
        if (files.isEmpty()) {
            throw ExceptionUtils.conflict("数据版本尚无已上传文件，不能发布或创建入库任务");
        }
        for (Map<String, Object> file : files) {
            String path = String.valueOf(file.get("logical_path"));
            if(com.fwdrobo.sirius.util.DataSpaceBusinessFilePolicy.isDescription(path))throw ExceptionUtils.conflict("说明文件不属于业务资产，请在发布前从草稿移除："+path);
            if (!"READY".equals(file.get("upload_state"))) {
                throw ExceptionUtils.conflict("文件尚未完成上传与校验: " + path);
            }
            String sha256 = file.get("sha256") == null ? "" : String.valueOf(file.get("sha256")).trim();
            if (!sha256.matches("[a-fA-F0-9]{64}")) {
                try {
                    FileResp verified = fileService.ensureContentIdentity(repoId, commitId, path);
                    String verifiedSha = verified == null ? "" : verified.getSha256();
                    if (verified == null || !"READY".equals(verified.getUploadState())
                            || verifiedSha == null || !verifiedSha.matches("[a-fA-F0-9]{64}")) {
                        throw ExceptionUtils.conflict("文件内容校验未完成: " + path);
                    }
                } catch (Exception exception) {
                    throw ExceptionUtils.conflict("文件内容尚未就绪，不能创建入库任务: " + path);
                }
            }
        }
    }

    public MmDatasetVersion getVersionOrNotFound(UUID versionId) {
        MmDatasetVersion version = mmDatasetVersionMapper.selectById(versionId);
        if (version == null) {
            throw ExceptionUtils.notFound("dataset version 不存在: " + versionId);
        }
        return version;
    }

    public MmDatasetVersionResp getById(UUID datasetId, UUID versionId) {
        access.requireVersionRead(datasetId,versionId,null);
        MmDatasetVersion version = getVersionOrNotFound(versionId);
        if (!datasetId.equals(version.getDatasetId())) {
            throw ExceptionUtils.badRequest("dataset 与 version 不匹配");
        }
        return toResp(version);
    }

    public PageRes<MmDatasetVersionResp> listByDataset(UUID datasetId, Integer page, Integer size) {
        List<UUID> allowed=access.readableVersionIds(datasetId);
        int pageOrDefault = page == null ? 1 : page;
        int sizeOrDefault = size == null ? 20 : size;
        if (pageOrDefault<1 || sizeOrDefault<1 || sizeOrDefault>1000) throw ExceptionUtils.badRequest("分页范围无效");
        int offset = (pageOrDefault - 1) * sizeOrDefault;
        long total = allowed==null?mmDatasetVersionMapper.countByDatasetId(datasetId):allowed.size();
        int totalPages = total == 0 ? 0 : (int) ((total + sizeOrDefault - 1) / sizeOrDefault);
        List<MmDatasetVersionResp> items = allowed==null?mmDatasetVersionMapper.selectByDatasetId(datasetId, sizeOrDefault, offset)
                .stream().map(this::toResp).toList():allowed.stream().map(mmDatasetVersionMapper::selectById)
                .sorted(java.util.Comparator.comparing(MmDatasetVersion::getCreatedAt).reversed()).skip(offset).limit(sizeOrDefault).map(this::toResp).toList();
        return new PageRes<>(total, totalPages, pageOrDefault, sizeOrDefault, items);
    }

    private MmDatasetVersionResp toResp(MmDatasetVersion version) {
        return new MmDatasetVersionResp(
                version.getVersionId(),
                version.getDatasetId(),
                version.getVersionName(),
                version.getRawCommitId(),
                version.getVersionStatus(),
                version.getSampleCount(),
                version.getImageCount(),
                version.getTextCount(),
                version.getActiveIndexVersionId(),
                version.getComment(),
                version.getCreatedAt(),
                version.getUpdatedAt(),
                version.getPublishedAt()
        );
    }
}
