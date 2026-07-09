package com.fwdrobo.sirius.util;

import java.util.regex.Pattern;

/**
 * 密码复杂度校验工具。
 */
public final class PasswordValidator {
    private static final Pattern ALLOWED_CHARS_PATTERN = Pattern.compile("^[A-Za-z0-9\\-_@*#$%&]+$");
    private static final Pattern UPPER_PATTERN = Pattern.compile("[A-Z]");
    private static final Pattern LOWER_PATTERN = Pattern.compile("[a-z]");
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d");
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("[-_@*#$%&]");

    private PasswordValidator() {
    }

    public static void validate(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        if (password.length() < 8) {
            throw new IllegalArgumentException("密码至少需要8位");
        }
        if (!ALLOWED_CHARS_PATTERN.matcher(password).matches()) {
            throw new IllegalArgumentException("密码仅允许包含字母、数字和符号(-_@*#$%&)");
        }

        int categories = 0;
        if (UPPER_PATTERN.matcher(password).find()) {
            categories++;
        }
        if (LOWER_PATTERN.matcher(password).find()) {
            categories++;
        }
        if (DIGIT_PATTERN.matcher(password).find()) {
            categories++;
        }
        if (SYMBOL_PATTERN.matcher(password).find()) {
            categories++;
        }
        if (categories < 2) {
            throw new IllegalArgumentException("密码需至少包含大写字母、小写字母、数字、符号(-_@*#$%&)中的任意两类");
        }
    }
}
