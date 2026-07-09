package com.fwdrobo.sirius.dto.mm;

import java.util.UUID;

public record MmResolvedIndexBundleResp(
        UUID datasetId,
        UUID versionId,
        UUID indexVersionId,
        UUID indexRepoId,
        UUID indexCommitId,
        String manifestPath,
        String imageIndexPath,
        String textIndexPath,
        String imageMetadataPath,
        String textMetadataPath,
        String manifestUrl,
        String imageIndexUrl,
        String textIndexUrl,
        String imageMetadataUrl,
        String textMetadataUrl,
        String modelName,
        String indexType
) {
}
