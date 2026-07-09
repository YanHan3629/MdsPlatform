package com.fwdrobo.sirius.controller;

import com.fwdrobo.sirius.dto.PageRes;
import com.fwdrobo.sirius.dto.mm.MmSpaceReq;
import com.fwdrobo.sirius.dto.mm.MmSpaceResp;
import com.fwdrobo.sirius.service.MmSpaceService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/mm/spaces")
@Validated
public class MmSpaceController {

    private final MmSpaceService mmSpaceService;

    public MmSpaceController(MmSpaceService mmSpaceService) {
        this.mmSpaceService = mmSpaceService;
    }

    @PostMapping
    public MmSpaceResp create(@Valid @RequestBody MmSpaceReq req) {
        return mmSpaceService.create(req);
    }

    @GetMapping
    public PageRes<MmSpaceResp> list(@RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        return mmSpaceService.list(page, size);
    }

    @GetMapping("/{spaceId}")
    public MmSpaceResp detail(@PathVariable UUID spaceId) {
        return mmSpaceService.getById(spaceId);
    }
}
