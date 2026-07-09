package com.fwdrobo.sirius.dto.mm;

import java.util.List;
import java.util.UUID;

public record MmTextToImageResp(
        UUID datasetId,
        UUID versionId,
        UUID indexVersionId,
        List<Item> items
) {
    public record Item(
            UUID assetId,
            double score,
            String logicalPath,
            String previewUrl,
            List<String> captions,
            List<String> tags,
            List<String> categories
    ) {
    }
}
