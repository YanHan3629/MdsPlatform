package com.fwdrobo.sirius.entity.artifact;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum CommitStatus {
    DRAFT,
    PUBLISHED,
    DEPRECATED;


    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static CommitStatus fromString(String s) {
        if (s == null || s.isBlank()) {
            return DRAFT;
        }
        try {
            return CommitStatus.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid commitStatus: " + s);
        }
    }
}
