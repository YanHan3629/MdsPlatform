package com.fwdrobo.sirius.dto.artifact;

import java.util.List;
import java.util.UUID;

/**
 * Artifact 标签列表响应。
 */
public record ArtifactTagsResponse(
        UUID artifactId,
        List<ArtifactTagItemResponse> tags
) {}
