package com.fwdrobo.sirius.dto.mm;

import jakarta.validation.constraints.NotBlank;

public record MmBuildIndexReq(
        @NotBlank String modelName,
        @NotBlank String indexType,
        Boolean rebuild,
        java.util.Map<String,Object> indexConfig,
        Boolean activateOnReady
) {
    public MmBuildIndexReq(String modelName,String indexType,Boolean rebuild,java.util.Map<String,Object> indexConfig){this(modelName,indexType,rebuild,indexConfig,null);}
    public MmBuildIndexReq(String modelName, String indexType, Boolean rebuild) { this(modelName,indexType,rebuild,null,null); }
    public boolean activateOnReadyOrDefault(){return !Boolean.FALSE.equals(activateOnReady);}
    public boolean rebuildOrDefault() {
        return Boolean.TRUE.equals(rebuild);
    }
}
