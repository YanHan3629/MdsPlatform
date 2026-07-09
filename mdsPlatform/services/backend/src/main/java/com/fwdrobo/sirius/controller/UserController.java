package com.fwdrobo.sirius.controller;

import com.fwdrobo.sirius.dto.user.PasswordResetReq;
import com.fwdrobo.sirius.entity.FwdUser;
import com.fwdrobo.sirius.service.RoleService;
import com.fwdrobo.sirius.service.UserService;
import com.fwdrobo.sirius.util.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;


@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService userService;
    private final RoleService roleService;

    public UserController(UserService userService, RoleService roleService) {
        this.userService = userService;
        this.roleService = roleService;
    }

    /**
     * 列出所有用户（仅管理员）
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public List<FwdUser> list() {
        return userService.list();
    }

    /**
     * 获取当前登录用户信息
     * - 返回用户名和角色权限
     * - 需要提供有效的访问令牌
     */
    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        String username = SecurityUtils.getUserName();
        List<String> roles = SecurityUtils.getUserRoles();
        return Map.of(
                "userName", username,
                "roles", roles
        );
    }

    /**
     * 修改当前登录用户密码
     * - 采用 旧密码 + 新密码 的方式修改密码
     * - 需要提供有效的访问令牌
     */
    @PatchMapping("/me/password")
    public Map<String, Object> changePassword(@RequestBody PasswordResetReq req) {
        userService.changePassword(req.oldPassword(), req.newPassword());
        return Map.of(
                "message", "密码修改成功"
        );
    }

    @PutMapping("/{userId}/roles")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public Map<String, Object> setUserRoles(@PathVariable UUID userId, @RequestBody List<UUID> roleIds) {
        roleService.setUserRoles(userId, roleIds);
        return Map.of(
                "success", true,
                "message", "用户授权成功"
        );
    }
}
