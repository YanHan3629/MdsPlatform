package com.fwdrobo.sirius.entity.mm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class MmDataset {
    private UUID datasetId;
    private UUID spaceId;
    private String datasetName;
    private String datasetType;
    private String modalityType;
    private String description;
    private UUID rawRepoId;
    private UUID indexRepoId;
    private UUID ownerUserId;
    private UUID orgId;
    private String status;
    private JsonNode meta;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
