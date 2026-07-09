package com.fwdrobo.sirius.mapper;

import com.fwdrobo.sirius.entity.permission.Org;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

public interface OrgMapper {
    Org selectById(@Param("orgId") UUID orgId);
    Org selectByName(@Param("orgName") String orgName);

    /**
     * 查询组织授权类型。
     */
    String selectLicenseTypeByOrgId(@Param("orgId") UUID orgId);

    List<Org> selectAll(@Param("orgStatus") Integer orgStatus); // 可空

    /**
     * 按状态统计组织数量。
     */
    long countByStatus(@Param("orgStatus") Integer orgStatus);

    /**
     * 按状态分页查询组织。
     */
    List<Org> selectPageByStatus(@Param("orgStatus") Integer orgStatus,
                                 @Param("offset") int offset,
                                 @Param("size") int size);

    UUID insert(Org org);   // orgId 可空，DB 默认 gen_random_uuid()
    int update(Org org);   // orgName/orgStatus 可空时不改
    int deleteById(@Param("orgId") UUID orgId);
}
