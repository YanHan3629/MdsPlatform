package com.fwdrobo.sirius.mapper;

import com.fwdrobo.sirius.entity.permission.LicenseQuotaPolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * License 配额策略 Mapper。
 */
@Mapper
public interface LicenseQuotaPolicyMapper {

    /**
     * 按授权类型查询启用中的策略。
     */
    LicenseQuotaPolicy selectActiveByLicenseType(@Param("licenseType") String licenseType);

    /**
     * 查询全部启用中的策略。
     */
    List<LicenseQuotaPolicy> selectAllActivePolicies();
}
