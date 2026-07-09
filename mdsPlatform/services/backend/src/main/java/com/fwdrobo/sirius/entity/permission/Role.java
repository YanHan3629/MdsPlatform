package com.fwdrobo.sirius.entity.permission;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class Role {
    private UUID roleId;
    private UUID orgId;
    private String roleCode;
    private String roleName;
    private Boolean isBuiltin;
    private OffsetDateTime createdAt;
    private UUID createdBy;
    private OffsetDateTime updatedAt;
    private UUID updatedBy;
}
