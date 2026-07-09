package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.dto.PageRes;
import com.fwdrobo.sirius.dto.artifact.ArtifactRepoReq;
import com.fwdrobo.sirius.dto.artifact.ArtifactRepoResponse;
import com.fwdrobo.sirius.dto.mm.MmDatasetReq;
import com.fwdrobo.sirius.dto.mm.MmDatasetResp;
import com.fwdrobo.sirius.entity.artifact.ArtifactVisibility;
import com.fwdrobo.sirius.entity.mm.MmDataset;
import com.fwdrobo.sirius.handler.JsonbTypeHandler;
import com.fwdrobo.sirius.mapper.MmDatasetMapper;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MmDatasetService {

    private final MmDatasetMapper mmDatasetMapper;
    private final MmSpaceService mmSpaceService;
    private final ArtifactRepoService artifactRepoService;

    public MmDatasetService(MmDatasetMapper mmDatasetMapper,
                            MmSpaceService mmSpaceService,
                            ArtifactRepoService artifactRepoService) {
        this.mmDatasetMapper = mmDatasetMapper;
        this.mmSpaceService = mmSpaceService;
        this.artifactRepoService = artifactRepoService;
    }

    @Transactional
    public MmDatasetResp createDataset(UUID spaceId, MmDatasetReq req) {
        mmSpaceService.getSpaceOrNotFound(spaceId);
        MmDataset existed = mmDatasetMapper.selectBySpaceIdAndName(spaceId, req.datasetName().trim());
        if (existed != null) {
            throw ExceptionUtils.conflict("dataset 已存在: " + req.datasetName());
        }
        ArtifactRepoReq rawReq = new ArtifactRepoReq();
        rawReq.setRepoName("mm-raw-" + req.datasetName().trim() + "-" + UUID.randomUUID());
        rawReq.setRepoType("MM_RAW");
        rawReq.setVisibility(ArtifactVisibility.PRIVATE);
        rawReq.setDescription("multimodal raw repo for dataset " + req.datasetName().trim());
        ArtifactRepoResponse rawRepo = artifactRepoService.create(rawReq);

        ArtifactRepoReq indexReq = new ArtifactRepoReq();
        indexReq.setRepoName("mm-index-" + req.datasetName().trim() + "-" + UUID.randomUUID());
        indexReq.setRepoType("MM_INDEX");
        indexReq.setVisibility(ArtifactVisibility.PRIVATE);
        indexReq.setDescription("multimodal index repo for dataset " + req.datasetName().trim());
        ArtifactRepoResponse indexRepo = artifactRepoService.create(indexReq);

        MmDataset dataset = new MmDataset();
        dataset.setDatasetId(UUID.randomUUID());
        dataset.setSpaceId(spaceId);
        dataset.setDatasetName(req.datasetName().trim());
        dataset.setDatasetType(req.datasetType().trim());
        dataset.setModalityType(req.modalityType().trim());
        dataset.setDescription(req.description());
        dataset.setRawRepoId(rawRepo.getRepoId());
        dataset.setIndexRepoId(indexRepo.getRepoId());
        dataset.setOwnerUserId(SecurityUtils.getUserId());
        dataset.setOrgId(SecurityUtils.getUserOrgId());
        dataset.setStatus("ACTIVE");
        dataset.setMeta(JsonbTypeHandler.toJsonNode(Map.of()));
        mmDatasetMapper.insert(dataset);
        return toResp(dataset);
    }

    public MmDatasetResp getById(UUID datasetId) {
        return toResp(getDatasetOrNotFound(datasetId));
    }

    public MmDataset getDatasetOrNotFound(UUID datasetId) {
        MmDataset dataset = mmDatasetMapper.selectById(datasetId);
        if (dataset == null) {
            throw ExceptionUtils.notFound("dataset 不存在: " + datasetId);
        }
        UUID currentOrgId = SecurityUtils.getUserOrgId();
        if (currentOrgId != null && !currentOrgId.equals(dataset.getOrgId())) {
            throw ExceptionUtils.forbidden("无权访问该 dataset");
        }
        return dataset;
    }

    public PageRes<MmDatasetResp> listBySpace(UUID spaceId, Integer page, Integer size) {
        mmSpaceService.getSpaceOrNotFound(spaceId);
        int pageOrDefault = page == null ? 1 : page;
        int sizeOrDefault = size == null ? 20 : size;
        int offset = (pageOrDefault - 1) * sizeOrDefault;
        long total = mmDatasetMapper.countBySpaceId(spaceId);
        int totalPages = total == 0 ? 0 : (int) ((total + sizeOrDefault - 1) / sizeOrDefault);
        List<MmDatasetResp> items = mmDatasetMapper.selectBySpaceId(spaceId, sizeOrDefault, offset).stream().map(this::toResp).toList();
        return new PageRes<>(total, totalPages, pageOrDefault, sizeOrDefault, items);
    }

    private MmDatasetResp toResp(MmDataset dataset) {
        return new MmDatasetResp(dataset.getDatasetId(), dataset.getSpaceId(), dataset.getDatasetName(), dataset.getDatasetType(), dataset.getModalityType(), dataset.getDescription(), dataset.getRawRepoId(), dataset.getIndexRepoId(), dataset.getStatus(), dataset.getCreatedAt(), dataset.getUpdatedAt());
    }
}
