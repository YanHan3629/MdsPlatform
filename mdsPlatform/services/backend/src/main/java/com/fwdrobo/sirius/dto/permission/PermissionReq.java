package com.fwdrobo.sirius.dto.permission;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class PermissionReq {
    private UUID parentId;
    @NotBlank private String perCode;
    @NotBlank private String perName;
    @NotBlank private String category;
    private String description;
    private Boolean isBuiltin;
}
