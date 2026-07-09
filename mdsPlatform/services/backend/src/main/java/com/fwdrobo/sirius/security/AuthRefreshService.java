package com.fwdrobo.sirius.security;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fwdrobo.sirius.entity.permission.Permission;
import com.fwdrobo.sirius.entity.permission.Org;
import com.fwdrobo.sirius.entity.permission.Role;
import com.fwdrobo.sirius.service.OrgService;
import com.fwdrobo.sirius.service.PermissionService;
import com.fwdrobo.sirius.service.RoleService;
import org.springframework.stereotype.Service;

import com.fwdrobo.sirius.entity.FwdUser;
import com.fwdrobo.sirius.service.AuthCacheService;
import com.fwdrobo.sirius.service.UserService;
import com.fwdrobo.sirius.util.ExceptionUtils;


@Service
public class AuthRefreshService {
    private final JwtService jwtService;
    private final UserService userService;
    private final AuthCacheService authCacheService;
    private final RoleService roleService;
    private final OrgService orgService;
    private final PermissionService permissionService;

    public AuthRefreshService(JwtService jwtService,
                              UserService userService,
                              AuthCacheService authCacheService,
                              RoleService roleService,
                              OrgService orgService,
                              PermissionService permissionService) {
        this.jwtService = jwtService;
        this.userService = userService;
        this.authCacheService = authCacheService;
        this.roleService = roleService;
        this.orgService = orgService;
        this.permissionService = permissionService;
    }

    public Map<String, Object> refresh(String refreshToken) {
        var claims = jwtService.parseClaims(refreshToken, JwtService.TokenType.REFRESH);
        String username = claims.getSubject();
        Integer tokenVersion = claims.get("token_version", Integer.class);
        UUID cachedUserId = authCacheService.getUserIdByRefreshToken(refreshToken);

        FwdUser user = userService.findByUsername(username)
                .orElseThrow(() -> ExceptionUtils.notFound("用户不存在"));
        if (user.getUserId() == null) {
            throw ExceptionUtils.notFound("用户状态异常");
        }
        if (user.getStatusCode() == null) {
            throw ExceptionUtils.notFound("用户不存在或已注销");
        }
        if (cachedUserId != null && !Objects.equals(user.getUserId(), cachedUserId)) {
            throw ExceptionUtils.unauthorized("刷新令牌已失效，用户信息不匹配，请重新登录");
        }
        if (!Objects.equals(user.getTokenVersion(), tokenVersion)) {
            throw ExceptionUtils.unauthorized("刷新令牌版本不匹配，可能已在其他地方登录，请重新登录");
        }
        return buildTokenResponse(user);
    }

    public Map<String, Object> login(String username, String password) {
        // 登录时必须从数据库查询以获取完整的用户信息（包括passwordHash）
        FwdUser user = userService.findByUsernameForAuth(username)
                .orElseThrow(() -> ExceptionUtils.notFound("用户不存在"));
        if (user.getUserId() == null) {
            throw ExceptionUtils.notFound("用户状态异常");
        }
        if (user.getStatusCode() == null) {
            throw ExceptionUtils.notFound("用户不存在或已注销");
        }
        if (!userService.isPasswordValid(user, password)) {
            throw ExceptionUtils.unauthorized("用户名或密码错误");
        }
        if (Objects.equals(user.getStatusCode(), 0)) {
            userService.updateStatus(user, 1);
        } else if (!Objects.equals(user.getStatusCode(), 1)) {
            throw ExceptionUtils.unauthorized("用户状态异常，无法登录");
        }
        return buildTokenResponse(user);
    }

    /**
     * 构建登录/刷新令牌响应，并返回用户角色与去重后的权限树。
     */
    public Map<String, Object> buildTokenResponse(FwdUser user) {
        List<Role> roles = roleService.getRolesByUserId(user.getUserId());
        Org org = orgService.get(user.getOrgId());
        var roleCodes = roles == null ? List.<String>of() : roles.stream()
                .map(Role::getRoleCode)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<PermissionNode> permissionTree = mergeUserPermissionTree(roles);
        String token = jwtService.generateAccessToken(user.getUserName(), roleCodes, user.getTokenVersion());
        String refreshToken = jwtService.generateRefreshToken(user.getUserName(), roleCodes,
                user.getTokenVersion());
        authCacheService.cacheUser(user);
        authCacheService.cacheTokens(
                user.getUserId(),
                token,
                jwtService.getAccessExpirationSeconds(),
                refreshToken,
                jwtService.getRefreshExpirationSeconds());

        return Map.of(
                "token", token,
                "tokenExpiresIn", jwtService.getAccessExpirationSeconds(),
                "refreshToken", refreshToken,
                "refreshExpiresIn", jwtService.getRefreshExpirationSeconds(),
                "user", Map.of(
                        "userid", user.getUserId(),
                        "username", user.getUserName(),
                        "org", Map.of("orgId", org.getOrgId(), "orgName", org.getOrgName()),
                        "role", roleCodes,
                        "permissionTree", permissionTree,
                        "statusCode", user.getStatusCode()));
    }

