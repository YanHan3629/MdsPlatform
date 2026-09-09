package com.fwdrobo.sirius.controller;

import com.fwdrobo.sirius.dto.mm.*;
import com.fwdrobo.sirius.service.MmIndexService;
import com.fwdrobo.sirius.util.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/mm")
@Validated
public class MmIndexController {

    private final MmIndexService mmIndexService;

    public MmIndexController(MmIndexService mmIndexService) {
        this.mmIndexService = mmIndexService;
    }

    @PostMapping("/datasets/{datasetId}/versions/{versionId}/build-index")
    public Map<String, Object> buildIndex(@PathVariable UUID datasetId,
                                          @PathVariable UUID versionId,
                                          @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                          @Valid @RequestBody MmBuildIndexReq req) throws Exception {
        return mmIndexService.buildIndex(datasetId, versionId, req, SecurityUtils.getToken(authorization));
    }

    @GetMapping("/datasets/{datasetId}/versions/{versionId}/index-status")
    public MmIndexVersionResp getIndexStatus(@PathVariable UUID datasetId, @PathVariable UUID versionId) {
        return mmIndexService.getIndexStatus(datasetId, versionId);
    }

    @GetMapping("/internal/datasets/{datasetId}/versions/{versionId}/indexes/{indexVersionId}/bundle")
    public MmResolvedIndexBundleResp resolveBundle(@PathVariable UUID datasetId,
                                                   @PathVariable UUID versionId,
                                                   @PathVariable UUID indexVersionId) throws Exception {
        return mmIndexService.resolveBundle(datasetId, versionId, indexVersionId);
    }

    @PostMapping("/internal/datasets/{datasetId}/versions/{versionId}/indexes/{indexVersionId}/ready")
    public MmIndexVersionResp markReady(@PathVariable UUID datasetId,
                                        @PathVariable UUID versionId,
                                        @PathVariable UUID indexVersionId,
                                        @RequestBody MmIndexReadyReq req) {
        return mmIndexService.markReady(datasetId, versionId, indexVersionId,
                req.indexCommitId(),
                req.imageIndexPath(),
                req.textIndexPath(),
                req.imageMetadataPath(),
                req.textMetadataPath(),
                req.unifiedIndexPath(),
                req.unifiedMetadataPath(),
                req.representationManifestPath(),
                req.manifestPath(),
                req.embeddingDim(),
                req.imageCount(),
                req.textCount(),
                req.unifiedCount());
    }

    @PostMapping("/internal/datasets/{datasetId}/versions/{versionId}/indexes/{indexVersionId}/failed")
    public MmIndexVersionResp markFailed(@PathVariable UUID datasetId,
                                         @PathVariable UUID versionId,
                                         @PathVariable UUID indexVersionId,
                                         @RequestBody MmIndexFailedReq req) {
        return mmIndexService.markFailed(datasetId, versionId, indexVersionId, req.errorMessage() == null ? "unknown error" : req.errorMessage());
    }
}
