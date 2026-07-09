package com.fwdrobo.sirius.entity.mm;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class MmSpace {
    private UUID spaceId;
    private String spaceName;
    private String description;
    private UUID orgId;
    private UUID ownerUserId;
    private String visibility;
    private String status;
    private JsonNode meta;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
