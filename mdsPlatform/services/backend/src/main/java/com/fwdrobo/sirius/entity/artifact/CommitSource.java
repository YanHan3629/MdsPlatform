package com.fwdrobo.sirius.entity.artifact;

import java.util.Locale;

/**
 * Commit 来源：
 * - UPLOAD：直接上传
 * - JOB：由平台 Job 产出（训练/导入/转换等）
 * - IMPORT：导入（历史数据/外部系统）
 * - SYSTEM：系统生成
 */
public enum CommitSource {
    UPLOAD,
    JOB,
    IMPORT,
    SYSTEM;

    public static CommitSource fromString(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("commitSource is required");
        }
        try {
            return CommitSource.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid commitSource: " + s + "(UPLOAD, JOB, IMPORT, SYSTEM)");
        }
    }
}
