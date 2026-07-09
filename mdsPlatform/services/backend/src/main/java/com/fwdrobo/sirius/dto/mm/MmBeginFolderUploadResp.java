package com.fwdrobo.sirius.dto.mm;

import java.util.List;

public record MmBeginFolderUploadResp(
        String basePath,
        int totalFileCount,
        List<MmFolderUploadTargetResp> files
) {
}
