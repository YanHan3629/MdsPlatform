package com.fwdrobo.sirius.entity.mm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class MmAssetCategory {
    private UUID assetId;
    private UUID categoryId;
    private Double score;
    private String sourceType;
    private OffsetDateTime createdAt;
}
