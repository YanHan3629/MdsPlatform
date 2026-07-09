package com.fwdrobo.sirius.dto.mm;

import java.util.UUID;

public record InternalTextToImageReq(
        UUID datasetId,
        UUID versionId,
        UUID indexVersionId,
        String query,
        Integer topK
) {
}
