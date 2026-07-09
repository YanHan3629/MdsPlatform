package com.fwdrobo.sirius.entity.permission;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * License 配额策略实体。
 */
@Getter
@Setter
public class LicenseQuotaPolicy {
    private String licenseType;
    private Long storageQuotaBytes;
    private Integer maxGpu;
    private BigDecimal maxCpu;
    private Integer maxMemoryMb;
    private Integer maxRuntimeSeconds;
    private Integer status;
    private OffsetDateTime updatedAt;
}
