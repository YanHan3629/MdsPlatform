package com.fwdrobo.sirius.dto.mm;

import jakarta.validation.constraints.NotBlank;

public record MmBuildIndexReq(
        @NotBlank String modelName,
        @NotBlank String indexType,
        Boolean rebuild
) {
    public boolean rebuildOrDefault() {
        return Boolean.TRUE.equals(rebuild);
    }
}
