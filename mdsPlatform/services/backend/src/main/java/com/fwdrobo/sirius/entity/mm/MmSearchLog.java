package com.fwdrobo.sirius.entity.mm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class MmSearchLog {
    private UUID searchLogId;
    private UUID datasetId;
    private UUID datasetVersionId;
    private UUID indexVersionId;
    private String queryType;
    private String queryText;
    private UUID queryAssetId;
    private String queryTempFileKey;
    private Integer topK;
    private Integer resultCount;
    private Integer latencyMs;
    private UUID requestUserId;
    private JsonNode meta;
    private OffsetDateTime createdAt;
}
