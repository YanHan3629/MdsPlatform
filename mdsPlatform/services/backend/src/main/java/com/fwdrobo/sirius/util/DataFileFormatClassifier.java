package com.fwdrobo.sirius.util;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Classifies data-space files by their actual on-disk format.
 *
 * <p>Images and plain text retain their broad storage groups. Every other
 * format gets its own group so that unrelated binary files are not mixed in
 * an opaque "other" directory.</p>
 */
public final class DataFileFormatClassifier {
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "svg");
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt");
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"),
            Map.entry("json", "application/json"),
            Map.entry("jsonl", "application/x-ndjson"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("obj", "model/obj"),
            Map.entry("step", "model/step"),
            Map.entry("parquet", "application/vnd.apache.parquet"),
            Map.entry("index", "application/octet-stream")
    );

    private DataFileFormatClassifier() {
    }

    public static Classification classify(String fileName, String contentType) {
        String extension = extensionOf(fileName);
        if (extension.isBlank()) {
            extension = extensionFromContentType(contentType);
        }

        if (IMAGE_EXTENSIONS.contains(extension)) {
            return new Classification(extension.toUpperCase(Locale.ROOT), "image", "IMAGE");
        }
        if (TEXT_EXTENSIONS.contains(extension)) {
            return new Classification("TXT", "text", "TEXT");
        }
        if ("index".equals(extension)) {
            return new Classification("FAISS", "faiss", "FAISS");
        }
        if (!extension.isBlank()) {
            String format = extension.toUpperCase(Locale.ROOT);
            return new Classification(format, extension, format);
        }

        // Extensionless objects are kept deterministic without recreating an
        // ambiguous "other" category.
        return new Classification("BINARY", "binary", "BINARY");
    }

    public static String resolveContentType(String fileName, String suppliedContentType) {
        String supplied = suppliedContentType == null ? "" : suppliedContentType.trim();
        if (!supplied.isBlank() && !"application/octet-stream".equalsIgnoreCase(supplied)) {
            return supplied;
        }
        return CONTENT_TYPES.getOrDefault(extensionOf(fileName),
                supplied.isBlank() ? "application/octet-stream" : supplied);
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String normalized = fileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        int dot = normalized.lastIndexOf('.');
        if (dot <= slash || dot == normalized.length() - 1) {
            return "";
        }
        String extension = normalized.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extension.matches("[a-z0-9]{1,24}") ? extension : "";
    }

    private static String extensionFromContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        String type = contentType.toLowerCase(Locale.ROOT);
        return switch (type) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/svg+xml" -> "svg";
            case "text/plain" -> "txt";
            case "text/csv" -> "csv";
            case "application/json" -> "json";
            case "application/x-ndjson", "application/jsonl" -> "jsonl";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            case "application/pdf" -> "pdf";
            case "model/obj" -> "obj";
            case "model/step" -> "step";
            case "application/vnd.apache.parquet" -> "parquet";
            default -> "";
        };
    }

    public record Classification(String format, String storageCategory, String mediaType) {
    }
}
