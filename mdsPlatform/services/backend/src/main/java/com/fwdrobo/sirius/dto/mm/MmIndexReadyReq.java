package com.fwdrobo.sirius.dto.mm;

import java.util.UUID;

public record MmIndexReadyReq(
        UUID indexCommitId,
        String imageIndexPath,
        String textIndexPath,
        String imageMetadataPath,
        String textMetadataPath,
        String manifestPath,
        Integer embeddingDim,
        Long imageCount,
        Long textCount
) {
}
