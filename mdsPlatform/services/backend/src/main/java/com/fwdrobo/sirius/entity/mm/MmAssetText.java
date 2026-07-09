package com.fwdrobo.sirius.entity.mm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class MmAssetText {
    private UUID assetTextId;
    private UUID assetId;
    private String textRole;
    private Integer seqNo;
    private String languageCode;
    private String content;
    private String sourceType;
    private JsonNode meta;
    private OffsetDateTime createdAt;
}
