package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.dto.permission.RoleReq;
import com.fwdrobo.sirius.entity.permission.Permission;
import com.fwdrobo.sirius.entity.permission.PermissionTree;
import com.fwdrobo.sirius.entity.permission.Role;
import com.fwdrobo.sirius.mapper.PermissionMapper;
import com.fwdrobo.sirius.mapper.RoleMapper;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.SecurityUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RoleService {

    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;

    public RoleService(RoleMapper roleMapper, PermissionMapper permissionMapper) {
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
    }

    public Role get(UUID roleId) {
        Role role = roleMapper.selectById(roleId);
        if (role == null) throw ExceptionUtils.badRequest("角色不存在");
        return role;
    }

    public List<Role> selectRoles() {
        return roleMapper.selectAll();
    }

    @Transactional
    public UUID create(RoleReq req) {
        Role exists = roleMapper.selectByOrgIdAndCode(req.getOrgId(), req.getRoleCode());
        if (exists != null) throw ExceptionUtils.badRequest("roleCode已存在");

        Role role = new Role();
        BeanUtils.copyProperties(req, role);
        role.setIsBuiltin(false);
        role.setCreatedAt(OffsetDateTime.now());
        role.setCreatedBy(SecurityUtils.getUserId());

        UUID roleId = roleMapper.insert(role);

        setRolePermissions(roleId, req.getPermissionIds());

        return roleId;
    }

    @Transactional
    public void update(UUID roleId, RoleReq req) {
        if (roleId == null) throw ExceptionUtils.badRequest("roleId不能为空");
        if (req == null) throw ExceptionUtils.badRequest("参数不能为空");

        Role db = roleMapper.selectById(roleId);
        if (db == null) throw ExceptionUtils.badRequest("角色不存在");

        // 内置保护：不允许改 code
        if (Boolean.TRUE.equals(db.getIsBuiltin()) && req.getRoleCode() != null
                && !req.getRoleCode().equals(db.getRoleCode())) {
            throw ExceptionUtils.badRequest("系统内置角色不允许修改 role_code");
        }

        // 如果改 code，检查唯一性
        if (!blank(req.getRoleCode()) && !req.getRoleCode().equals(db.getRoleCode())) {
            Role exists = roleMapper.selectByOrgIdAndCode(db.getOrgId(), req.getRoleCode());
            if (exists != null) throw ExceptionUtils.badRequest("roleCode已存在");
        }

        Role upd = new Role();
        upd.setRoleId(roleId);
        upd.setRoleCode(req.getRoleCode());
        upd.setRoleName(req.getRoleName());
        upd.setUpdatedAt(OffsetDateTime.now());
        upd.setUpdatedBy(SecurityUtils.getUserId());
        roleMapper.update(upd);

        if (req.getPermissionIds() != null) {
            setRolePermissions(roleId, req.getPermissionIds());
        }
    }

    @Transactional
    public void delete(UUID roleId) {
        if (roleId == null) return;

        Role db = roleMapper.selectById(roleId);
        if (db == null) return;

        if (Boolean.TRUE.equals(db.getIsBuiltin())) {
            throw ExceptionUtils.badRequest("系统内置角色不允许删除");
        }

        roleMapper.deleteRolePermissions(roleId);
        roleMapper.deleteById(roleId);
    }

    public List<Role> getRolesByUserId(UUID userId) {
        return roleMapper.selectRolesByUserId(userId);
    }

    public List<Role> getRolesByIds(List<UUID> roleIds) {
        return roleMapper.selectByIds(roleIds);
    }

    public void insertUserRoles(UUID userId, List<UUID> roleIds) {
        roleMapper.insertUserRoles(userId, roleIds);
    }

    /**
     * 覆盖更新用户角色：先删除原角色，再写入新角色。
     */
    @Transactional
    public void setUserRoles(UUID userId, List<UUID> roleIds) {
        if (userId == null) {
            throw ExceptionUtils.badRequest("userId不能为空");
        }
        roleMapper.deleteUserRolesByUserId(userId);
        List<UUID> uniqueRoleIds = distinct(roleIds);
        if (uniqueRoleIds.isEmpty()) {
            return;
        }
        roleMapper.insertUserRoles(userId, uniqueRoleIds);
    }

    public List<UUID> listRolePermissionIds(UUID roleId) {
        return roleMapper.selectPerIdsByRoleId(roleId);
    }

    @Transactional
    public void setRolePermissions(UUID roleId, List<UUID> permissionIds) {
        List<UUID> unique = distinct(permissionIds);

        // 先清空
        roleMapper.deleteRolePermissions(roleId);

        if (unique.isEmpty()) return;

        // 批量查 permission，做分类 + parent 推导
        List<Permission> perms = permissionMapper.selectByIds(unique);
        if (perms.size() != unique.size()) {
            throw ExceptionUtils.badRequest("存在无效的 permissionId");
        }

        List<UUID> opIds = perms.stream()
                .filter(p -> "operation".equals(p.getCategory()))
                .map(Permission::getPerId)
                .collect(Collectors.toList());

        if (!opIds.isEmpty()) {
            roleMapper.insertRolePermissions(roleId, opIds);
        }
    }

    public List<PermissionTree> getRolePermissionTree(UUID roleId) {
        // 查全量权限（feature + operation）
        List<Permission> all = permissionMapper.selectAll(null);

        // 查角色已绑定的 per_id（推荐模式：仅 operation）
        Set<UUID> selectedOpIds = new HashSet<>(roleMapper.selectPerIdsByRoleId(roleId));

        // 建索引：id -> Permission
        Map<UUID, Permission> byId = new HashMap<>(all.size());
        for (Permission p : all) byId.put(p.getPerId(), p);

        // feature 节点容器（保持稳定顺序）
        Map<UUID, PermissionTree> featureNodes = new LinkedHashMap<>();
        for (Permission p : all) {
            if ("feature".equals(p.getCategory())) {
                PermissionTree f = toTreeNode(p);
                featureNodes.put(p.getPerId(), f);
            }
        }

        // 挂 operation，并设置 operation.checked
        for (Permission p : all) {
            if (!"operation".equals(p.getCategory())) continue;
            UUID parentId = p.getParentId();
            if (parentId == null) continue;

            Permission parent = byId.get(parentId);
            if (parent == null || !"feature".equals(parent.getCategory())) continue;

            PermissionTree feature = featureNodes.get(parentId);
            if (feature == null) continue;

            PermissionTree op = toTreeNode(p);
            op.setChecked(selectedOpIds.contains(p.getPerId()));
            feature.getChildren().add(op);
        }

        // 计算 feature 的 checked / indeterminate
        List<PermissionTree> result = new ArrayList<>(featureNodes.values());
        for (PermissionTree f : result) {
            int total = f.getChildren().size();
            int checked = 0;
            for (PermissionTree op : f.getChildren()) {
                if (op.isChecked()) checked++;
            }

            if (total == 0) {
                f.setChecked(false);
                f.setIndeterminate(false);
            } else if (checked == 0) {
                f.setChecked(false);
                f.setIndeterminate(false);
            } else if (checked == total) {
                f.setChecked(true);
                f.setIndeterminate(false);
            } else {
                f.setChecked(false);
                f.setIndeterminate(true);
            }

            // 对子节点排序（按 perCode）
            f.getChildren().sort(java.util.Comparator.comparing(PermissionTree::getPerCode));
        }

        // feature 排序
        result.sort(java.util.Comparator.comparing(PermissionTree::getPerCode));

        return result;
    }

    private PermissionTree toTreeNode(Permission p) {
        PermissionTree n = new PermissionTree();
        BeanUtils.copyProperties(p, n);
        return n;
    }

    private List<UUID> distinct(List<UUID> list) {
        if (list == null) return Collections.emptyList();
        return new ArrayList<>(new LinkedHashSet<>(list));
    }

    private boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
