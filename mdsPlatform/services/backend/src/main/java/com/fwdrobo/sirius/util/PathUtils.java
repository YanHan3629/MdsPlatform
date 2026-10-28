package com.fwdrobo.sirius.util;

import java.util.regex.Pattern;

public class PathUtils {
    private PathUtils() {
    }

    // 路径白名单（校验到时前导 / 已被截去）：a/b/c
    private static final Pattern PATH_ALLOWED_PATTERN = Pattern.compile("^[\\p{L}\\p{M}\\p{N}._-]+(?:/[\\p{L}\\p{M}\\p{N}._-]+)*$");

    // 校验路径
    public static String normalizePath(String p) {
        // 基础拦截：空值、非法字符、Windows 分隔符
        if (p == null || p.isBlank() || !p.startsWith("/") || p.indexOf('\0') >= 0 || p.contains("\\") || p.contains("//")) {
            throw ExceptionUtils.badRequest("illegal path");
        }

        String pathBody = p.substring(1);
        if (pathBody.length() > 512) {
            throw ExceptionUtils.badRequest("path too long");
        }
        if (pathBody.isBlank() || !PATH_ALLOWED_PATTERN.matcher(pathBody).matches()) {
            throw ExceptionUtils.badRequest("illegal path");
        }

        for (String part : pathBody.split("/")) {
            if (".".equals(part) || "..".equals(part)) {
                throw ExceptionUtils.badRequest("illegal path");
            }
            if (part.length() > 255) {
                throw ExceptionUtils.badRequest("path segment too long");
            }
        }

        // Logical artifact paths are platform independent.  The whitelist and
        // explicit dot-segment checks above make filesystem resolution
        // unnecessary and avoid Windows converting '/' to '\\'.
        return "/" + pathBody;
    }

    // 校验相对路径：a/b/c.jpg
    public static String normalizeRelativePath(String p) {
        if (p == null) {
            throw ExceptionUtils.badRequest("illegal relative path");
        }

        String candidate = p.trim().replace('\\', '/');
        while (candidate.startsWith("/")) {
            candidate = candidate.substring(1);
        }

        if (candidate.isBlank() || candidate.indexOf('\0') >= 0 || candidate.contains("//")) {
            throw ExceptionUtils.badRequest("illegal relative path");
        }
        if (candidate.length() > 512) {
            throw ExceptionUtils.badRequest("relative path too long");
        }
        if (!PATH_ALLOWED_PATTERN.matcher(candidate).matches()) {
            throw ExceptionUtils.badRequest("illegal relative path");
        }

        for (String part : candidate.split("/")) {
            if (".".equals(part) || "..".equals(part)) {
                throw ExceptionUtils.badRequest("illegal relative path");
            }
            if (part.length() > 255) {
                throw ExceptionUtils.badRequest("path segment too long");
            }
        }

        return candidate;
    }

    // 组合目录基路径和相对路径，得到最终逻辑路径
    public static String resolveChildPath(String basePath, String relativePath) {
        String normalizedBase = normalizePath(basePath);
        String normalizedRelative = normalizeRelativePath(relativePath);
        String combined = "/" + normalizedRelative;
        if (!"/".equals(normalizedBase)) {
            combined = normalizedBase + "/" + normalizedRelative;
        }
        return normalizePath(combined);
    }
}
