package com.fwdrobo.sirius.dto.mm;

import java.util.List;

public record MmUploadFolderResp(
        String basePath,
        int totalFileCount,
        List<MmUploadFolderFileResp> files
) {
}
