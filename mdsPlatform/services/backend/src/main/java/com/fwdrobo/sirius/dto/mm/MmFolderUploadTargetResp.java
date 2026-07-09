package com.fwdrobo.sirius.dto.mm;

public record MmFolderUploadTargetResp(
        String relativePath,
        String path,
        String objectKey,
        String url,
        String uploadMethod
) {
}
