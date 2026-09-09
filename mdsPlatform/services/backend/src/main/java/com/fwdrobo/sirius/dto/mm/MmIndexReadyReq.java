package com.fwdrobo.sirius.dto.mm;

import java.util.UUID;

public record MmIndexReadyReq(
        UUID indexCommitId,
        String imageIndexPath,
        String textIndexPath,
        String imageMetadataPath,
        String textMetadataPath,
        String unifiedIndexPath,
        String unifiedMetadataPath,
        String representationManifestPath,
        String manifestPath,
        Integer embeddingDim,
        Long imageCount,
        Long textCount,
        Long unifiedCount
) {
}
