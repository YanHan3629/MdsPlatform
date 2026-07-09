package com.fwdrobo.sirius.dto.user;

import jakarta.validation.constraints.NotBlank;

public record RefreshReq(
        @NotBlank(message = "刷新令牌不能为空")
        String refreshToken
) {}
