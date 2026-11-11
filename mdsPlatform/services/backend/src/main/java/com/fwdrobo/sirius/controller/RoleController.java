package com.fwdrobo.sirius.controller;

import com.fwdrobo.sirius.dto.permission.RoleReq;
import com.fwdrobo.sirius.entity.permission.PermissionTree;
import com.fwdrobo.sirius.entity.permission.Role;
import com.fwdrobo.sirius.service.RoleService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping("/{roleId}")
    public Role get(@PathVariable UUID roleId) {
        return roleService.get(roleId);
    }

    @GetMapping
    public List<Role> listRoles() {
        return roleService.selectRoles();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody RoleReq req) {
        UUID roleId = roleService.create(req);
        return Map.of("message", "role创建成功",
                "roleId", roleId);
    }

    @PatchMapping("/{roleId}")
    public Map<String, Object> update(@PathVariable UUID roleId, @RequestBody RoleReq req) {
        roleService.update(roleId, req);
        return Map.of("message", "role修改成功",
                "roleId", roleId);
    }

    @DeleteMapping("/{roleId}")
    public Map<String, Object> delete(@PathVariable UUID roleId,
            @RequestHeader(value="If-Match",required=false) Long version,
            @RequestParam(required=false) UUID replacementTypeId,
            @RequestParam(required=false) Long replacementTypeVersion) {
        roleService.delete(roleId,version,replacementTypeId,replacementTypeVersion);
        return Map.of("message", "role删除成功",
                "roleId", roleId);
    }

    @GetMapping("/{roleId}/permissions")
    public List<UUID> listRolePermissions(@PathVariable UUID roleId) {
        return roleService.listRolePermissionIds(roleId);
    }

    @PutMapping("/{roleId}/permissions")
    public void setRolePermissions(@PathVariable UUID roleId, @RequestBody List<UUID> permissionIds,
            @RequestHeader(value="If-Match",required=false) Long version) {
        roleService.setRolePermissions(roleId, permissionIds,version);
    }

    @GetMapping("/{roleId}/permissions/tree")
    public List<PermissionTree> getRolePermissionTree(@PathVariable UUID roleId) {
        return roleService.getRolePermissionTree(roleId);
    }


    @PostMapping("/{roleId}/impact") public Object impact(@PathVariable UUID roleId,@RequestBody RoleReq req){return roleService.previewUpdate(roleId,req);}
    @PostMapping("/{roleId}/permissions/impact") public Object permissionsImpact(@PathVariable UUID roleId,@RequestBody List<UUID> permissionIds,@RequestHeader(value="If-Match",required=false) Long version){return roleService.previewPermissions(roleId,permissionIds,version);}
    @PostMapping("/{roleId}/delete-impact") public Object deleteImpact(@PathVariable UUID roleId,@RequestHeader(value="If-Match",required=false) Long version,@RequestParam(required=false) UUID replacementTypeId,@RequestParam(required=false) Long replacementTypeVersion){return roleService.previewDelete(roleId,version,replacementTypeId,replacementTypeVersion);}
}
