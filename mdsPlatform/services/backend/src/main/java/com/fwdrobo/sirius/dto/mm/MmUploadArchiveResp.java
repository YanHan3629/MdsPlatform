package com.fwdrobo.sirius.dto.mm;

import java.util.List;

public record MmUploadArchiveResp(
        String basePath,
        String archiveName,
        int totalEntries,
        int uploadedFiles,
        int skippedFiles,
        int failedFiles,
        List<String> skippedItems,
        List<MmUploadArchiveErrorResp> failedItems
) {
}
