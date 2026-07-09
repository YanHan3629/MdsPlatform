package com.fwdrobo.sirius.controller;

import com.fwdrobo.sirius.entity.FwdUser;
import com.fwdrobo.sirius.security.AuthRefreshService;
import com.fwdrobo.sirius.service.UserService;
import com.fwdrobo.sirius.util.SecurityUtils;
import com.fwdrobo.sirius.dto.user.RefreshReq;
import com.fwdrobo.sirius.dto.user.LoginReq;
import com.fwdrobo.sirius.dto.user.RegisterReq;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final UserService userService;
    private final AuthRefreshService authRefreshService;

    public AuthController(UserService userService, AuthRefreshService authRefreshService) {
        this.userService = userService;
        this.authRefreshService = authRefreshService;
    }

    /**
     * 用户登录接口
     * - 验证用户名和密码
     * - 返回访问令牌(token)和刷新令牌(refreshToken)
     */
    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginReq req) {
        return authRefreshService.login(req.userName(), req.password());
    }

    /**
     * 刷新访问令牌
     * - 使用refreshToken换取新的访问令牌
     * - 延长用户会话时间
     */
    @PostMapping("/refresh")
    public Map<String, Object> refresh(@Valid @RequestBody RefreshReq req) {
        return authRefreshService.refresh(req.refreshToken());
    }

    /**
     * 注册新用户（仅管理员）
     * - 创建新用户账号
     * - 设置用户名、密码和角色
     */
    @PostMapping("/register")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public Map<String, Object> register(@Valid @RequestBody RegisterReq req) {
        FwdUser user = userService.register(req.userName(), req.password(), req.roles(), req.orgId());
        return Map.of(
                "success", true,
                "message", "注册成功",
                "userId", user.getUserId(),
                "userName", user.getUserName(),
                "userRole", user.getRoles(),
                "statusCode", user.getStatusCode()
        );
    }

    /**
     * 删除用户（仅管理员）
     * - 从系统中永久删除指定用户
     * - userId: 要删除的用户ID
     */
    @PostMapping("/delete/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public Map<String, Object> deleteUser(
        @PathVariable
        @NotNull(message = "userId不能为空") 
        UUID userId
    ) {
        userService.deleteUser(userId);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "用户已删除");
        response.put("userId", userId);
        response.put("statusCode", null);
        return response;
    }

    /**
     * 强制用户登出（仅管理员）
     * - 使指定用户的所有令牌失效
     * - userId: 要登出的用户ID
     */
    @PostMapping("/logout/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public Map<String, Object> logoutUser(
        @PathVariable
        @NotNull(message = "userId不能为空") 
        UUID userId
    ) {
        userService.forceLogout(userId);
        return Map.of(
                "success", true,
                "message", "用户已强制登出",
                "userId", userId,
                "statusCode", 0
        );
    }

    /**
     * 强制所有非管理员用户登出（仅管理员）
     * - 使所有非管理员用户的令牌失效
     * - 管理员账号不受影响
     */
    @PostMapping("/logout/all")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public Map<String, Object> logoutAllExceptAdmin() {
        int affected = userService.forceLogoutAllNonAdmin();
        return Map.of(
                "success", true,
                "message", "所有非管理员用户已强制登出",
                "affected", affected,
                "statusCode", 0
        );
    }

    /**
     * 当前用户登出
     * - 使当前用户的所有令牌失效
     * - 需要提供有效的访问令牌
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(Authentication authentication) {
        String username = SecurityUtils.getUserName();
        userService.logout(username);
        return Map.of(
                "success", true,
                "message", "登出成功",
                "userName", username,
                "statusCode", 0
        );
    }


}
