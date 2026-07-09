package com.fwdrobo.sirius.dto.mm;

import java.util.List;

public record MmUploadSessionBatchBeginResp(
        MmUploadSessionSummaryResp session,
        int batchSize,
        List<MmFolderUploadTargetResp> files
) {
}
