package com.fwdrobo.sirius.dto.mm;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record MmUploadSessionBatchBeginReq(
        @NotEmpty List<@Valid MmFolderFileItemReq> files
) {
}
