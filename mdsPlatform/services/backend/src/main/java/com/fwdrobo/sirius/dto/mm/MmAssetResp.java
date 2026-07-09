package com.fwdrobo.sirius.dto.mm;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record MmAssetResp(
        UUID assetId,
        UUID datasetVersionId,
        UUID fileId,
        String assetType,
        String logicalPath,
        String fileName,
        String sourceAssetCode,
        Long sizeBytes,
        String contentType,
        Integer width,
        Integer height,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String previewUrl,
        List<String> captions,
        List<String> tags,
        List<String> categories
) {
}
