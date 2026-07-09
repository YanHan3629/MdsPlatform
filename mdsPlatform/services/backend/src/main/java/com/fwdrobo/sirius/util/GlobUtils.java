package com.fwdrobo.sirius.util;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.regex.Pattern;

public final class GlobUtils {
    private GlobUtils() {}

    private static final Pattern LEADING_SLASHES_PATTERN = Pattern.compile("^/+");

    /**
     * Build a glob matcher using Java standard library.
     * Notes:
     * - Manifest paths are usually like "/a/b/c.txt". We strip leading '/' so it is treated as a relative path.
     * - We reject '\' to keep path semantics consistent.
     */
    public static PathMatcher parseGlob(String glob) {
        if (glob == null) return null;
        String g = glob.trim();
        if (g.isEmpty()) return null;

        if (g.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("glob 不允许包含反斜杠 \\");
        }

        g = LEADING_SLASHES_PATTERN.matcher(g).replaceFirst("");

        // Java PathMatcher requires "glob:" prefix
        return FileSystems.getDefault().getPathMatcher("glob:" + g);
    }

    public static boolean matches(PathMatcher matcher, String path) {
        if (matcher == null) return true;         // no glob => match all
        if (path == null) return false;

        if (path.indexOf('\\') >= 0) return false; // stay strict

        // normalize: "/a/b.txt" -> "a/b.txt"
        String p = path.startsWith("/") ? path.substring(1) : path;

        // Use Paths.get so matcher can match by path segments
        return matcher.matches(Paths.get(p));
    }
}
