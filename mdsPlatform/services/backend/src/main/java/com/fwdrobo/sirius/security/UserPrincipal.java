package com.fwdrobo.sirius.security;

import java.util.List;
import java.util.UUID;

public record UserPrincipal(
    
    /**
     * 用户唯一标识符 
     */
    UUID userId,

    /**
     * 用户名
     */
    String userName,

    /**
     * 角色列表，格式为 ["ROLE_{Role}", ...]
     */
    List<String> roles
) {}

