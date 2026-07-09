package com.fwdrobo.sirius.dto.artifact;

import java.util.UUID;

/**
 * Artifact 标签项响应。
 */
public record ArtifactTagItemResponse(
        String tag,
        boolean isSpecial,
        UUID commitId
) {}
