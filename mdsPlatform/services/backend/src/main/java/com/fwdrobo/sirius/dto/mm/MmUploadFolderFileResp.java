package com.fwdrobo.sirius.dto.mm;

import java.util.UUID;

public record MmUploadFolderFileResp(
        String relativePath,
        String path,
        UUID fileId,
        long size,
        String contentType
) {
}
