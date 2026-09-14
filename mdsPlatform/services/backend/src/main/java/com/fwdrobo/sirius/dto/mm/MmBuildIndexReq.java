package com.fwdrobo.sirius.dto.mm;

import jakarta.validation.constraints.NotBlank;

public record MmBuildIndexReq(
        @NotBlank String modelName,
        @NotBlank String indexType,
        Boolean rebuild,
        java.util.Map<String,Object> indexConfig
) {
    public MmBuildIndexReq(String modelName, String indexType, Boolean rebuild) { this(modelName,indexType,rebuild,null); }
    public boolean rebuildOrDefault() {
        return Boolean.TRUE.equals(rebuild);
    }
}
