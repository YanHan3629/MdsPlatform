package com.fwdrobo.sirius.dto.user;

import java.util.List;
import java.util.UUID;

/**
 * 组织用户列表响应对象，包含用户基础信息与角色编码列表。
 */
public record OrgUserResp(
        UUID userId,
        String userName,
        UUID orgId,
        Integer statusCode,
        Integer tokenVersion,
        List<String> roles
) {}
