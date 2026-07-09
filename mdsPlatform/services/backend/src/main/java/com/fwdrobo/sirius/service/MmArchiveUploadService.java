package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.dto.mm.MmUploadArchiveErrorResp;
import com.fwdrobo.sirius.dto.mm.MmUploadArchiveResp;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.PathUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

@Service
public class MmArchiveUploadService {

    private static final long UNKNOWN_SIZE_READ_LIMIT_BYTES = 64L * 1024 * 1024;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "bmp", "gif", "webp", "tif", "tiff"
    );

    private final FileService fileService;

    public MmArchiveUploadService(FileService fileService) {
        this.fileService = fileService;
    }

    public MmUploadArchiveResp uploadArchive(UUID repoId,
                                             UUID commitId,
                                             String basePath,
                                             boolean imagesOnly,
                                             int maxDetailItems,
                                             MultipartFile archive) throws Exception {
        if (archive == null || archive.isEmpty()) {
            throw ExceptionUtils.badRequest("archive 不能为空");
        }

        String archiveName = archive.getOriginalFilename();
        if (archiveName == null || archiveName.isBlank()) {
            archiveName = "archive.zip";
        }
        if (!archiveName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw ExceptionUtils.badRequest("仅支持 .zip 压缩包");
        }

        String normalizedBasePath = PathUtils.normalizePath(basePath);
        Path tempZip = Files.createTempFile("mm-archive-upload-", ".zip");
        try {
            archive.transferTo(tempZip);
            return processZip(repoId, commitId, normalizedBasePath, imagesOnly, maxDetailItems, archiveName, tempZip);
        } finally {
            Files.deleteIfExists(tempZip);
        }
    }

    private MmUploadArchiveResp processZip(UUID repoId,
                                           UUID commitId,
                                           String basePath,
                                           boolean imagesOnly,
                                           int maxDetailItems,
                                           String archiveName,
                                           Path tempZip) throws Exception {
        int totalEntries = 0;
        int uploadedFiles = 0;
        int skippedFiles = 0;
        int failedFiles = 0;
        List<String> skippedItems = new ArrayList<>();
        List<MmUploadArchiveErrorResp> failedItems = new ArrayList<>();
        Set<String> resolvedPaths = new HashSet<>();

        try (ZipFile zipFile = new ZipFile(tempZip.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                totalEntries++;

                String rawEntryName = entry.getName();
                String normalizedRelativePath;
                try {
                    normalizedRelativePath = PathUtils.normalizeRelativePath(rawEntryName);
                } catch (RuntimeException ex) {
                    failedFiles++;
                    addFailed(failedItems, maxDetailItems, rawEntryName, messageFrom(ex));
                    continue;
                }

                if (imagesOnly && !isImage(normalizedRelativePath)) {
                    skippedFiles++;
                    addSkipped(skippedItems, maxDetailItems, normalizedRelativePath);
                    continue;
                }

                String finalPath;
                try {
                    finalPath = PathUtils.resolveChildPath(basePath, normalizedRelativePath);
                } catch (RuntimeException ex) {
                    failedFiles++;
                    addFailed(failedItems, maxDetailItems, normalizedRelativePath, messageFrom(ex));
                    continue;
                }

                if (!resolvedPaths.add(finalPath)) {
                    failedFiles++;
                    addFailed(failedItems, maxDetailItems, normalizedRelativePath, "duplicate path in archive: " + finalPath);
                    continue;
                }

                String contentType = detectContentType(normalizedRelativePath, imagesOnly);
                try {
                    long size = entry.getSize();
                    if (size >= 0) {
                        try (InputStream in = zipFile.getInputStream(entry)) {
                            fileService.uploadContent(repoId, commitId, finalPath, contentType, in, size, null);
                        }
                    } else {
                        byte[] bytes = readUnknownSizeEntry(zipFile, entry);
                        try (InputStream in = new ByteArrayInputStream(bytes)) {
                            fileService.uploadContent(repoId, commitId, finalPath, contentType, in, bytes.length, null);
                        }
                    }
                    uploadedFiles++;
                } catch (Exception ex) {
                    failedFiles++;
                    addFailed(failedItems, maxDetailItems, normalizedRelativePath, messageFrom(ex));
                }
            }
        } catch (ZipException ex) {
            throw ExceptionUtils.badRequest("zip 文件损坏或格式不合法: " + ex.getMessage());
        }

        return new MmUploadArchiveResp(
                basePath,
                archiveName,
                totalEntries,
                uploadedFiles,
                skippedFiles,
                failedFiles,
                skippedItems,
                failedItems
        );
    }

    private String detectContentType(String relativePath, boolean imagesOnly) {
        String ext = extensionOf(relativePath);
        if ("jpg".equals(ext) || "jpeg".equals(ext)) return "image/jpeg";
        if ("png".equals(ext)) return "image/png";
        if ("gif".equals(ext)) return "image/gif";
        if ("bmp".equals(ext)) return "image/bmp";
        if ("webp".equals(ext)) return "image/webp";
        if ("tif".equals(ext) || "tiff".equals(ext)) return "image/tiff";
        if (imagesOnly) return null;
        return URLConnection.guessContentTypeFromName(relativePath);
    }

    private boolean isImage(String relativePath) {
        return IMAGE_EXTENSIONS.contains(extensionOf(relativePath));
    }

    private String extensionOf(String path) {
        int idx = path.lastIndexOf('.');
        if (idx < 0 || idx == path.length() - 1) {
            return "";
        }
        return path.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private byte[] readUnknownSizeEntry(ZipFile zipFile, ZipEntry entry) throws IOException {
        try (InputStream in = zipFile.getInputStream(entry);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0L;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > UNKNOWN_SIZE_READ_LIMIT_BYTES) {
                    throw new IllegalArgumentException("zip entry size unknown and exceeds 64MB: " + entry.getName());
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private void addSkipped(List<String> skippedItems, int maxDetailItems, String relativePath) {
        if (skippedItems.size() < maxDetailItems) {
            skippedItems.add(relativePath);
        }
    }

    private void addFailed(List<MmUploadArchiveErrorResp> failedItems, int maxDetailItems, String relativePath, String message) {
        if (failedItems.size() < maxDetailItems) {
            failedItems.add(new MmUploadArchiveErrorResp(relativePath, message));
        }
    }

    private String messageFrom(Throwable ex) {
        Throwable curr = ex;
        while (curr.getCause() != null) {
            curr = curr.getCause();
        }
        String msg = curr.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = ex.getMessage();
        }
        if (msg == null || msg.isBlank()) {
            msg = "unknown error";
        }
        return msg;
    }
}
