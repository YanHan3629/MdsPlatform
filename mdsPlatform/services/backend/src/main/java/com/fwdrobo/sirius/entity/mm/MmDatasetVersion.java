package com.fwdrobo.sirius.entity.mm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class MmDatasetVersion {
    private UUID versionId;
    private UUID datasetId;
    private String versionName;
    private UUID rawCommitId;
    private String versionStatus;
    private Long sampleCount;
    private Long imageCount;
    private Long textCount;
    private UUID activeIndexVersionId;
    private String comment;
    private JsonNode meta;
    private UUID createdBy;
    private UUID publishedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime publishedAt;
}
