package com.fwdrobo.sirius.dto;

import java.util.List;

public record PageRes<T>(
        /** 
         * 总元素数
         */
        long totalItemsCount,
        /**
         * 总页数
         */
        int totalPages,
        /**
         * 当前页码（1-based）
         */
        int page,
        /**
         * 当前页大小
         */
        int size,
        /**
         * 当前页元素列表
         */
        List<T> items
) {}
