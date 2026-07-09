package com.fwdrobo.sirius.dto.mm;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record MmTextToImageReq(
        @NotNull UUID datasetId,
        @NotNull UUID versionId,
        @NotBlank String query,
        @Min(1) Integer topK
) {
    public int topKOrDefault() {
        return topK == null ? 5 : topK;
    }
}
