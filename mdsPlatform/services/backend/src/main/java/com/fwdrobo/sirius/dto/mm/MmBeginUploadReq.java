package com.fwdrobo.sirius.dto.mm;

import com.fwdrobo.sirius.validation.ValidLogicalPath;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record MmBeginUploadReq(
        @NotBlank @ValidLogicalPath String path,
        String contentType,
        @Min(1) @Max(43200) Integer expireSeconds
) {
    public int expireSecondsOrDefault() {
        return expireSeconds == null ? 3600 : expireSeconds;
    }
}
