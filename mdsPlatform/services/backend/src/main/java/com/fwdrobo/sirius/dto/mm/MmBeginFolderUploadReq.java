package com.fwdrobo.sirius.dto.mm;

import com.fwdrobo.sirius.validation.ValidLogicalPath;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record MmBeginFolderUploadReq(
        @ValidLogicalPath String basePath,
        @Min(1) @Max(43200) Integer expireSeconds,
        @NotEmpty List<@Valid MmFolderFileItemReq> files
) {
    public int expireSecondsOrDefault() {
        return expireSeconds == null ? 3600 : expireSeconds;
    }
}
