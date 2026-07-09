package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.dto.PageRes;
import com.fwdrobo.sirius.dto.mm.MmAssetCategoryReq;
import com.fwdrobo.sirius.dto.mm.MmAssetResp;
import com.fwdrobo.sirius.dto.mm.MmAssetTagReq;
import com.fwdrobo.sirius.entity.mm.MmAsset;
import com.fwdrobo.sirius.entity.mm.MmDataset;
import com.fwdrobo.sirius.entity.mm.MmDatasetVersion;
import com.fwdrobo.sirius.mapper.MmAssetMapper;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.SecurityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class MmAssetService {

    private final MmAssetMapper mmAssetMapper;
    private final MmDatasetService mmDatasetService;
    private final MmDatasetVersionService mmDatasetVersionService;
    private final FileService fileService;
    private final int previewExpireSeconds;

    public MmAssetService(MmAssetMapper mmAssetMapper,
                          MmDatasetService mmDatasetService,
                          MmDatasetVersionService mmDatasetVersionService,
                          FileService fileService,
                          @Value("${mm.search.preview-expire-seconds:600}") int previewExpireSeconds) {
        this.mmAssetMapper = mmAssetMapper;
        this.mmDatasetService = mmDatasetService;
        this.mmDatasetVersionService = mmDatasetVersionService;
        this.fileService = fileService;
        this.previewExpireSeconds = previewExpireSeconds;
    }

    public PageRes<MmAssetResp> listAssets(UUID datasetId, UUID versionId, Integer page, Integer size, String keyword, String tag, String category) {
        MmDataset dataset = mmDatasetService.getDatasetOrNotFound(datasetId);
        MmDatasetVersion version = mmDatasetVersionService.getVersionOrNotFound(versionId);
        if (!datasetId.equals(version.getDatasetId())) {
            throw ExceptionUtils.badRequest("dataset 与 version 不匹配");
        }
        int pageOrDefault = page == null ? 1 : page;
        int sizeOrDefault = size == null ? 20 : size;
        int offset = (pageOrDefault - 1) * sizeOrDefault;
        long total = mmAssetMapper.countByDatasetVersionId(versionId, keyword, tag, category);
        int totalPages = total == 0 ? 0 : (int) ((total + sizeOrDefault - 1) / sizeOrDefault);
        List<MmAssetResp> items = new ArrayList<>();
        for (MmAsset asset : mmAssetMapper.selectByDatasetVersionId(versionId, keyword, tag, category, sizeOrDefault, offset)) {
            items.add(toResp(asset, dataset, version));
        }
        return new PageRes<>(total, totalPages, pageOrDefault, sizeOrDefault, items);
    }

    public MmAssetResp getAsset(UUID datasetId, UUID versionId, UUID assetId) {
        MmDataset dataset = mmDatasetService.getDatasetOrNotFound(datasetId);
        MmDatasetVersion version = mmDatasetVersionService.getVersionOrNotFound(versionId);
        MmAsset asset = getAssetOrNotFound(assetId);
        if (!versionId.equals(asset.getDatasetVersionId()) || !datasetId.equals(version.getDatasetId())) {
            throw ExceptionUtils.badRequest("asset 与 dataset/version 不匹配");
        }
        return toResp(asset, dataset, version);
    }

    public MmAsset getAssetOrNotFound(UUID assetId) {
        MmAsset asset = mmAssetMapper.selectById(assetId);
        if (asset == null) {
            throw ExceptionUtils.notFound("asset 不存在: " + assetId);
        }
        return asset;
    }

    @Transactional
    public MmAssetResp replaceTags(UUID datasetId, UUID versionId, UUID assetId, MmAssetTagReq req) {
        MmAsset asset = validateAssetBelongs(datasetId, versionId, assetId);
        UUID spaceId = mmAssetMapper.findSpaceIdByAssetId(assetId);
        mmAssetMapper.deleteTagsByAssetId(assetId);
        UUID currentUser = SecurityUtils.getUserId();
        for (String tagName : req.tagNames()) {
            if (tagName == null || tagName.isBlank()) continue;
            UUID tagId = mmAssetMapper.findOrCreateTag(spaceId, tagName.trim(), currentUser);
            mmAssetMapper.bindTag(assetId, tagId, "MANUAL");
        }
        return getAsset(datasetId, versionId, assetId);
    }

    @Transactional
    public MmAssetResp replaceCategories(UUID datasetId, UUID versionId, UUID assetId, MmAssetCategoryReq req) {
        validateAssetBelongs(datasetId, versionId, assetId);
        mmAssetMapper.deleteCategoriesByAssetId(assetId);
        for (UUID categoryId : req.categoryIds()) {
            mmAssetMapper.bindCategory(assetId, categoryId, "MANUAL");
        }
        return getAsset(datasetId, versionId, assetId);
    }

    public MmAssetResp enrichSearchAsset(UUID datasetId, UUID versionId, UUID assetId) {
        return getAsset(datasetId, versionId, assetId);
    }

    private MmAsset validateAssetBelongs(UUID datasetId, UUID versionId, UUID assetId) {
        MmDatasetVersion version = mmDatasetVersionService.getVersionOrNotFound(versionId);
        if (!datasetId.equals(version.getDatasetId())) {
            throw ExceptionUtils.badRequest("dataset 与 version 不匹配");
        }
        MmAsset asset = getAssetOrNotFound(assetId);
        if (!versionId.equals(asset.getDatasetVersionId())) {
            throw ExceptionUtils.badRequest("asset 与 version 不匹配");
        }
        return asset;
    }

    private MmAssetResp toResp(MmAsset asset, MmDataset dataset, MmDatasetVersion version) {
        String previewUrl = null;
        try {
            previewUrl = fileService.downloadUrl(dataset.getRawRepoId(), version.getRawCommitId(), asset.getLogicalPath(), Duration.ofSeconds(previewExpireSeconds)).getUrl();
        } catch (Exception ignored) {
            // 资产索引可能先于对象同步落库，预览 URL 失败时不影响主体查询
        }
        return new MmAssetResp(asset.getAssetId(), asset.getDatasetVersionId(), asset.getFileId(), asset.getAssetType(), asset.getLogicalPath(), asset.getFileName(), asset.getSourceAssetCode(), asset.getSizeBytes(), asset.getContentType(), asset.getWidth(), asset.getHeight(), asset.getStatus(), asset.getCreatedAt(), asset.getUpdatedAt(), previewUrl, mmAssetMapper.selectCaptions(asset.getAssetId()), mmAssetMapper.selectTags(asset.getAssetId()), mmAssetMapper.selectCategories(asset.getAssetId()));
    }
}
