package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.dto.user.OrgUserResp;
import com.fwdrobo.sirius.entity.FwdUser;
import com.fwdrobo.sirius.entity.permission.Role;
import com.fwdrobo.sirius.mapper.UserMapper;
import com.fwdrobo.sirius.security.PasswordHashService;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.PasswordValidator;
import com.fwdrobo.sirius.util.SecurityUtils;

import lombok.extern.slf4j.Slf4j;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserService {
    private final UserMapper userMapper;
    private final AuthCacheService authCacheService;
    private final PasswordHashService passwordHashService;
    private final PasswordEncoder passwordEncoder;
    private final RoleService roleService;

    public UserService(UserMapper userMapper,
                       AuthCacheService authCacheService,
                       PasswordHashService passwordHashService,
                       PasswordEncoder passwordEncoder, RoleService roleService) {
        this.userMapper = userMapper;
        this.authCacheService = authCacheService;
        this.passwordHashService = passwordHashService;
        this.passwordEncoder = passwordEncoder;
        this.roleService = roleService;
    }

    /**
     * 注册新用户
     */
    public FwdUser register(String username, String password, List<UUID> roleIds, UUID orgId) {
        // 校验密码复杂度
        try {
            PasswordValidator.validate(password);
        } catch (IllegalArgumentException e) {
            throw ExceptionUtils.badRequest("密码不符合复杂度要求：" + e.getMessage());
        }

        // 检查用户名是否已存在
        if (userMapper.existsByUsername(username) > 0) {
            throw ExceptionUtils.conflict("用户名 '" + username + "' 已存在，请使用其他用户名");
        }

        FwdUser u = new FwdUser();
        UUID userId = UUID.randomUUID();
        u.setUserId(userId);
        u.setUserName(username);
        u.setOrgId(orgId);
        u.setPasswordHash(passwordHashService.encode(password));
        u.setStatusCode(0);
        u.setTokenVersion(0);
        userMapper.insert(u);
        authCacheService.cacheUser(u);

        roleService.insertUserRoles(userId, roleIds);
        List<Role> roles = roleService.getRolesByIds(roleIds);
        if (roles != null) {
            var roleCodes = roles.stream()
                    .map(Role::getRoleCode)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            u.setRoles(roleCodes);
        }
        log.info("Registered new user: {} ", username);
        return u;
    }

    /**
     * 查找用户（使用缓存）
     * 注意：由于passwordHash被@JsonIgnore，从缓存返回的用户对象不包含密码哈希
     * 如需验证密码，请使用findByUsernameForAuth方法
     */
    public Optional<FwdUser> findByUsername(String username) {
        FwdUser cached = authCacheService.getCachedUser(username);
        if (cached != null && cached.getStatusCode() == null) {
            authCacheService.evictUser(username);
            cached = null;
        }
        if (cached != null) {
            return Optional.of(cached);
        }
        FwdUser user = userMapper.selectByUsername(username);
        if (user != null) {
            authCacheService.cacheUser(user);
        }
        return Optional.of(user);
    }

    /**
     * 查找用户用于认证（直接从数据库查询，确保包含passwordHash）
     * 用于登录等需要验证密码的场景
     */
    public Optional<FwdUser> findByUsernameForAuth(String username) {
        FwdUser user = userMapper.selectByUsername(username);
        if (user != null && user.getStatusCode() != null) {
            // 查询后更新缓存（虽然缓存中不会有passwordHash，但可以缓存其他信息）
            authCacheService.cacheUser(user);
            return Optional.of(user);
        }
        return Optional.empty();
    }

    /**
     * 列出所有用户
     */
    public List<FwdUser> list() {
        List<FwdUser> users = userMapper.selectAll();
        if (users != null) {
            users.forEach(authCacheService::cacheUser);
        }
        return users;
    }

    /**
     * 验证用户密码是否正确
     */
    public boolean isPasswordValid(FwdUser user, String rawPassword) {
        if (user == null || rawPassword == null) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    /**
     * 根据用户ID查询用户信息，优先从缓存获取
     */
    public Optional<FwdUser> findById(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        FwdUser cached = authCacheService.getCachedUser(userId);
        if (cached != null && cached.getStatusCode() == null) {
            authCacheService.evictUser(cached.getUserName());
            cached = null;
        }
        if (cached != null) {
            return Optional.of(cached);
        }
        FwdUser user = userMapper.selectById(userId);
        if (user != null) {
            authCacheService.cacheUser(user);
            if (user.getStatusCode() == null) {
                authCacheService.evictUser(user.getUserName());
                return Optional.empty();
            }
        }
        return Optional.of(user);
    }

    /**
     * 强制用户登出
     * - 使指定用户的所有令牌失效
     */
    public void forceLogout(UUID userId) {
        FwdUser user = userMapper.selectById(userId);
        if (user == null || user.getStatusCode() == null) {
            throw ExceptionUtils.notFound("用户不存在，无法执行登出操作");
        }
        userMapper.incrementTokenVersion(userId);
        authCacheService.evictUser(user.getUserName());
        authCacheService.evictTokensForUser(user.getUserId());
    }

    /**
     * 强制所有非管理员用户登出
     * - 使所有非管理员用户的令牌失效
     * - 管理员账号不受影响
     */
    public int forceLogoutAllNonAdmin() {
        int affected = userMapper.incrementTokenVersionForNonAdmin();
        authCacheService.clearAllAuthCaches();
        return affected;
    }

    /**
     * 用户登出
     * - 使当前用户的所有令牌失效
     */
    public void logout(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        FwdUser user = userMapper.selectByUsername(username);
        if (user == null) {
            throw ExceptionUtils.notFound("用户 '" + username + "' 不存在");
        }
        user.setStatusCode(0);
        userMapper.updateStatus(user.getUserId(), 0);
        userMapper.incrementTokenVersion(user.getUserId());
        authCacheService.evictUser(username);
        authCacheService.evictTokensForUser(user.getUserId());
    }

    /**
     * 修改当前用户密码
     */
    public void changePassword(String oldPassword, String newPassword) {
        UUID userId = SecurityUtils.getUserId();
        if (userId == null) {
            throw ExceptionUtils.notFound("用户不存在，无法修改密码");
        }
        try {
            PasswordValidator.validate(newPassword);
        } catch (IllegalArgumentException e) {
            throw ExceptionUtils.badRequest("新密码不符合复杂度要求：" + e.getMessage());
        }

        FwdUser user = userMapper.selectById(userId);
        if (user == null || user.getStatusCode() == null) {
            throw ExceptionUtils.notFound("用户不存在，无法修改密码");
        }

        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw ExceptionUtils.conflict("旧密码不正确");
        }

        String passwordHash = passwordHashService.encode(newPassword);
        int updated = userMapper.updatePassword(userId, passwordHash);
        if (updated <= 0) {
            throw ExceptionUtils.conflict("密码修改失败");
        }

        // TODO: 可选策略：修改密码后强制登出所有设备
        // 清除该User缓存和token
        // authCacheService.evictUser(user.getUserName());
        // authCacheService.evictTokensForUser(userId);
    }

    /**
     * 更新用户状态
     */
    public void updateStatus(FwdUser user, Integer statusCode) {
        if (user == null) {
            throw ExceptionUtils.notFound("用户不存在，无法更新状态");
        }
        if (user.getUserId() == null) {
            throw ExceptionUtils.notFound("userId字段不存在");
        }
        userMapper.updateStatus(user.getUserId(), statusCode);
        user.setStatusCode(statusCode);
    }

    /**
     * 删除用户
     */
    public void deleteUser(UUID userId) {
        if (userId == null) {
            throw ExceptionUtils.notFound("userId字段不存在");
        }
        FwdUser user = userMapper.selectById(userId);
        if (user == null || user.getStatusCode() == null) {
            throw ExceptionUtils.notFound("用户不存在，无法删除");
        }
        userMapper.updateStatus(userId, null);
        authCacheService.evictUser(user.getUserName());
        authCacheService.evictTokensForUser(userId);
    }

    public String resolveUsername(UUID userId) {
        if (userId == null) {
            return null;
        }
        return findById(userId)
                .map(FwdUser::getUserName)
                .orElse(null);
    }

    /**
     * 按组织查询用户，包含角色编码列表。
     */
    public List<OrgUserResp> getUsersByOrg(UUID orgId) {
        List<FwdUser> users = userMapper.selectByOrg(orgId);
        if (users == null || users.isEmpty()) {
            return List.of();
        }
        return users.stream()
                .map(this::toOrgUserResp)
                .collect(Collectors.toList());
    }

    /**
     * 将用户实体转换为组织用户响应对象。
     */
    private OrgUserResp toOrgUserResp(FwdUser user) {
        return new OrgUserResp(
                user.getUserId(),
                user.getUserName(),
                user.getOrgId(),
                user.getStatusCode(),
                user.getTokenVersion(),
                normalizeRoleCodes(user.getRoles())
        );
    }

    /**
     * 规范化角色编码列表，确保响应字段稳定输出为数组。
     */
    private List<String> normalizeRoleCodes(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        return roleCodes.stream()
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }
}
