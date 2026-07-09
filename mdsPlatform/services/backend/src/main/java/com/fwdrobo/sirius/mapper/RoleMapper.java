package com.fwdrobo.sirius.mapper;


import com.fwdrobo.sirius.entity.permission.Role;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

public interface RoleMapper {
    Role selectById(@Param("roleId") UUID roleId);

    List<Role> selectAll();
    List<Role> selectRolesByUserId(@Param("userId") UUID userId);

    Role selectByOrgIdAndCode(@Param("orgId") UUID orgId, @Param("roleCode") String roleCode);

    UUID insert(Role role);
    int update(Role role);
    int deleteById(@Param("roleId") UUID roleId);

    List<UUID> selectPerIdsByRoleId(@Param("roleId") UUID roleId);

    int deleteRolePermissions(@Param("roleId") UUID roleId);

    int insertRolePermissions(@Param("roleId") UUID roleId, @Param("perIds") List<UUID> perIds);

    int insertUserRoles(@Param("userId") UUID userId, @Param("roleIds") List<UUID> roleIds);
    int deleteUserRolesByUserId(@Param("userId") UUID userId);

    List<Role> selectByIds(@Param("ids") List<UUID> ids);
}
