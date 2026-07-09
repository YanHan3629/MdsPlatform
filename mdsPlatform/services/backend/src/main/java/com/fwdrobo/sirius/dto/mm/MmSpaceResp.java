package com.fwdrobo.sirius.dto.mm;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MmSpaceResp(
        UUID spaceId,
        String spaceName,
        String description,
        UUID orgId,
        UUID ownerUserId,
        String visibility,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
