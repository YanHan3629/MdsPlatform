package com.fwdrobo.sirius.controller;

import com.fwdrobo.sirius.dto.mm.MmImageToTextResp;
import com.fwdrobo.sirius.dto.mm.MmTextToImageReq;
import com.fwdrobo.sirius.dto.mm.MmTextToImageResp;
import com.fwdrobo.sirius.service.MmSearchGatewayService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/mm/search")
@Validated
public class MmSearchController {

    private final MmSearchGatewayService mmSearchGatewayService;

    public MmSearchController(MmSearchGatewayService mmSearchGatewayService) {
        this.mmSearchGatewayService = mmSearchGatewayService;
    }

    @PostMapping("/text-to-image")
    public MmTextToImageResp textToImage(@Valid @RequestBody MmTextToImageReq req) {
        return mmSearchGatewayService.textToImage(req);
    }

    @PostMapping(value = "/image-to-text", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MmImageToTextResp imageToText(@RequestParam UUID datasetId,
                                         @RequestParam UUID versionId,
                                         @RequestParam(defaultValue = "5") Integer topK,
                                         @RequestPart("file") MultipartFile file) {
        return mmSearchGatewayService.imageToText(datasetId, versionId, topK, file);
    }
}
