package com.fwdrobo.sirius.util;

import java.nio.file.Path;
import java.util.regex.Pattern;

public class PathUtils {
    private PathUtils() {
    }

    // 路径白名单（校验到时前导 / 已被截去）：a/b/c
    private static final Pattern PATH_ALLOWED_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*$");
    private static final Path SANDBOX_ROOT = Path.of("/sandbox_root");

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

        // 把 "/a/b" 变成 "a/b", resolve 后变成 "/sandbox_root/a/b"
        Path resolved = SANDBOX_ROOT.resolve(pathBody).normalize();

        // 越界检查：如果 normalize 之后跑到了 sandbox 之外，说明有 ../ 攻击
        if (!resolved.startsWith(SANDBOX_ROOT)) {
            throw ExceptionUtils.badRequest("illegal path");
        }

        // 还原路径
        String result = resolved.toString().substring(SANDBOX_ROOT.toString().length());
        return result.isEmpty() ? "/" : result;
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

        Path resolved = SANDBOX_ROOT.resolve(candidate).normalize();
        if (!resolved.startsWith(SANDBOX_ROOT)) {
            throw ExceptionUtils.badRequest("illegal relative path");
        }

        return resolved.toString().substring(SANDBOX_ROOT.toString().length() + 1);
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