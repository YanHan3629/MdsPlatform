package com.fwdrobo.sirius.util;

import com.fwdrobo.sirius.security.JwtAuthFilter.UserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 工具类：获取当前认证用户的信息
 */
@Component
public final class SecurityUtils {
    
    private SecurityUtils() {
    }

    /**
     * 获取当前认证用户的 userId
     *
     * @return userId，若未认证则返回 null
     */
    public static UUID getUserId() {
        UserPrincipal principal = currentPrincipal();
        if (principal != null) {
            return principal.userId();
        }
        return null;
    }

    /**
     * 获取当前认证用户的 userName
     *
     * @return userName，若未认证则返回 null
     */
    public static String getUserName() {
        UserPrincipal principal = currentPrincipal();
        if (principal != null) {
            return principal.userName();
        }
        return null;
    }

    public static UUID getUserOrgId() {
        UserPrincipal principal = currentPrincipal();
        if (principal != null) {
            return principal.orgId();
        }
        return null;
    }

    /**
     * 获取当前认证用户的角色列表
     *
     * @return ["ROLE_{Role}", ...]，若未认证则返回空列表
     */
    public static List<String> getUserRoles() {
        UserPrincipal principal = currentPrincipal();
        if (principal != null && principal.roles() != null && !principal.roles().isEmpty()) {
            return principal.roles();
        }
        return null;
    }

    /**
     * 私有方法
     * 获取当前认证用户的 Principal 对象
     *
     * @return UserPrincipal，若未认证则返回 null
     */
    private static UserPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal) {
            return userPrincipal;
        }
        return null;
    }

    public static String getToken(String authz) {
        String token = "";
        if (authz != null && authz.toLowerCase().startsWith("bearer ")) {
            token = authz.substring(7).trim();
        }
        return token;
    }
}
