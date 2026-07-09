package com.fwdrobo.sirius.dto.permission;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class RoleReq {
    private UUID orgId;
    @NotBlank private String roleCode;
    @NotBlank private String roleName;

    // 前端勾选的权限
    private List<UUID> permissionIds;
}
