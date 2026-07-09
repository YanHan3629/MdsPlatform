package com.fwdrobo.sirius.dto.mm;

import com.fwdrobo.sirius.validation.ValidLogicalPath;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record MmCreateUploadSessionReq(
        @ValidLogicalPath String basePath,
        @Min(60) @Max(86400) Integer expireSeconds,
        @Min(1) Integer expectedFileCount
) {
    public String basePathOrDefault() {
        return (basePath == null || basePath.isBlank()) ? "/images" : basePath;
    }

    public int expireSecondsOrDefault() {
        return expireSeconds == null ? 3600 : expireSeconds;
    }
}
