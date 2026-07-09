package com.fwdrobo.sirius.mapper;


import com.fwdrobo.sirius.entity.FwdUser;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

public interface UserMapper {
    int insert(FwdUser user);
    FwdUser selectById(@Param("userId") UUID userId);
    List<FwdUser> selectAll();

    FwdUser selectByUsername(@Param("userName") String userName);

    int existsByUsername(@Param("userName") String userName);

    int updateStatus(@Param("userId") UUID userId, @Param("statusCode") Integer statusCode);

    int incrementTokenVersion(@Param("userId") UUID userId);

    int incrementTokenVersionForNonAdmin();

    int updatePassword(@Param("userId") UUID userId, @Param("passwordHash") String passwordHash);

    List<FwdUser> selectByOrg(@Param("orgId") UUID orgId);
}
