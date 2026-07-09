package com.fwdrobo.sirius.mapper;

import com.fwdrobo.sirius.entity.permission.Permission;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

public interface PermissionMapper {

    Permission selectById(@Param("perId") UUID perId);

    Permission selectByCode(@Param("perCode") String perCode);

    List<Permission> selectAll(@Param("category") String category);

    List<Permission> selectByParentId(@Param("parentId") UUID parentId);

    UUID insert(Permission permission);

    int update(Permission permission);

    int deleteById(@Param("perId") UUID perId);

    List<Permission> selectByIds(@Param("ids") List<UUID> ids);
}