package com.fwdrobo.sirius.dto.mm;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MmIndexVersionResp(
        UUID indexVersionId,
        UUID datasetId,
        UUID datasetVersionId,
        String indexStatus,
        String indexType,
        String modelName,
        UUID buildJobId,
        UUID buildRunId,
        OffsetDateTime readyAt,
        String errorMessage
) {
}
