package com.fwdrobo.sirius.dto.mm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MmDatasetReq(
        @NotBlank @Size(max = 128) String datasetName,
        @NotBlank @Size(max = 32) String datasetType,
        @NotBlank @Size(max = 32) String modalityType,
        @Size(max = 500) String description
) {
}
