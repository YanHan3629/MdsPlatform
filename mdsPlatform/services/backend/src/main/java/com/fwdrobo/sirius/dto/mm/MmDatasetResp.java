package com.fwdrobo.sirius.dto.mm;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MmDatasetResp(
        UUID datasetId,
        UUID spaceId,
        String datasetName,
        String datasetType,
        String modalityType,
        String description,
        UUID rawRepoId,
        UUID indexRepoId,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
