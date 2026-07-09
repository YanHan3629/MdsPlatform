package com.fwdrobo.sirius.dto.mm;

import jakarta.validation.constraints.NotBlank;

public record MmUploadSessionFileResultReq(
        @NotBlank String relativePath,
        boolean success,
        String errorMessage
) {
}
