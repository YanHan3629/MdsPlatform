package com.fwdrobo.sirius.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.dto.file.FileResp;
import com.fwdrobo.sirius.dto.file.ListFilesQuery;
import com.fwdrobo.sirius.entity.mm.MmAsset;
import com.fwdrobo.sirius.entity.mm.MmAssetText;
import com.fwdrobo.sirius.entity.mm.MmDataset;
import com.fwdrobo.sirius.entity.mm.MmDatasetVersion;
import com.fwdrobo.sirius.handler.JsonbTypeHandler;
import com.fwdrobo.sirius.mapper.ArtifactFileMapper;
import com.fwdrobo.sirius.mapper.MmAssetMapper;
import com.fwdrobo.sirius.mapper.MmAssetTextMapper;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.MmDeterministicIdUtils;
import com.fwdrobo.sirius.util.PathUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
public class MmMetadataImportService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ASSET_UPSERT_BATCH_SIZE = 1000;
    private static final int ASSET_TEXT_INSERT_BATCH_SIZE = 1000;

    private final FileService fileService;
    private final ArtifactFileMapper artifactFileMapper;
    private final MmAssetMapper mmAssetMapper;
    private final MmAssetTextMapper mmAssetTextMapper;

    public MmMetadataImportService(FileService fileService,
                                   ArtifactFileMapper artifactFileMapper,
                                   MmAssetMapper mmAssetMapper,
                                   MmAssetTextMapper mmAssetTextMapper) {
        this.fileService = fileService;
        this.artifactFileMapper = artifactFileMapper;
        this.mmAssetMapper = mmAssetMapper;
        this.mmAssetTextMapper = mmAssetTextMapper;
    }

    @Transactional
    public ImportSummary importFromDatasetVersion(MmDataset dataset, MmDatasetVersion version) throws Exception {
        List<FileResp> files = fileService.listFiles(ListFilesQuery.builder()
                .repoId(dataset.getRawRepoId())
                .commitId(version.getRawCommitId())
                .pageIdx(1)
                .pageSize(20000)
                .build()).getFileRespList();

        if (files == null || files.isEmpty()) {
            throw ExceptionUtils.badRequest("当前版本没有可导入的文件");
        }

        Map<String, FileResp> filesByPath = new HashMap<>();
        Map<String, String> basenameToPath = new HashMap<>();
        String annotationsPath = null;
        for (FileResp file : files) {
            filesByPath.put(file.getLogicalPath(), file);
            String logicalPath = file.getLogicalPath();
            String baseName = baseName(logicalPath);
            basenameToPath.putIfAbsent(baseName, logicalPath);
            String lower = logicalPath.toLowerCase();
            if (annotationsPath == null && lower.endsWith(".json") && lower.contains("caption")) {
                annotationsPath = logicalPath;
            }
        }

        if (annotationsPath == null) {
            throw ExceptionUtils.badRequest("未找到 captions json，请确认已上传 captions_val2017.json");
        }

        JsonNode root;
        try (var stream = fileService.downloadContent(dataset.getRawRepoId(), version.getRawCommitId(), annotationsPath)) {
            root = MAPPER.readTree(stream.inputStream());
        }

        JsonNode images = root.get("images");
        JsonNode annotations = root.get("annotations");
        if (images == null || !images.isArray() || annotations == null || !annotations.isArray()) {
            throw ExceptionUtils.badRequest("captions json 不是标准 COCO 格式");
        }

        Map<Long, JsonNode> imageMap = new LinkedHashMap<>();
        for (JsonNode image : images) {
            if (image.get("id") != null) {
                imageMap.put(image.get("id").asLong(), image);
            }
        }

        Map<Long, List<String>> imageIdToCaptions = new LinkedHashMap<>();
        for (JsonNode ann : annotations) {
            if (ann.get("image_id") == null) continue;
            long imageId = ann.get("image_id").asLong();
            String caption = ann.hasNonNull("caption") ? ann.get("caption").asText() : null;
            if (caption == null || caption.isBlank()) continue;
            imageIdToCaptions.computeIfAbsent(imageId, ignored -> new ArrayList<>()).add(caption.trim());
        }

        List<MmAsset> assets = new ArrayList<>();
        List<MmAssetText> texts = new ArrayList<>();

        for (Map.Entry<Long, JsonNode> entry : imageMap.entrySet()) {
            long imageId = entry.getKey();
            JsonNode image = entry.getValue();
            String fileName = image.hasNonNull("file_name") ? image.get("file_name").asText() : null;
            if (fileName == null || fileName.isBlank()) continue;
            String resolvedPath = resolveImageLogicalPath(fileName, basenameToPath);
            if (resolvedPath == null) {
                log.warn("skip image because logical path not found, fileName={}", fileName);
                continue;
            }
            UUID assetId = MmDeterministicIdUtils.assetId(version.getVersionId(), resolvedPath);
            FileResp file = artifactFileMapper.selectByCommitIdAndPath(version.getRawCommitId(), PathUtils.normalizePath(resolvedPath));
            MmAsset asset = new MmAsset();
            asset.setAssetId(assetId);
            asset.setDatasetVersionId(version.getVersionId());
            asset.setFileId(file == null ? null : file.getFileId());
            asset.setAssetType("IMAGE");
            asset.setLogicalPath(PathUtils.normalizePath(resolvedPath));
            asset.setFileName(fileName);
            asset.setSourceAssetCode(String.valueOf(imageId));
            asset.setSizeBytes(file == null ? null : file.getSizeBytes());
            asset.setContentType(file == null ? null : file.getContentType());
            asset.setWidth(image.hasNonNull("width") ? image.get("width").asInt() : null);
            asset.setHeight(image.hasNonNull("height") ? image.get("height").asInt() : null);
            asset.setStatus("ACTIVE");
            asset.setMeta(JsonbTypeHandler.toJsonNode(Map.of(
                    "source", "COCO",
                    "imageId", imageId,
                    "annotationsPath", annotationsPath
            )));
            assets.add(asset);

            List<String> captions = imageIdToCaptions.getOrDefault(imageId, List.of());
            int seq = 1;
            for (String caption : captions) {
                MmAssetText text = new MmAssetText();
                text.setAssetTextId(UUID.nameUUIDFromBytes((assetId + ":caption:" + seq + ":" + caption).getBytes(StandardCharsets.UTF_8)));
                text.setAssetId(assetId);
                text.setTextRole("CAPTION");
                text.setSeqNo(seq++);
                text.setLanguageCode(detectLanguage(caption));
                text.setContent(caption);
                text.setSourceType("IMPORTED");
                text.setMeta(JsonbTypeHandler.toJsonNode(Map.of("source", "captions_json")));
                texts.add(text);
            }
        }

        mmAssetMapper.deleteByDatasetVersionId(version.getVersionId());
        if (!assets.isEmpty()) {
            batchUpsertAssets(assets);
        }
        if (!texts.isEmpty()) {
            batchInsertAssetTexts(texts);
        }

        version.setImageCount((long) assets.size());
        version.setTextCount((long) texts.size());
        version.setSampleCount((long) assets.size());
        if ("DRAFT".equals(version.getVersionStatus())) {
            version.setVersionStatus("UPLOADING");
        }

        return new ImportSummary(annotationsPath, assets.size(), texts.size());
    }

    private String resolveImageLogicalPath(String fileName, Map<String, String> basenameToPath) {
        if (fileName.startsWith("/")) {
            return fileName;
        }
        String direct = basenameToPath.get(fileName);
        if (direct != null) return direct;
        String base = baseName(fileName);
        return basenameToPath.get(base);
    }

    private String baseName(String logicalPath) {
        String normalized = PathUtils.normalizePath(logicalPath);
        int idx = normalized.lastIndexOf('/');
        return idx >= 0 ? normalized.substring(idx + 1) : normalized;
    }

    private String detectLanguage(String text) {
        if (text == null || text.isBlank()) return "unknown";
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                return "zh";
            }
        }
        return "en";
    }

    private void batchUpsertAssets(List<MmAsset> assets) {
        int total = assets.size();
        for (int from = 0; from < total; from += ASSET_UPSERT_BATCH_SIZE) {
            int to = Math.min(from + ASSET_UPSERT_BATCH_SIZE, total);
            mmAssetMapper.upsertBatch(assets.subList(from, to));
        }
        log.info("metadata import assets upserted in batches: total={}, batchSize={}", total, ASSET_UPSERT_BATCH_SIZE);
    }

    private void batchInsertAssetTexts(List<MmAssetText> texts) {
        int total = texts.size();
        for (int from = 0; from < total; from += ASSET_TEXT_INSERT_BATCH_SIZE) {
            int to = Math.min(from + ASSET_TEXT_INSERT_BATCH_SIZE, total);
            mmAssetTextMapper.insertBatch(texts.subList(from, to));
        }
        log.info("metadata import asset texts inserted in batches: total={}, batchSize={}", total, ASSET_TEXT_INSERT_BATCH_SIZE);
    }

    public record ImportSummary(String annotationsPath, int imageCount, int textCount) {
    }
}
