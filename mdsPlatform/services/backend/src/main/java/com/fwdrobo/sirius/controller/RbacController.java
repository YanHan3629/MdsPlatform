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
    @PreAuthorize("@authorization.hasOperation('identity.type.manage')")
    public Map<String, Object> adminOnly() {
        return Map.of(
                "path", "/api/rbac/admin-only",
                "message", "平台账号类型管理能力已验证",
                "requiredOperation", "identity.type.manage"
        );
    }

    /**
     * 用户和管理员均可访问的接口（测试接口）
     */
    @GetMapping("/user-allowed")
    @PreAuthorize("@authorization.hasOperation('identity.self.read')")
    public Map<String, Object> userAllowed() {
        return Map.of(
                "path", "/api/rbac/user-allowed",
                "message", "当前账号身份已验证",
                "requiredOperation", "identity.self.read"
        );
    }
}
