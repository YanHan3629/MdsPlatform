package com.fwdrobo.sirius.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class MmDeterministicIdUtils {
    private MmDeterministicIdUtils() {
    }

    public static UUID assetId(UUID datasetVersionId, String logicalPath) {
        String normalized = PathUtils.normalizePath(logicalPath);
        String source = datasetVersionId + ":" + normalized;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }
}
