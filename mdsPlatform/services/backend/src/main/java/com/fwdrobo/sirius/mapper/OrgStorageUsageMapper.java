package com.fwdrobo.sirius.mapper;

import com.fwdrobo.sirius.entity.permission.OrgStorageUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 组织维度存储用量 Mapper。
 */
@Mapper
public interface OrgStorageUsageMapper {

    /**
     * 初始化组织用量行（若已存在则忽略）。
     */
    int initIfAbsent(@Param("orgId") UUID orgId);

    /**
     * 查询组织已用存储容量。
     */
    Long selectUsedStorageBytes(@Param("orgId") UUID orgId);

    /**
     * 增加组织已用存储容量。
     */
    int addUsedStorageBytes(@Param("orgId") UUID orgId, @Param("delta") long delta);

    /**
     * 按组织配额原子预占存储容量，超限时不更新。
     */
    int reserveStorageIfWithinQuota(@Param("orgId") UUID orgId, @Param("delta") long delta);

    /**
     * 扣减组织已用存储容量，最小不低于0。
     */
    int reduceUsedStorageBytes(@Param("orgId") UUID orgId, @Param("delta") long delta);

    /**
     * 查询组织维度资源用量。
     */
    OrgStorageUsage selectByOrgId(@Param("orgId") UUID orgId);

    /**
     * 增加运行时资源占用。
     */
    int addRuntimeUsage(
            @Param("orgId") UUID orgId,
            @Param("gpuDelta") int gpuDelta,
            @Param("cpuDelta") BigDecimal cpuDelta,
            @Param("memoryMbDelta") int memoryMbDelta
    );

    /**
     * 按配额原子预占运行时资源。
     */
    int reserveRuntimeUsageIfWithinQuota(
            @Param("orgId") UUID orgId,
            @Param("gpuDelta") int gpuDelta,
            @Param("cpuDelta") BigDecimal cpuDelta,
            @Param("memoryMbDelta") int memoryMbDelta
    );

    /**
     * 释放运行时资源占用，最小不低于0。
     */
    int reduceRuntimeUsage(
            @Param("orgId") UUID orgId,
            @Param("gpuDelta") int gpuDelta,
            @Param("cpuDelta") BigDecimal cpuDelta,
            @Param("memoryMbDelta") int memoryMbDelta
    );
}
