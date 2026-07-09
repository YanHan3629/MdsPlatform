package com.fwdrobo.sirius.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * 注册用户请求体。
 */
public record RegisterReq(
        @NotBlank(message = "用户名不能为空")
        String userName,
        @NotBlank(message = "密码不能为空")
        String password,
        @NotEmpty(message = "角色不能为空")
        List<@NotNull UUID> roles,
        @NotNull(message = "组织不能为空")
        UUID orgId
) {}
