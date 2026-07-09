package com.fwdrobo.sirius.dto.user;

import jakarta.validation.constraints.NotBlank;

public record LoginReq(
        @NotBlank(message = "用户名不能为空")
        String userName,
        @NotBlank(message = "密码不能为空")
        String password
) {}