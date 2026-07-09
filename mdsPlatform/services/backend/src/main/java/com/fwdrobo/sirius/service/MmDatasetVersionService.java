package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.dto.PageRes;
import com.fwdrobo.sirius.dto.artifact.ArtifactCommitResponse;
import com.fwdrobo.sirius.dto.artifact.CreateCommitReq;
import com.fwdrobo.sirius.dto.mm.MmDatasetVersionReq;
import com.fwdrobo.sirius.dto.mm.MmDatasetVersionResp;
import com.fwdrobo.sirius.entity.mm.MmDataset;
import com.fwdrobo.sirius.entity.mm.MmDatasetVersion;
import com.fwdrobo.sirius.handler.JsonbTypeHandler;
import com.fwdrobo.sirius.mapper.MmDatasetVersionMapper;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.SecurityUtils;
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

    public MmDatasetVersionService(MmDatasetService mmDatasetService,
                                   MmDatasetVersionMapper mmDatasetVersionMapper,
                                   ArtifactCommitService artifactCommitService) {
        this.mmDatasetService = mmDatasetService;
        this.mmDatasetVersionMapper = mmDatasetVersionMapper;
        this.artifactCommitService = artifactCommitService;
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
        MmDataset dataset = mmDatasetService.getDatasetOrNotFound(datasetId);
        MmDatasetVersion version = getVersionOrNotFound(versionId);
        if (!datasetId.equals(version.getDatasetId())) {
            throw ExceptionUtils.badRequest("dataset 与 version 不匹配");
        }

        artifactCommitService.publishCommit(dataset.getRawRepoId(), version.getRawCommitId(), version.getComment());
        version.setVersionStatus("RAW_READY");
        version.setPublishedAt(OffsetDateTime.now());
        version.setPublishedBy(SecurityUtils.getUserId());
        mmDatasetVersionMapper.update(version);
        return toResp(version);
    }

    public MmDatasetVersion getVersionOrNotFound(UUID versionId) {
        MmDatasetVersion version = mmDatasetVersionMapper.selectById(versionId);
        if (version == null) {
            throw ExceptionUtils.notFound("dataset version 不存在: " + versionId);
        }
        return version;
    }

    public MmDatasetVersionResp getById(UUID datasetId, UUID versionId) {
        MmDatasetVersion version = getVersionOrNotFound(versionId);
        if (!datasetId.equals(version.getDatasetId())) {
            throw ExceptionUtils.badRequest("dataset 与 version 不匹配");
        }
        return toResp(version);
    }

    public PageRes<MmDatasetVersionResp> listByDataset(UUID datasetId, Integer page, Integer size) {
        int pageOrDefault = page == null ? 1 : page;
        int sizeOrDefault = size == null ? 20 : size;
        int offset = (pageOrDefault - 1) * sizeOrDefault;
        long total = mmDatasetVersionMapper.countByDatasetId(datasetId);
        int totalPages = total == 0 ? 0 : (int) ((total + sizeOrDefault - 1) / sizeOrDefault);
        List<MmDatasetVersionResp> items = mmDatasetVersionMapper.selectByDatasetId(datasetId, sizeOrDefault, offset)
                .stream().map(this::toResp).toList();
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