    /**
     * 合并用户全部角色的已授权权限树（只包含 role_permission 已存入 operation 及其父 feature）。
     */
    private List<PermissionNode> mergeUserPermissionTree(List<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        Set<UUID> grantedOperationIds = collectGrantedOperationIds(roles);
        if (grantedOperationIds.isEmpty()) {
            return List.of();
        }

        List<Permission> operationPermissions = permissionService.listByIds(new ArrayList<>(grantedOperationIds));
        if (operationPermissions.isEmpty()) {
            return List.of();
        }
        Map<UUID, Permission> operationById = new HashMap<>(operationPermissions.size());
        Set<UUID> featureIds = new LinkedHashSet<>();
        for (Permission permission : operationPermissions) {
            if (permission == null || permission.getPerId() == null) {
                continue;
            }
            if (!"operation".equals(permission.getCategory()) || permission.getParentId() == null) {
                continue;
            }
            operationById.put(permission.getPerId(), permission);
            featureIds.add(permission.getParentId());
        }
        if (featureIds.isEmpty()) {
            return List.of();
        }

        List<Permission> featurePermissions = permissionService.listByIds(new ArrayList<>(featureIds));
        Map<UUID, Permission> featureById = new HashMap<>(featurePermissions.size());
        for (Permission permission : featurePermissions) {
            if (permission != null && permission.getPerId() != null) {
                featureById.put(permission.getPerId(), permission);
            }
        }

        Map<UUID, PermissionNode> featureMap = new LinkedHashMap<>();
        Map<UUID, Map<UUID, PermissionNode>> featureChildrenMap = new HashMap<>();
        for (UUID operationId : grantedOperationIds) {
            Permission operation = operationById.get(operationId);
            if (operation == null) {
                continue;
            }
            UUID parentId = operation.getParentId();
            if (parentId == null) {
                continue;
            }
            Permission feature = featureById.get(parentId);
            if (feature == null || !"feature".equals(feature.getCategory())) {
                continue;
            }

            PermissionNode featureNode = featureMap.computeIfAbsent(parentId, key -> toFeatureNode(feature));
            Map<UUID, PermissionNode> childMap = featureChildrenMap.computeIfAbsent(parentId, key -> new LinkedHashMap<>());
            childMap.putIfAbsent(operationId, toOperationNode(operation));
            featureNode.children().clear();
            featureNode.children().addAll(childMap.values());
        }

        List<PermissionNode> result = new ArrayList<>(featureMap.values());
        for (PermissionNode feature : result) {
            feature.children().sort(Comparator.comparing(PermissionNode::perCode, Comparator.nullsLast(String::compareTo)));
        }
        result.sort(Comparator.comparing(PermissionNode::perCode, Comparator.nullsLast(String::compareTo)));
        return result;
    }

    /**
     * 收集用户全部角色在 role_permission 中已授权的 operation 权限ID，并去重。
     */
    private Set<UUID> collectGrantedOperationIds(List<Role> roles) {
        Set<UUID> grantedOperationIds = new LinkedHashSet<>();
        for (Role role : roles) {
            if (role == null || role.getRoleId() == null) {
                continue;
            }
            List<UUID> permissionIds = roleService.listRolePermissionIds(role.getRoleId());
            if (permissionIds == null || permissionIds.isEmpty()) {
                continue;
            }
            for (UUID permissionId : permissionIds) {
                if (permissionId != null) {
                    grantedOperationIds.add(permissionId);
                }
            }
        }
        return grantedOperationIds;
    }

    /**
     * 复制 feature 节点基础信息。
     */
    private PermissionNode toFeatureNode(Permission source) {
        return new PermissionNode(
                source.getPerId(),
                source.getPerCode(),
                source.getPerName(),
                source.getCategory(),
                new ArrayList<>()
        );
    }

    /**
     * 复制 operation 节点基础信息。
     */
    private PermissionNode toOperationNode(Permission source) {
        return new PermissionNode(
                source.getPerId(),
                source.getPerCode(),
                source.getPerName(),
                source.getCategory(),
                new ArrayList<>()
        );
    }

    /**
     * 登录返回使用的权限树节点，不包含 checked / indeterminate 字段。
     */
    private record PermissionNode(UUID perId, String perCode, String perName, String category,
                                  List<PermissionNode> children) {
    }
}
