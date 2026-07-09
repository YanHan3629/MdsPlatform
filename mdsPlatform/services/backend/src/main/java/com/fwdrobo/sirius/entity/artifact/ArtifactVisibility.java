package com.fwdrobo.sirius.entity.artifact;

import java.util.Locale;

public enum ArtifactVisibility {
    PRIVATE,
    INTERNAL,
    PUBLIC;

    public static ArtifactVisibility fromString(String s) {
        if (s == null || s.isBlank()) {
            return PRIVATE; // 与 DB 默认对齐
        }
        try {
            return ArtifactVisibility.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid visibility: " + s);
        }
    }
}
