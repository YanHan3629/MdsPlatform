package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.dto.permission.PermissionReq;
import com.fwdrobo.sirius.entity.permission.Permission;
import com.fwdrobo.sirius.entity.permission.PermissionTree;
import com.fwdrobo.sirius.mapper.PermissionMapper;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
public class PermissionService {

    private final PermissionMapper permissionMapper;

    public PermissionService(PermissionMapper permissionMapper) {
        this.permissionMapper = permissionMapper;
    }

    public Permission get(UUID perId) {
        Permission permission = permissionMapper.selectById(perId);
        if (permission == null) {
            throw ExceptionUtils.notFound("权限不存在");
        }
        return permission;
    }

    public Permission getByCode(String perCode) {
        Permission permission = permissionMapper.selectByCode(perCode);
        if (permission == null) {
            throw ExceptionUtils.notFound("权限不存在");
        }
        return permission;
    }

    public java.util.List<Permission> list(String category) {
        return permissionMapper.selectAll(category);
    }

    /**
     * 按权限ID集合批量查询权限。
     */
    public List<Permission> listByIds(List<UUID> permissionIds) {
        if (permissionIds == null || permissionIds.isEmpty()) {
            return List.of();
        }
        return permissionMapper.selectByIds(permissionIds);
    }

    public java.util.List<Permission> listChildren(UUID parentId) {
        if (parentId == null) {
            throw ExceptionUtils.badRequest("parentId不能为空");
        }
        return permissionMapper.selectByParentId(parentId);
    }

    @Transactional
    public UUID create(PermissionReq perReq) {
        validate(perReq);

        Permission exists = permissionMapper.selectByCode(perReq.getPerCode());
        if (exists != null) {
            throw ExceptionUtils.conflict("perCode已存在");
        }
        Permission per = toPermission(perReq);
        per.setCreatedBy(SecurityUtils.getUserId());
        per.setCreatedAt(OffsetDateTime.now());

        return permissionMapper.insert(per);
    }

    @Transactional
    public void update(UUID perId, PermissionReq perReq) {
        if (perReq == null || perId == null) {
            throw ExceptionUtils.badRequest("perId不能为空");
        }

        Permission db = permissionMapper.selectById(perId);
        if (db == null) {
            throw ExceptionUtils.notFound("权限不存在");
        }

        // 内置保护
        if (Boolean.TRUE.equals(db.getIsBuiltin())
                && perReq.getPerCode() != null
                && !perReq.getPerCode().equals(db.getPerCode())) {
            throw ExceptionUtils.conflict("系统内置权限不允许修改 per_code");
        }

        if (perReq.getPerCode() != null
                && !perReq.getPerCode().equals(db.getPerCode())) {

            Permission exists = permissionMapper.selectByCode(perReq.getPerCode());
            if (exists != null) {
                throw ExceptionUtils.conflict("perCode已存在");
            }
        }

        Permission permission = toPermission(perReq);
        permission.setPerId(perId);
        permission.setUpdatedBy(SecurityUtils.getUserId());
        permission.setUpdatedAt(OffsetDateTime.now());
        permissionMapper.update(permission);
    }

    @Transactional
    public void delete(UUID perId) {
        if (perId == null) return;

        Permission db = permissionMapper.selectById(perId);
        if (db == null) return;

        if (Boolean.TRUE.equals(db.getIsBuiltin())) {
            throw ExceptionUtils.conflict("系统内置权限不允许删除");
        }

        permissionMapper.deleteById(perId);
    }

    public List<PermissionTree> getPermissionTree() {

        // 一次性查出所有权限
        List<Permission> all = permissionMapper.selectAll(null);

        // feature 容器
        Map<UUID, PermissionTree> featureMap = new LinkedHashMap<>();

        // 第一步：先放 feature
        for (Permission p : all) {
            if ("feature".equals(p.getCategory())) {
                PermissionTree node = toTreeDTO(p);
                featureMap.put(p.getPerId(), node);
            }
        }

        // 第二步：挂 operation
        for (Permission p : all) {
            if ("operation".equals(p.getCategory()) && p.getParentId() != null) {
                PermissionTree parent = featureMap.get(p.getParentId());
                if (parent != null) {
                    parent.getChildren().add(toTreeDTO(p));
                }
            }
        }

        return new ArrayList<>(featureMap.values());
    }

    private PermissionTree toTreeDTO(Permission p) {
        PermissionTree dto = new PermissionTree();
        dto.setPerId(p.getPerId());
        dto.setPerCode(p.getPerCode());
        dto.setPerName(p.getPerName());
        dto.setCategory(p.getCategory());
        dto.setDescription(p.getDescription());
        return dto;
    }

    private void validate(PermissionReq p) {
        if (p == null) throw ExceptionUtils.badRequest("参数不能为空");
        if (p.getPerCode() == null || p.getPerCode().isBlank())
            throw ExceptionUtils.badRequest("perCode不能为空");
        if (p.getPerName() == null || p.getPerName().isBlank())
            throw ExceptionUtils.badRequest("perName不能为空");
        if (p.getCategory() == null || p.getCategory().isBlank())
            throw ExceptionUtils.badRequest("category不能为空");

        validateCategoryRule(p.getCategory(), p.getParentId());
    }

    private void validateCategoryRule(String category, UUID parentId) {
        if (!"feature".equals(category) && !"operation".equals(category)) {
            throw ExceptionUtils.badRequest("category必须是 feature 或 operation");
        }

        if ("feature".equals(category) && parentId != null) {
            throw ExceptionUtils.badRequest("feature 不允许设置 parent_id");
        }

        if ("operation".equals(category) && parentId == null) {
            throw ExceptionUtils.badRequest("operation 必须设置 parent_id 指向 feature");
        }
    }

    private Permission toPermission(PermissionReq permissionReq) {
        if (permissionReq == null) {
            return null;
        }
        Permission permission = new Permission();
        BeanUtils.copyProperties(permissionReq, permission);
        return permission;
    }
}
