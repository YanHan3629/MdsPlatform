package com.fwdrobo.sirius.dto.mm;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MmDatasetVersionResp(
        UUID versionId,
        UUID datasetId,
        String versionName,
        UUID rawCommitId,
        String versionStatus,
        Long sampleCount,
        Long imageCount,
        Long textCount,
        UUID activeIndexVersionId,
        String comment,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime publishedAt
) {
}
