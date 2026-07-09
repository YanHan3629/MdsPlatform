package com.fwdrobo.sirius.dto.mm;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MmUploadSessionSummaryResp(
        UUID sessionId,
        String status,
        String basePath,
        int expireSeconds,
        Integer expectedFileCount,
        int trackedFileCount,
        int preparedCount,
        int uploadedCount,
        int failedCount,
        OffsetDateTime createdAt,
        OffsetDateTime expiredAt,
        OffsetDateTime completedAt
) {
}
