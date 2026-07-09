package com.fwdrobo.sirius.dto.mm;

import jakarta.validation.constraints.NotBlank;

public record MmFolderFileItemReq(
        @NotBlank String relativePath,
        String contentType
) {
}
