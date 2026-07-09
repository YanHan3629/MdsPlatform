package com.fwdrobo.sirius.dto.mm;

public record MmBeginUploadResp(
        String path,
        String objectKey,
        String url,
        String uploadMethod
) {
}
