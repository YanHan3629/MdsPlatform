package com.fwdrobo.sirius.entity.permission;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class Org {
    private UUID orgId;
    private String orgName;
    private Integer orgStatus;
    private String licenseType;
    private OffsetDateTime createdAt;
    private UUID createdBy;
    private OffsetDateTime updatedAt;
    private UUID updatedBy;
}
