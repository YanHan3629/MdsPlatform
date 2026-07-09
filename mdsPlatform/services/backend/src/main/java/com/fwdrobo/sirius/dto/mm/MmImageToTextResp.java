package com.fwdrobo.sirius.dto.mm;

import java.util.List;
import java.util.UUID;

public record MmImageToTextResp(
        UUID datasetId,
        UUID versionId,
        UUID indexVersionId,
        List<Item> items
) {
    public record Item(
            UUID assetId,
            double score,
            String text,
            String matchedImagePath,
            String previewUrl
    ) {
    }
}
