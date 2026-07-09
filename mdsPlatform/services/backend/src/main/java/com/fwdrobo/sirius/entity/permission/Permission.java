package com.fwdrobo.sirius.entity.permission;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class Permission {
    private UUID perId;
    private UUID parentId;
    private String perCode;
    private String perName;
    private String category;      // operation/api/feature
    private String description;
    private Boolean isBuiltin;
    private OffsetDateTime createdAt;
    private UUID createdBy;
    private OffsetDateTime updatedAt;
    private UUID updatedBy;
}

