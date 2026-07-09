package com.fwdrobo.sirius.entity.mm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class MmIndexVersion {
    private UUID indexVersionId;
    private UUID datasetId;
    private UUID datasetVersionId;
    private UUID indexRepoId;
    private UUID indexCommitId;
    private String indexStatus;
    private String indexType;
    private String modelName;
    private String modelVersion;
    private Integer embeddingDim;
    private String imageIndexPath;
    private String textIndexPath;
    private String metadataPath;
    private String manifestPath;
    private Long imageCount;
    private Long textCount;
    private UUID buildJobId;
    private UUID buildRunId;
    private String errorMessage;
    private JsonNode meta;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime readyAt;
}
