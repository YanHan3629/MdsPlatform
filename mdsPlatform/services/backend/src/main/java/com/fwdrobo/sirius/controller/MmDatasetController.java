package com.fwdrobo.sirius.controller;

import com.fwdrobo.sirius.dto.PageRes;
import com.fwdrobo.sirius.dto.mm.MmDatasetReq;
import com.fwdrobo.sirius.dto.mm.MmDatasetResp;
import com.fwdrobo.sirius.dto.mm.MmDatasetVersionReq;
import com.fwdrobo.sirius.dto.mm.MmDatasetVersionResp;
import com.fwdrobo.sirius.service.MmDatasetService;
import com.fwdrobo.sirius.service.MmDatasetVersionService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/mm")
@Validated
public class MmDatasetController {

    private final MmDatasetService mmDatasetService;
    private final MmDatasetVersionService mmDatasetVersionService;

    public MmDatasetController(MmDatasetService mmDatasetService,
                               MmDatasetVersionService mmDatasetVersionService) {
        this.mmDatasetService = mmDatasetService;
        this.mmDatasetVersionService = mmDatasetVersionService;
    }

    @PostMapping("/spaces/{spaceId}/datasets")
    public MmDatasetResp createDataset(@PathVariable UUID spaceId, @Valid @RequestBody MmDatasetReq req) {
        return mmDatasetService.createDataset(spaceId, req);
    }

    @GetMapping("/spaces/{spaceId}/datasets")
    public PageRes<MmDatasetResp> listDatasets(@PathVariable UUID spaceId,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        return mmDatasetService.listBySpace(spaceId, page, size);
    }

    @GetMapping("/datasets/{datasetId}")
    public MmDatasetResp getDataset(@PathVariable UUID datasetId) {
        return mmDatasetService.getById(datasetId);
    }

    @PostMapping("/datasets/{datasetId}/versions")
    public MmDatasetVersionResp createVersion(@PathVariable UUID datasetId, @Valid @RequestBody MmDatasetVersionReq req) {
        return mmDatasetVersionService.createVersion(datasetId, req);
    }

    @GetMapping("/datasets/{datasetId}/versions")
    public PageRes<MmDatasetVersionResp> listVersions(@PathVariable UUID datasetId,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size) {
        return mmDatasetVersionService.listByDataset(datasetId, page, size);
    }

    @GetMapping("/datasets/{datasetId}/versions/{versionId}")
    public MmDatasetVersionResp getVersion(@PathVariable UUID datasetId, @PathVariable UUID versionId) {
        return mmDatasetVersionService.getById(datasetId, versionId);
    }

    @PostMapping("/datasets/{datasetId}/versions/{versionId}/publish")
    public MmDatasetVersionResp publishVersion(@PathVariable UUID datasetId, @PathVariable UUID versionId) {
        return mmDatasetVersionService.publishVersion(datasetId, versionId);
    }
}
