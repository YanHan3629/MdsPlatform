package com.fwdrobo.sirius.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/rbac")
public class RbacController {

    /**
     * 仅管理员可访问的接口（测试接口）
     */
    @GetMapping("/admin-only")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> adminOnly() {
        return Map.of(
                "path", "/api/rbac/admin-only",
                "message", "ADMIN 可访问的接口",
                "allowedRoles", "ADMIN"
        );
    }

    /**
     * 用户和管理员均可访问的接口（测试接口）
     */
    @GetMapping("/user-allowed")
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public Map<String, Object> userAllowed() {
        return Map.of(
                "path", "/api/rbac/user-allowed",
                "message", "USER 和 ADMIN 可访问的接口",
                "allowedRoles", "USER, ADMIN"
        );
    }
}
