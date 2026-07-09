package com.fwdrobo.sirius.dto.permission;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 组织分页查询参数。
 */
public record OrgQuery(
        /**
         * 页码（从 1 开始）。
         */
        @Min(1) Integer page,
        /**
         * 每页数量。
         */
        @Min(1) @Max(100) Integer size
) {
    /**
     * 构造器，设置默认分页参数。
     */
    public OrgQuery {
        page = page == null ? 1 : page;
        size = size == null ? 20 : size;
    }
}
