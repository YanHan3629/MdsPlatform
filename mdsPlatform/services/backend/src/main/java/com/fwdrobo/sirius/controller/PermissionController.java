package com.fwdrobo.sirius.controller;

import com.fwdrobo.sirius.dto.permission.PermissionReq;
import com.fwdrobo.sirius.entity.permission.Permission;
import com.fwdrobo.sirius.entity.permission.PermissionTree;
import com.fwdrobo.sirius.service.PermissionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /**
     * 获取权限树结构（feature -> operation）
     */
    @GetMapping("/tree")
    public List<PermissionTree> getPermissionTree() {
        return permissionService.getPermissionTree();
    }

    @GetMapping("/{perId}")
    public Permission get(@PathVariable UUID perId) {
        return permissionService.get(perId);
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> create(@RequestBody PermissionReq req) {
        UUID perId = permissionService.create(req);
        return Map.of("message", "权限创建成功", "perId", perId);
    }

    @PatchMapping("/{perId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> update(@PathVariable UUID perId, @RequestBody PermissionReq req) {
        permissionService.update(perId, req);
        return Map.of("message", "权限修改成功", "perId", perId);
    }

    @DeleteMapping("/{perId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> delete(@PathVariable UUID perId) {
        permissionService.delete(perId);
        return Map.of("message", "权限删除成功", "perId", perId);
    }

}
