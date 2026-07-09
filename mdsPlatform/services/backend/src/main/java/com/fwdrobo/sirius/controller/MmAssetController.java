package com.fwdrobo.sirius.controller;

import com.fwdrobo.sirius.dto.PageRes;
import com.fwdrobo.sirius.dto.mm.MmAssetCategoryReq;
import com.fwdrobo.sirius.dto.mm.MmAssetResp;
import com.fwdrobo.sirius.dto.mm.MmAssetTagReq;
import com.fwdrobo.sirius.service.MmAssetService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/mm/datasets/{datasetId}/versions/{versionId}/assets")
@Validated
public class MmAssetController {

    private final MmAssetService mmAssetService;

    public MmAssetController(MmAssetService mmAssetService) {
        this.mmAssetService = mmAssetService;
    }

    @GetMapping
    public PageRes<MmAssetResp> listAssets(@PathVariable UUID datasetId,
                                           @PathVariable UUID versionId,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size,
                                           @RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) String tag,
                                           @RequestParam(required = false) String category) {
        return mmAssetService.listAssets(datasetId, versionId, page, size, keyword, tag, category);
    }

    @GetMapping("/{assetId}")
    public MmAssetResp getAsset(@PathVariable UUID datasetId, @PathVariable UUID versionId, @PathVariable UUID assetId) {
        return mmAssetService.getAsset(datasetId, versionId, assetId);
    }

    @PostMapping("/{assetId}/tags")
    public MmAssetResp replaceTags(@PathVariable UUID datasetId, @PathVariable UUID versionId, @PathVariable UUID assetId, @Valid @RequestBody MmAssetTagReq req) {
        return mmAssetService.replaceTags(datasetId, versionId, assetId, req);
    }

    @PostMapping("/{assetId}/categories")
    public MmAssetResp replaceCategories(@PathVariable UUID datasetId, @PathVariable UUID versionId, @PathVariable UUID assetId, @Valid @RequestBody MmAssetCategoryReq req) {
        return mmAssetService.replaceCategories(datasetId, versionId, assetId, req);
    }
}
