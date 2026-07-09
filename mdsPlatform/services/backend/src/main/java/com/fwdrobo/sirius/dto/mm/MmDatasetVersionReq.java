package com.fwdrobo.sirius.dto.mm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MmDatasetVersionReq(
        @NotBlank @Size(max = 64) String versionName,
        @Size(max = 500) String comment
) {
}
