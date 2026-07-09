package com.fwdrobo.sirius.entity.mm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class MmTag {
    private UUID tagId;
    private UUID spaceId;
    private String tagName;
    private String tagColor;
    private UUID createdBy;
    private OffsetDateTime createdAt;
}
