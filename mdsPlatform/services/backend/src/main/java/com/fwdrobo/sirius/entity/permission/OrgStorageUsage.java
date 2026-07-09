package com.fwdrobo.sirius.entity.permission;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 组织维度资源用量实体。
 */
@Getter
@Setter
public class OrgStorageUsage {
    private UUID orgId;
    private Long usedStorageBytes;
    private Integer usedGpu;
    private BigDecimal usedCpu;
    private Integer usedMemoryMb;
    private OffsetDateTime updatedAt;
}
