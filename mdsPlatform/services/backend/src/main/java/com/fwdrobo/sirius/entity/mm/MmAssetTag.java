package com.fwdrobo.sirius.entity.mm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class MmAssetTag {
    private UUID assetId;
    private UUID tagId;
    private String sourceType;
    private Double score;
    private OffsetDateTime createdAt;
}
