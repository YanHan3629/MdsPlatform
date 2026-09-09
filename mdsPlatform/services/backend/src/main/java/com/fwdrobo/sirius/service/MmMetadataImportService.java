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
import com.fwdrobo.sirius.mapper.MmAssetMapper;
import com.fwdrobo.sirius.mapper.MmAssetTextMapper;
import com.fwdrobo.sirius.util.DataFileFormatClassifier;
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
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class MmMetadataImportService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ASSET_UPSERT_BATCH_SIZE = 1000;
    private static final int ASSET_TEXT_INSERT_BATCH_SIZE = 1000;
    private static final Set<String> BUSINESS_FORMATS = Set.of(
            "JPG", "JPEG", "PNG", "SVG", "TXT", "CSV", "JSON", "JSONL", "XLSX", "PDF", "OBJ", "STEP");

    private final FileService fileService;
    private final MmAssetMapper mmAssetMapper;
    private final MmAssetTextMapper mmAssetTextMapper;

    public MmMetadataImportService(FileService fileService,
                                   MmAssetMapper mmAssetMapper,
                                   MmAssetTextMapper mmAssetTextMapper) {
        this.fileService = fileService;
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

        CocoMetadata coco = loadOptionalCocoMetadata(dataset, version, files);
        List<MmAsset> assets = new ArrayList<>();
        List<MmAssetText> texts = new ArrayList<>();
        int imageCount = 0;

        for (FileResp file : files) {
            String logicalPath = PathUtils.normalizePath(file.getLogicalPath());
            if (coco.annotationsPath() != null && coco.annotationsPath().equals(logicalPath)) {
                continue;
            }
            DataFileFormatClassifier.Classification classification =
                    DataFileFormatClassifier.classify(logicalPath, file.getContentType());
            if (!BUSINESS_FORMATS.contains(classification.format())) {
                continue;
            }

            String fileName = baseName(logicalPath);
            CocoImage image = coco.imagesByFileName().get(fileName);
            UUID assetId = MmDeterministicIdUtils.assetId(version.getVersionId(), logicalPath);
            MmAsset asset = new MmAsset();
            asset.setAssetId(assetId);
            asset.setDatasetVersionId(version.getVersionId());
            asset.setFileId(file.getFileId());
            asset.setAssetType(classification.mediaType());
            asset.setLogicalPath(logicalPath);
            asset.setFileName(fileName);
            asset.setSourceAssetCode(image == null ? logicalPath : String.valueOf(image.imageId()));
            asset.setSizeBytes(file.getSizeBytes());
            asset.setContentType(file.getContentType());
            asset.setWidth(image == null ? null : image.width());
            asset.setHeight(image == null ? null : image.height());
            asset.setStatus("ACTIVE");
            Map<String, Object> assetMeta = new LinkedHashMap<>();
            assetMeta.put("source", image == null ? "DATASET_FILE" : "COCO");
            assetMeta.put("sourceFormat", classification.format());
            assetMeta.put("storageCategory", classification.storageCategory());
            if (coco.annotationsPath() != null) {
                assetMeta.put("annotationsPath", coco.annotationsPath());
            }
            asset.setMeta(JsonbTypeHandler.toJsonNode(assetMeta));
            assets.add(asset);
            if ("IMAGE".equals(classification.mediaType())) {
                imageCount++;
            }

            List<String> captions = image == null
                    ? List.of()
                    : coco.captionsByImageId().getOrDefault(image.imageId(), List.of());
            int sequence = 1;
            for (String caption : captions) {
                MmAssetText text = new MmAssetText();
                text.setAssetTextId(UUID.nameUUIDFromBytes(
                        (assetId + ":caption:" + sequence + ":" + caption).getBytes(StandardCharsets.UTF_8)));
                text.setAssetId(assetId);
                text.setTextRole("CAPTION");
                text.setSeqNo(sequence++);
                text.setLanguageCode(detectLanguage(caption));
                text.setContent(caption);
                text.setSourceType("IMPORTED");
                text.setMeta(JsonbTypeHandler.toJsonNode(Map.of("source", "captions_json")));
                texts.add(text);
            }
        }

        if (assets.isEmpty()) {
            throw ExceptionUtils.badRequest("当前版本不包含可构建统一描述的业务文件");
        }

        mmAssetMapper.deleteByDatasetVersionId(version.getVersionId());
        batchUpsertAssets(assets);
        if (!texts.isEmpty()) {
            batchInsertAssetTexts(texts);
        }

        version.setImageCount((long) imageCount);
        version.setTextCount((long) texts.size());
        version.setSampleCount((long) assets.size());
        if ("DRAFT".equals(version.getVersionStatus())) {
            version.setVersionStatus("UPLOADING");
        }

        return new ImportSummary(coco.annotationsPath(), assets.size(), imageCount, texts.size());
    }

    private CocoMetadata loadOptionalCocoMetadata(MmDataset dataset,
                                                  MmDatasetVersion version,
                                                  List<FileResp> files) throws Exception {
        for (FileResp file : files) {
            String logicalPath = PathUtils.normalizePath(file.getLogicalPath());
            if (!logicalPath.toLowerCase().endsWith(".json")) {
                continue;
            }
            JsonNode root;
            try (var stream = fileService.downloadContent(
                    dataset.getRawRepoId(), version.getRawCommitId(), logicalPath)) {
                root = MAPPER.readTree(stream.inputStream());
            } catch (Exception exception) {
                log.debug("skip non-readable JSON while looking for optional COCO metadata: {}", logicalPath);
                continue;
            }
            JsonNode images = root == null ? null : root.get("images");
            JsonNode annotations = root == null ? null : root.get("annotations");
            if (images == null || !images.isArray() || annotations == null || !annotations.isArray()) {
                continue;
            }

            Map<Long, String> imageIdToName = new HashMap<>();
            Map<String, CocoImage> imagesByFileName = new HashMap<>();
            for (JsonNode image : images) {
                if (!image.hasNonNull("id") || !image.hasNonNull("file_name")) {
                    continue;
                }
                long imageId = image.get("id").asLong();
                String fileName = baseName(image.get("file_name").asText());
                imageIdToName.put(imageId, fileName);
                imagesByFileName.put(fileName, new CocoImage(
                        imageId,
                        image.hasNonNull("width") ? image.get("width").asInt() : null,
                        image.hasNonNull("height") ? image.get("height").asInt() : null));
            }
            Map<Long, List<String>> captionsByImageId = new LinkedHashMap<>();
            for (JsonNode annotation : annotations) {
                if (!annotation.hasNonNull("image_id") || !annotation.hasNonNull("caption")) {
                    continue;
                }
                long imageId = annotation.get("image_id").asLong();
                if (!imageIdToName.containsKey(imageId)) {
                    continue;
                }
                String caption = annotation.get("caption").asText().trim();
                if (!caption.isBlank()) {
                    captionsByImageId.computeIfAbsent(imageId, ignored -> new ArrayList<>()).add(caption);
                }
            }
            return new CocoMetadata(logicalPath, imagesByFileName, captionsByImageId);
        }
        return new CocoMetadata(null, Map.of(), Map.of());
    }

    private String baseName(String logicalPath) {
        String normalized = PathUtils.normalizePath(logicalPath);
        int index = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        return index >= 0 ? normalized.substring(index + 1) : normalized;
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
        for (int from = 0; from < assets.size(); from += ASSET_UPSERT_BATCH_SIZE) {
            int to = Math.min(from + ASSET_UPSERT_BATCH_SIZE, assets.size());
            mmAssetMapper.upsertBatch(assets.subList(from, to));
        }
        log.info("metadata import assets upserted in batches: total={}, batchSize={}",
                assets.size(), ASSET_UPSERT_BATCH_SIZE);
    }

    private void batchInsertAssetTexts(List<MmAssetText> texts) {
        for (int from = 0; from < texts.size(); from += ASSET_TEXT_INSERT_BATCH_SIZE) {
            int to = Math.min(from + ASSET_TEXT_INSERT_BATCH_SIZE, texts.size());
            mmAssetTextMapper.insertBatch(texts.subList(from, to));
        }
        log.info("metadata import asset texts inserted in batches: total={}, batchSize={}",
                texts.size(), ASSET_TEXT_INSERT_BATCH_SIZE);
    }

    private record CocoMetadata(String annotationsPath,
                                Map<String, CocoImage> imagesByFileName,
                                Map<Long, List<String>> captionsByImageId) {
    }

    private record CocoImage(long imageId, Integer width, Integer height) {
    }

    public record ImportSummary(String annotationsPath, int assetCount, int imageCount, int textCount) {
    }
}
