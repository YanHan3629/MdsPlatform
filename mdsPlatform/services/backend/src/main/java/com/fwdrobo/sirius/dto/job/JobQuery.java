package com.fwdrobo.sirius.dto.job;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 设备查询参数
 */
public record JobQuery(
        @Min(1) Integer page,
        @Min(1) @Max(100) Integer size,
        String jobName,
        String jobCategory,
        String image
) {
    /**
     * 构造器，设置默认值
     */
    public JobQuery {
        page = page == null ? 1 : page;
        size = size == null ? 20 : size;
    }
}
