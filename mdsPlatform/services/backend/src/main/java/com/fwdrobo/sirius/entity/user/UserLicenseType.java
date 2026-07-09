package com.fwdrobo.sirius.entity.user;

import com.fwdrobo.sirius.util.ExceptionUtils;

import java.util.Locale;

/**
 * 用户授权类型枚举。
 */
public enum UserLicenseType {
    TRIAL,
    FORMAL;

    /**
     * 解析授权类型字符串，空值按 FORMAL 处理。
     */
    public static UserLicenseType fromNullable(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return FORMAL;
        }
        try {
            return UserLicenseType.valueOf(rawValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ExceptionUtils.badRequest("licenseType 仅支持 TRIAL 或 FORMAL");
        }
    }
}