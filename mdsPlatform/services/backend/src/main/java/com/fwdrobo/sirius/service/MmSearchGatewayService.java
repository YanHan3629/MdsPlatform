package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.dto.mm.*;
import com.fwdrobo.sirius.entity.mm.MmDatasetVersion;
import com.fwdrobo.sirius.entity.mm.MmIndexVersion;
import com.fwdrobo.sirius.entity.mm.MmSearchLog;
import com.fwdrobo.sirius.handler.JsonbTypeHandler;
import com.fwdrobo.sirius.mapper.MmIndexVersionMapper;
import com.fwdrobo.sirius.mapper.MmSearchLogMapper;
import com.fwdrobo.sirius.port.SearchServiceClient;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class MmSearchGatewayService {

    private final MmDatasetVersionService mmDatasetVersionService;
    private final MmIndexVersionMapper mmIndexVersionMapper;
    private final SearchServiceClient searchServiceClient;
    private final MmAssetService mmAssetService;
    private final MmSearchLogMapper mmSearchLogMapper;

    public MmSearchGatewayService(MmDatasetVersionService mmDatasetVersionService,
                                  MmIndexVersionMapper mmIndexVersionMapper,
                                  SearchServiceClient searchServiceClient,
                                  MmAssetService mmAssetService,
                                  MmSearchLogMapper mmSearchLogMapper) {
        this.mmDatasetVersionService = mmDatasetVersionService;
        this.mmIndexVersionMapper = mmIndexVersionMapper;
        this.searchServiceClient = searchServiceClient;
        this.mmAssetService = mmAssetService;
        this.mmSearchLogMapper = mmSearchLogMapper;
    }

    public MmTextToImageResp textToImage(MmTextToImageReq req) {
        MmDatasetVersion version = mmDatasetVersionService.getVersionOrNotFound(req.versionId());
        if (!req.datasetId().equals(version.getDatasetId())) {
            throw ExceptionUtils.badRequest("dataset and version mismatch");
        }
        MmIndexVersion indexVersion = loadActiveIndex(version);
        Instant start = Instant.now();
        InternalTextToImageResp internal = searchServiceClient.textToImage(new InternalTextToImageReq(req.datasetId(), req.versionId(), indexVersion.getIndexVersionId(), req.query(), req.topKOrDefault()));
        int latency = (int) Duration.between(start, Instant.now()).toMillis();
        List<MmTextToImageResp.Item> items = new ArrayList<>();
        if (internal != null && internal.items() != null) {
            for (InternalTextToImageResp.Item item : internal.items()) {
                MmAssetResp asset = tryEnrichSearchAsset(req.datasetId(), req.versionId(), item.assetId());
                if (asset == null) {
                    continue;
                }
                items.add(new MmTextToImageResp.Item(asset.assetId(), item.score() == null ? 0d : item.score(), asset.logicalPath(), asset.previewUrl(), asset.captions(), asset.tags(), asset.categories()));
            }
        }
        logSearch(req.datasetId(), req.versionId(), indexVersion.getIndexVersionId(), "TEXT_TO_IMAGE", req.query(), null, req.topKOrDefault(), items.size(), latency);
        return new MmTextToImageResp(req.datasetId(), req.versionId(), indexVersion.getIndexVersionId(), items);
    }

    public MmImageToTextResp imageToText(UUID datasetId, UUID versionId, Integer topK, MultipartFile file) {
        MmDatasetVersion version = mmDatasetVersionService.getVersionOrNotFound(versionId);
        if (!datasetId.equals(version.getDatasetId())) {
            throw ExceptionUtils.badRequest("dataset and version mismatch");
        }
        MmIndexVersion indexVersion = loadActiveIndex(version);
        Instant start = Instant.now();
        InternalImageToTextResp internal = searchServiceClient.imageToText(datasetId, versionId, indexVersion.getIndexVersionId(), topK == null ? 5 : topK, file);
        int latency = (int) Duration.between(start, Instant.now()).toMillis();
        List<MmImageToTextResp.Item> items = new ArrayList<>();
        if (internal != null && internal.items() != null) {
            for (InternalImageToTextResp.Item item : internal.items()) {
                MmAssetResp asset = tryEnrichSearchAsset(datasetId, versionId, item.assetId());
                if (asset == null) {
                    continue;
                }
                items.add(new MmImageToTextResp.Item(asset.assetId(), item.score() == null ? 0d : item.score(), item.text(), asset.logicalPath(), asset.previewUrl()));
            }
        }
        logSearch(datasetId, versionId, indexVersion.getIndexVersionId(), "IMAGE_TO_TEXT", null, null, topK == null ? 5 : topK, items.size(), latency);
        return new MmImageToTextResp(datasetId, versionId, indexVersion.getIndexVersionId(), items);
    }

    private MmIndexVersion loadActiveIndex(MmDatasetVersion version) {
        if (version.getActiveIndexVersionId() == null) {
            throw ExceptionUtils.badRequest("no active index for current version");
        }
        MmIndexVersion indexVersion = mmIndexVersionMapper.selectById(version.getActiveIndexVersionId());
        if (indexVersion == null || !"READY".equals(indexVersion.getIndexStatus())) {
            throw ExceptionUtils.badRequest("current index is not ready");
        }
        return indexVersion;
    }

    private MmAssetResp tryEnrichSearchAsset(UUID datasetId, UUID versionId, UUID assetId) {
        try {
            return mmAssetService.enrichSearchAsset(datasetId, versionId, assetId);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.warn("Skip missing search asset. datasetId={}, versionId={}, assetId={}", datasetId, versionId, assetId);
                return null;
            }
            throw e;
        }
    }

    private void logSearch(UUID datasetId, UUID versionId, UUID indexVersionId, String queryType, String queryText, UUID queryAssetId, int topK, int resultCount, int latencyMs) {
        MmSearchLog log = new MmSearchLog();
        log.setSearchLogId(UUID.randomUUID());
        log.setDatasetId(datasetId);
        log.setDatasetVersionId(versionId);
        log.setIndexVersionId(indexVersionId);
        log.setQueryType(queryType);
        log.setQueryText(queryText);
        log.setQueryAssetId(queryAssetId);
        log.setTopK(topK);
        log.setResultCount(resultCount);
        log.setLatencyMs(latencyMs);
        log.setRequestUserId(SecurityUtils.getUserId());
        log.setMeta(JsonbTypeHandler.toJsonNode(new HashMap<>()));
        mmSearchLogMapper.insert(log);
    }
}


