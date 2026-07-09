package com.fwdrobo.sirius.entity.mm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class MmCategory {
    private UUID categoryId;
    private UUID datasetId;
    private UUID parentId;
    private String categoryCode;
    private String categoryName;
    private String sourceType;
    private OffsetDateTime createdAt;
}
