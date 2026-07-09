package com.fwdrobo.sirius.entity.mm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class MmAsset {
    private UUID assetId;
    private UUID datasetVersionId;
    private UUID fileId;
    private String assetType;
    private String logicalPath;
    private String fileName;
    private String sourceAssetCode;
    private String sha256;
    private Long sizeBytes;
    private String contentType;
    private Integer width;
    private Integer height;
    private String status;
    private JsonNode meta;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
