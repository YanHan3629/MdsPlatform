package com.fwdrobo.sirius.dataspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.service.MinioService;
import com.fwdrobo.sirius.util.DataFileFormatClassifier;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class DataSpaceFlowService {
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String PLATFORM_NAME = "产业链数据空间平台";
    private static final String CHAIN_OWNER_NAME = "链主企业";
    private static final Path DEFAULT_VAL2017_DIR = defaultPath("DATA_SPACE_VAL2017_DIR", "../../../dataset/val2017");
    private static final Path DEFAULT_VAL2017_CAPTIONS = defaultPath("DATA_SPACE_VAL2017_CAPTIONS", "../../../dataset/captions_val2017.json");
    private static final String[][] BUSINESS_DOMAIN_ITEMS = new String[][]{
            {"marketing", "营销", "20"},
            {"service", "服务", "16"},
            {"retail", "零售", "11"},
            {"customer-service", "客服", "6"},
            {"channel-sales", "渠道销售", "18"},
            {"procurement", "采购", "15"},
            {"manufacturing", "制造", "11"},
            {"rd", "研发", "11"},
            {"quality", "质量", "14"},
            {"logistics", "物流", "6"},
            {"supply-chain", "供应链", "12"},
            {"finance", "财务", "17"},
            {"planning", "企划", "7"},
            {"legal", "法务", "10"},
            {"hr", "人力", "12"},
            {"strategy", "战略", "11"},
            {"it", "IT", "16"}
    };

    private final Object lock = new Object();
    private final ObjectMapper objectMapper;
    private final MinioService minioService;
    private final Map<String, Map<String, Object>> spaces = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> sources = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> catalogs = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> datasets = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> products = new LinkedHashMap<>();
    private final Map<String, List<Map<String, Object>>> sourceFiles = new LinkedHashMap<>();
    private final Map<String, List<Map<String, Object>>> sourceSamples = new LinkedHashMap<>();
    private final Map<String, List<UploadedJson>> sourceJsonFiles = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> intents = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> contracts = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> deliveries = new LinkedHashMap<>();
    private final List<Map<String, Object>> events = new ArrayList<>();

    @Autowired
    public DataSpaceFlowService(ObjectMapper objectMapper, ObjectProvider<MinioService> minioServiceProvider) {
        this.objectMapper = objectMapper;
        this.minioService = minioServiceProvider.getIfAvailable();
    }

    DataSpaceFlowService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.minioService = null;
    }

    @PostConstruct
    public void init() {
        synchronized (lock) {
            if (!spaces.isEmpty()) {
                return;
            }
            seedChainOwnerLocked();
            seedProviderSpacesLocked();
            seedPublishedProductLocked(
                    "quality",
                    "fridge-quality",
                    "智能冰箱生产质检图文数据源",
                    "冰箱制造质量资源目录",
                    "冰箱生产质检治理数据集",
                    "冰箱生产质检图文数据产品",
                    "包含冰箱产线外观图片、压缩机运行曲线、质检缺陷文本和批次追溯元数据",
                    "冰箱,质检,缺陷,图片,文本,产线",
                    "IMAGE_TEXT",
                    98.2,
                    48600,
                    39200,
                    9400);
            seedPublishedProductLocked(
                    "supply-chain",
                    "fridge-supply",
                    "冰箱核心零部件供应链数据源",
                    "冰箱供应链资源目录",
                    "冰箱供应链履约治理数据集",
                    "冰箱零部件供应链履约数据产品",
                    "覆盖压缩机、蒸发器、门封条等关键零部件的订单、库存、交付和异常协同数据",
                    "冰箱,供应链,零部件,库存,交付",
                    "TEXT",
                    96.7,
                    32800,
                    0,
                    32800);
            seedPublishedProductLocked(
                    "service",
                    "fridge-service",
                    "冰箱售后服务工单语音文本数据源",
                    "冰箱服务资源目录",
                    "冰箱服务工单治理数据集",
                    "冰箱售后服务工单数据产品",
                    "汇聚冰箱报修工单、客服转写文本、维修图片和用户反馈标签，支持故障预测与服务优化",
                    "冰箱,售后,服务,工单,客服,语音,文本",
                    "AUDIO_TEXT",
                    95.8,
                    76500,
                    9800,
                    66700);

            Map<String, Object> intent = createIntentLocked(mapOf(
                    "productId", "prd-fridge-quality",
                    "consumerName", CHAIN_OWNER_NAME,
                    "purpose", "用于智能冰箱质量追溯、缺陷识别模型训练和产线良率分析",
                    "expectedSendTime", now().plusMinutes(30).format(DISPLAY_TIME)));
            createContractLocked(mapOf(
                    "intentId", intent.get("intentId"),
                    "contractName", "冰箱质检数据流通合约",
                    "sendTime", now().plusMinutes(30).format(DISPLAY_TIME),
                    "receiverEndpoint", "s3://haier-chain-owner/fridge-quality/",
                    "qualityThreshold", 96));
            addEventLocked("系统初始化", "已加载产业链数据空间演示数据，供给方工作台可直接注册、接入、治理、建索引和发布产品", "READY");
        }
    }

    public Map<String, Object> snapshot() {
        synchronized (lock) {
            autoDispatchDueContractsLocked();
            return mapOf(
                    "platformName", PLATFORM_NAME,
                    "chainOwner", chainOwnerInfo(),
                    "businessDomains", businessDomains(),
                    "kpis", kpisLocked(),
                    "spaces", cloneList(spaces),
                    "sources", cloneList(sources),
                    "catalogs", cloneList(catalogs),
                    "datasets", cloneList(datasets),
                    "products", cloneList(products),
                    "intents", cloneList(intents),
                    "contracts", cloneList(contracts),
                    "deliveries", cloneList(deliveries),
                    "events", cloneEvents());
        }
    }

    public List<Map<String, Object>> listBusinessDomains() {
        return businessDomains();
    }

    public List<Map<String, Object>> listSpaces() {
        synchronized (lock) {
            return cloneList(spaces);
        }
    }

    public Map<String, Object> registerSpace(Map<String, Object> body) {
        synchronized (lock) {
            String orgName = str(body, "orgName", "未命名组织");
            String role = str(body, "role", "PROVIDER").toUpperCase(Locale.ROOT);
            Map<String, Object> business = null;
            if ("PROVIDER".equals(role)) {
                business = findBusiness(body);
            }
            String id = id("sp");
            Map<String, Object> space = mapOf(
                    "spaceId", id,
                    "orgName", orgName,
                    "role", role,
                    "authStatus", "PENDING_AUTH",
                    "description", str(body, "description", ""),
                    "createdAt", nowText());
            if (business != null) {
                space.put("businessCode", business.get("code"));
                space.put("businessName", business.get("name"));
            }
            spaces.put(id, space);
            addEventLocked("注册认证", orgName + " 已提交" + (business == null ? "" : business.get("name") + "业务") + "数据空间注册申请", "PENDING_AUTH");
            return cloneMap(space);
        }
    }

    public Map<String, Object> verifySpace(String spaceId) {
        synchronized (lock) {
            Map<String, Object> space = mustGet(spaces, spaceId, "空间不存在");
            space.put("authStatus", "VERIFIED");
            space.put("verifiedAt", nowText());
            addEventLocked("认证完成", str(space, "orgName", spaceId) + " 已通过数据空间认证", "VERIFIED");
            return cloneMap(space);
        }
    }

    public List<Map<String, Object>> listSources() {
        synchronized (lock) {
            return cloneList(sources);
        }
    }

    public Map<String, Object> createSource(Map<String, Object> body) {
        synchronized (lock) {
            String spaceId = str(body, "spaceId", "");
            Map<String, Object> space = mustGet(spaces, spaceId, "请先选择有效的数据空间");
            if (!"VERIFIED".equals(space.get("authStatus"))) {
                throw new IllegalArgumentException("数据空间尚未认证，不能接入数据资源");
            }
            if (!"PROVIDER".equals(String.valueOf(space.get("role")))) {
                throw new IllegalArgumentException("只有产业链业务供给方可以创建数据资源");
            }
            Map<String, Object> business = requireBusinessDomain(body);
            String businessCode = String.valueOf(business.get("code"));
            String spaceBusinessCode = str(space, "businessCode", businessCode);
            if (!businessCode.equals(spaceBusinessCode)) {
                throw new IllegalArgumentException("所选数据空间与业务域不一致，请选择 " + business.get("name") + " 对应的供给方空间");
            }
            Map<String, Object> source = mapOf(
                    "sourceId", id("src"),
                    "spaceId", spaceId,
                    "providerName", space.get("orgName"),
                    "businessCode", businessCode,
                    "businessName", business.get("name"),
                    "chainOwner", CHAIN_OWNER_NAME,
                    "sourceName", str(body, "sourceName", "未命名数据资源"),
                    "sourceType", str(body, "sourceType", "LOCAL_UPLOAD"),
                    "modalityType", str(body, "modalityType", "IMAGE_TEXT"),
                    "uploadMethods", uploadMethods(body),
                    "description", str(body, "description", ""),
                    "fileCount", 0,
                    "totalBytes", 0L,
                    "totalSize", "0 KB",
                    "status", "CONNECTED",
                    "createdAt", nowText());
            sources.put(String.valueOf(source.get("sourceId")), source);
            addEventLocked("数据源接入", source.get("businessName") + "业务已创建数据资源：" + source.get("sourceName"), "CONNECTED");
            return cloneMap(source);
        }
    }

    public Map<String, Object> uploadFiles(String sourceId, MultipartFile[] files) {
        if (minioService == null) {
            throw new IllegalStateException("MinIO 服务未配置，无法上传数据资源");
        }
        Map<String, Object> source;
        synchronized (lock) {
            source = cloneMap(mustGet(sources, sourceId, "数据源不存在"));
        }
        long uploadBytes = 0L;
        List<Map<String, Object>> fileItems = new ArrayList<>();
        List<UploadedJson> jsonFiles = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                try {
                    String fileName = normalizeObjectPath(file.getOriginalFilename());
                    String contentType = resolveContentType(fileName, file.getContentType());
                    DataFileFormatClassifier.Classification classification =
                            DataFileFormatClassifier.classify(fileName, contentType);
                    String objectKey = "data-space/sources/" + sourceId + "/"
                            + classification.storageCategory() + "/" + fileName;
                    long size = file.getSize();
                    if (isJsonFile(fileName, contentType)) {
                        byte[] bytes = file.getBytes();
                        minioService.putObject(objectKey, new ByteArrayInputStream(bytes), bytes.length, contentType);
                        jsonFiles.add(new UploadedJson(fileName, bytes));
                    } else {
                        try (InputStream in = file.getInputStream()) {
                            minioService.putObject(objectKey, in, size, contentType);
                        }
                    }
                    uploadBytes += size;
                    fileItems.add(mapOf(
                            "fileName", fileName,
                            "objectKey", objectKey,
                            "bucket", minioService.getBucket(),
                            "contentType", contentType,
                            "fileFormat", classification.format(),
                            "storageCategory", classification.storageCategory(),
                            "mediaType", classification.mediaType(),
                            "size", size,
                            "uploadedAt", nowText()));
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                } catch (Exception ex) {
                    throw new IllegalStateException("上传到 MinIO 失败: " + ex.getMessage(), ex);
                }
            }
        }
        synchronized (lock) {
            Map<String, Object> liveSource = mustGet(sources, sourceId, "数据源不存在");
            mergeSourceFilesLocked(sourceId, fileItems);
            mergeSourceJsonFilesLocked(sourceId, jsonFiles);
            List<Map<String, Object>> records = sourceFiles.getOrDefault(sourceId, List.of());
            List<Map<String, Object>> samples = rebuildSourceSamplesLocked(sourceId);
            addSourceFilesLocked(liveSource, fileItems.size(), uploadBytes);
            liveSource.put("sampleCount", samples.size());
            liveSource.put("imageCount", records.stream().filter(item -> "IMAGE".equals(item.get("mediaType"))).count());
            liveSource.put("textFileCount", records.stream().filter(item -> "TEXT".equals(item.get("mediaType"))).count());
            liveSource.put("formatCounts", formatCounts(records));
            liveSource.put("captionCount", samples.stream().filter(item -> item.get("caption") != null).count());
            liveSource.put("minioBucket", minioService.getBucket());
            liveSource.put("storagePrefix", "data-space/sources/" + sourceId + "/");
            liveSource.put("storagePrefixes", storagePrefixes(sourceId, records));
            liveSource.put("lastUploadAt", nowText());
            addEventLocked("文件上传", liveSource.get("sourceName") + " 已上传 " + fileItems.size() + " 个对象到 MinIO", "UPLOADED");
            return mapOf(
                    "source", cloneMap(liveSource),
                    "files", fileItems,
                    "sampleCount", samples.size(),
                    "message", "数据资源已按文件格式分类存储到 MinIO，资源清单和可搜索样本索引已更新");
        }
    }

    public Map<String, Object> attachLocalDataset(String sourceId, Map<String, Object> body) {
        try {
            synchronized (lock) {
                Map<String, Object> source = mustGet(sources, sourceId, "数据源不存在");
                DatasetStats stats = scanDataset(
                        Path.of(str(body, "imageDir", DEFAULT_VAL2017_DIR.toString())),
                        Path.of(str(body, "captionsJson", DEFAULT_VAL2017_CAPTIONS.toString())));
                attachDatasetStatsLocked(source, stats);
                addEventLocked("文件上传", source.get("sourceName") + " 已接入本地目录 " + stats.imageDir(), "UPLOADED");
                return mapOf("source", cloneMap(source), "stats", stats.toMap(), "message", "本地数据集已接入数据源");
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("读取本地数据集失败: " + ex.getMessage());
        }
    }

    public List<Map<String, Object>> listCatalogs() {
        synchronized (lock) {
            return cloneList(catalogs);
        }
    }

    public Map<String, Object> createCatalog(String sourceId, Map<String, Object> body) {
        synchronized (lock) {
            return cloneMap(createCatalogLocked(sourceId, body));
        }
    }

    public List<Map<String, Object>> listDatasets() {
        synchronized (lock) {
            return cloneList(datasets);
        }
    }

    public Map<String, Object> governSource(String sourceId, Map<String, Object> body) {
        synchronized (lock) {
            Map<String, Object> source = mustGet(sources, sourceId, "数据资源不存在");
            String catalogId = findCatalogIdBySource(sourceId);
            if (catalogId == null) {
                catalogId = String.valueOf(createCatalogLocked(sourceId, mapOf(
                        "catalogName", str(source, "sourceName", "资源") + "目录")).get("catalogId"));
            }
            int sourceFileCount = intValue(source.get("fileCount"));
            int imageCount = intValue(body.getOrDefault("imageCount", source.getOrDefault("imageCount", sourceFileCount)));
            int textCount = intValue(body.getOrDefault("textCount", source.getOrDefault("captionCount", Math.max(1, sourceFileCount / 8))));
            int sampleCount = intValue(body.getOrDefault("sampleCount", source.getOrDefault("sampleCount", Math.max(1000, imageCount))));
            double quality = doubleValue(body.get("qualityScore"), sourceFileCount == 0 ? 95.0 : 99.1);
            Map<String, Object> dataset = mapOf(
                    "datasetId", id("ds"),
                    "sourceId", sourceId,
                    "catalogId", catalogId,
                    "spaceId", source.get("spaceId"),
                    "businessCode", source.get("businessCode"),
                    "businessName", source.get("businessName"),
                    "datasetName", str(body, "datasetName", str(source, "sourceName", "数据资源") + "治理数据集"),
                    "version", str(body, "version", "v1.0"),
                    "modalityType", source.get("modalityType"),
                    "governanceStatus", "READY",
                    "indexStatus", "PENDING",
                    "qualityScore", quality,
                    "sampleCount", sampleCount,
                    "imageCount", imageCount,
                    "textCount", textCount,
                    "description", str(body, "description", "已完成清洗、去重、质量抽检和标注标准化治理"),
                    "createdAt", nowText());
            copyIfPresent(source, dataset, "imageDir", "captionsJson", "annotationCount", "annotatedImageCount", "minioBucket", "storagePrefix");
            datasets.put(String.valueOf(dataset.get("datasetId")), dataset);
            addEventLocked("治理加工", dataset.get("datasetName") + " 已形成治理数据集，质量评分 " + quality, "READY");
            return cloneMap(dataset);
        }
    }

    public Map<String, Object> buildIndex(String datasetId, Map<String, Object> body) {
        synchronized (lock) {
            Map<String, Object> dataset = mustGet(datasets, datasetId, "数据集不存在");
            if (!"READY".equals(dataset.get("governanceStatus"))) {
                throw new IllegalArgumentException("数据集尚未完成治理，不能构建索引");
            }
            dataset.put("indexStatus", "READY");
            dataset.put("indexType", str(body, "indexType", "VECTOR"));
            dataset.put("modelName", str(body, "modelName", "CLIP-Chinese"));
            dataset.put("indexAssetCount", intValue(dataset.get("sampleCount")));
            dataset.put("indexedAt", nowText());
            addEventLocked("索引构建", dataset.get("datasetName") + " 已完成 " + dataset.get("indexType") + " 索引构建", "READY");
            return cloneMap(dataset);
        }
    }

    public Map<String, Object> publishProduct(String datasetId, Map<String, Object> body) {
        synchronized (lock) {
            Map<String, Object> dataset = mustGet(datasets, datasetId, "数据集不存在");
            if (!"READY".equals(dataset.get("indexStatus"))) {
                throw new IllegalArgumentException("数据集索引未 READY，不能发布数据产品");
            }
            Map<String, Object> source = sources.get(String.valueOf(dataset.get("sourceId")));
            Map<String, Object> product = mapOf(
                    "productId", id("prd"),
                    "datasetId", datasetId,
                    "sourceId", dataset.get("sourceId"),
                    "spaceId", dataset.get("spaceId"),
                    "businessCode", dataset.get("businessCode"),
                    "businessName", dataset.get("businessName"),
                    "productName", str(body, "productName", str(dataset, "datasetName", "数据集") + "产品"),
                    "providerName", source == null ? "未知供给方" : source.get("providerName"),
                    "consumerName", CHAIN_OWNER_NAME,
                    "modalityType", dataset.get("modalityType"),
                    "qualityScore", dataset.get("qualityScore"),
                    "deliveryRule", str(body, "deliveryRule", "按合约发送时间自动推送给链主企业"),
                    "rights", str(body, "rights", "合约授权 / 可追溯交付 / 仅限产业链场景"),
                    "description", str(body, "description", str(dataset, "description", "")),
                    "status", "PUBLISHED",
                    "registeredAt", nowText());
            copyIfPresent(dataset, product, "sampleCount", "imageCount", "textCount", "annotationCount", "imageDir", "captionsJson", "minioBucket", "storagePrefix");
            products.put(String.valueOf(product.get("productId")), product);
            addEventLocked("产品发布", product.get("productName") + " 已发布并注册到产业链数据空间", "PUBLISHED");
            return cloneMap(product);
        }
    }

    public List<Map<String, Object>> productSamples(String productId, int limit) {
        Map<String, Object> product;
        Map<String, Object> dataset;
        synchronized (lock) {
            product = cloneMap(mustGet(products, productId, "数据产品不存在"));
            dataset = cloneMap(mustGet(datasets, String.valueOf(product.get("datasetId")), "数据集不存在"));
        }
        Path imageDir = Path.of(str(dataset, "imageDir", ""));
        Path captionsJson = Path.of(str(dataset, "captionsJson", ""));
        if (!Files.isDirectory(imageDir) || !Files.isRegularFile(captionsJson)) {
            synchronized (lock) {
                return sourceSamples.getOrDefault(String.valueOf(product.get("sourceId")), List.of()).stream()
                        .limit(Math.max(1, Math.min(limit, 100)))
                        .map(item -> sampleForProduct(productId, item))
                        .toList();
            }
        }
        try {
            return readCaptionSamples(productId, imageDir, captionsJson, Math.max(1, Math.min(limit, 100)));
        } catch (IOException ex) {
            throw new IllegalArgumentException("读取数据集样本失败: " + ex.getMessage());
        }
    }

    public DataSpaceFile openProductFile(String productId, String fileName) throws Exception {
        Map<String, Object> product;
        Map<String, Object> dataset;
        synchronized (lock) {
            product = cloneMap(mustGet(products, productId, "数据产品不存在"));
            dataset = cloneMap(mustGet(datasets, String.valueOf(product.get("datasetId")), "数据集不存在"));
        }
        Path imageDir = Path.of(str(dataset, "imageDir", "")).toAbsolutePath().normalize();
        Path file = imageDir.resolve(fileName == null ? "" : fileName).normalize();
        if (Files.isDirectory(imageDir) && file.startsWith(imageDir) && Files.isRegularFile(file) && isImageFile(file)) {
            return new DataSpaceFile(file.getFileName().toString(), contentType(file), Files.size(file), Files.newInputStream(file));
        }
        if (minioService == null) {
            throw new IllegalStateException("MinIO 服务未配置，无法读取文件");
        }
        Map<String, Object> record;
        synchronized (lock) {
            record = sourceFiles.getOrDefault(String.valueOf(product.get("sourceId")), List.of()).stream()
                    .filter(item -> normalizeObjectPath(str(item, "fileName", "")).equals(normalizeObjectPath(fileName))
                            || normalizeObjectPath(str(item, "fileName", "")).endsWith("/" + normalizeObjectPath(fileName)))
                    .findFirst()
                    .map(this::cloneMap)
                    .orElseThrow(() -> new IllegalArgumentException("文件不存在或不允许访问: " + fileName));
        }
        String objectKey = str(record, "objectKey", "");
        return new DataSpaceFile(fileName, str(record, "contentType", "application/octet-stream"), longValue(record.get("size")), minioService.getObject(objectKey));
    }

    public List<Map<String, Object>> searchProductContent(String productId, String keyword, int limit) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        int max = Math.max(1, Math.min(limit, 100));
        synchronized (lock) {
            Map<String, Object> product = mustGet(products, productId, "数据产品不存在");
            return sourceSamples.getOrDefault(String.valueOf(product.get("sourceId")), List.of()).stream()
                    .filter(item -> kw.isBlank() || sampleText(item).contains(kw))
                    .limit(max)
                    .map(item -> sampleForProduct(productId, item))
                    .toList();
        }
    }

    public Map<String, Object> syncProductToMinio(String productId, Map<String, Object> body) throws Exception {
        if (minioService == null) {
            throw new IllegalStateException("MinIO 服务未配置，无法同步数据集内容");
        }
        Map<String, Object> product;
        Map<String, Object> dataset;
        synchronized (lock) {
            product = cloneMap(mustGet(products, productId, "数据产品不存在"));
            dataset = cloneMap(mustGet(datasets, String.valueOf(product.get("datasetId")), "数据集不存在"));
        }
        Path imageDir = Path.of(str(dataset, "imageDir", ""));
        Path captionsJson = Path.of(str(dataset, "captionsJson", ""));
        if (!Files.isDirectory(imageDir) || !Files.isRegularFile(captionsJson)) {
            throw new IllegalArgumentException("该产品没有可同步的本地数据集文件");
        }
        int maxFiles = intValue(body.getOrDefault("maxFiles", Integer.MAX_VALUE));
        if (maxFiles <= 0) {
            maxFiles = Integer.MAX_VALUE;
        }
        String prefix = str(body, "prefix", "data-space/products/" + productId).replaceAll("^/+", "").replaceAll("/+$", "");
        int uploaded = 0;
        long bytes = 0L;
        String captionsKey = prefix + "/annotations/" + captionsJson.getFileName();
        try (InputStream in = Files.newInputStream(captionsJson)) {
            minioService.putObject(captionsKey, in, Files.size(captionsJson), "application/json");
            uploaded++;
            bytes += Files.size(captionsJson);
        }
        try (Stream<Path> stream = Files.list(imageDir)) {
            List<Path> images = stream
                    .filter(Files::isRegularFile)
                    .filter(DataSpaceFlowService::isImageFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .limit(maxFiles)
                    .toList();
            for (Path image : images) {
                String objectKey = prefix + "/images/" + image.getFileName();
                long size = Files.size(image);
                try (InputStream in = Files.newInputStream(image)) {
                    minioService.putObject(objectKey, in, size, contentType(image));
                }
                uploaded++;
                bytes += size;
            }
        }
        synchronized (lock) {
            Map<String, Object> liveProduct = mustGet(products, productId, "数据产品不存在");
            liveProduct.put("minioStatus", "SYNCED");
            liveProduct.put("minioBucket", minioService.getBucket());
            liveProduct.put("minioPrefix", prefix);
            liveProduct.put("minioObjectCount", uploaded);
            liveProduct.put("minioBytes", bytes);
            liveProduct.put("minioSyncedAt", nowText());
            Map<String, Object> liveDataset = datasets.get(String.valueOf(liveProduct.get("datasetId")));
            if (liveDataset != null) {
                liveDataset.put("minioStatus", "SYNCED");
                liveDataset.put("minioBucket", minioService.getBucket());
                liveDataset.put("minioPrefix", prefix);
            }
            addEventLocked("MinIO 同步", liveProduct.get("productName") + " 已同步 " + uploaded + " 个对象到 MinIO: " + prefix, "READY");
        }
        return mapOf(
                "productId", productId,
                "bucket", minioService.getBucket(),
                "prefix", prefix,
                "objectCount", uploaded,
                "bytes", bytes,
                "size", humanBytes(bytes),
                "status", "SYNCED");
    }

    public Map<String, Object> runVal2017Flow(Map<String, Object> body) {
        Path imageDir = Path.of(str(body, "imageDir", DEFAULT_VAL2017_DIR.toString()));
        Path captionsJson = Path.of(str(body, "captionsJson", DEFAULT_VAL2017_CAPTIONS.toString()));
        String datasetName = str(body, "datasetName", "COCO Val2017 图文治理数据集");
        try {
            DatasetStats stats = scanDataset(imageDir, captionsJson);
            synchronized (lock) {
                Map<String, Object> space = registerSpaceLocked(mapOf(
                        "orgName", str(body, "orgName", "COCO 视觉数据供给组织"),
                        "role", "PROVIDER",
                        "businessCode", str(body, "businessCode", "quality"),
                        "description", "基于 COCO val2017 图像和 captions 标注的图文数据供给方"));
                verifySpace(String.valueOf(space.get("spaceId")));

                Map<String, Object> source = createSource(mapOf(
                        "spaceId", space.get("spaceId"),
                        "businessCode", space.get("businessCode"),
                        "sourceName", str(body, "sourceName", "COCO Val2017 图文数据源"),
                        "sourceType", "LOCAL_DIRECTORY",
                        "modalityType", "IMAGE_TEXT",
                        "uploadMethods", List.of("本地目录", "标注JSON"),
                        "description", "COCO val2017 图片目录与 captions_val2017.json 标注文件"));
                attachDatasetStatsLocked(sources.get(String.valueOf(source.get("sourceId"))), stats);
                source = cloneMap(sources.get(String.valueOf(source.get("sourceId"))));
                addEventLocked("文件上传", "COCO val2017 已接入：" + stats.imageCount() + " 张图片、" + stats.annotationCount() + " 条文本标注", "UPLOADED");

                Map<String, Object> catalog = createCatalogLocked(String.valueOf(source.get("sourceId")), mapOf(
                        "catalogName", str(body, "catalogName", "COCO Val2017 图文资源目录"),
                        "visibility", "产业链数据空间内可检索",
                        "tags", "COCO,val2017,image,caption,vision-language"));
                Map<String, Object> dataset = governSource(String.valueOf(source.get("sourceId")), mapOf(
                        "datasetName", datasetName,
                        "version", str(body, "version", "v2017.val"),
                        "qualityScore", doubleValue(body.get("qualityScore"), 99.1),
                        "sampleCount", stats.imageCount(),
                        "imageCount", stats.imageCount(),
                        "textCount", stats.annotationCount(),
                        "description", "由 COCO val2017 图片与 caption 标注治理形成的图文检索数据集"));
                Map<String, Object> index = buildIndex(String.valueOf(dataset.get("datasetId")), mapOf(
                        "indexType", str(body, "indexType", "VECTOR"),
                        "modelName", str(body, "modelName", "CLIP-Chinese")));
                Map<String, Object> product = publishProduct(String.valueOf(dataset.get("datasetId")), mapOf(
                        "productName", str(body, "productName", "COCO Val2017 图文检索数据产品"),
                        "rights", "合约授权 / 可追溯交付 / 仅限视觉检索与模型评测场景",
                        "description", "面向图文检索、视觉语言模型评测和样例验证的 COCO val2017 数据产品"));

                return mapOf(
                        "ok", true,
                        "message", "val2017 数据空间供给方闭环验证通过",
                        "stats", stats.toMap(),
                        "space", cloneMap(space),
                        "source", source,
                        "catalog", cloneMap(catalog),
                        "dataset", cloneMap(index),
                        "product", cloneMap(product),
                        "steps", List.of("REGISTERED", "VERIFIED", "SOURCE_CONNECTED", "FILES_ATTACHED", "CATALOG_CREATED", "GOVERNED", "INDEX_READY", "PRODUCT_PUBLISHED"));
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("val2017 验证失败: " + ex.getMessage());
        }
    }

    public List<Map<String, Object>> searchProducts(String keyword, String modalityType, String businessCode) {
        synchronized (lock) {
            String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
            String modality = modalityType == null ? "" : modalityType.trim().toUpperCase(Locale.ROOT);
            String business = businessCode == null ? "" : businessCode.trim();
            return products.values().stream()
                    .filter(p -> "PUBLISHED".equals(p.get("status")))
                    .filter(p -> modality.isEmpty() || modality.equals(String.valueOf(p.get("modalityType")).toUpperCase(Locale.ROOT)))
                    .filter(p -> business.isEmpty() || business.equals(String.valueOf(p.get("businessCode"))))
                    .filter(p -> kw.isEmpty() || containsProductKeyword(p, kw))
                    .map(this::cloneMap)
                    .toList();
        }
    }

    public List<Map<String, Object>> listProducts() {
        synchronized (lock) {
            return cloneList(products);
        }
    }

    public Map<String, Object> deleteProduct(String productId) {
        synchronized (lock) {
            Map<String, Object> product = mustGet(products, productId, "数据产品不存在");
            product.put("status", "DELETED");
            product.put("deletedAt", nowText());
            int canceledIntents = 0;
            for (Map<String, Object> intent : intents.values()) {
                if (productId.equals(intent.get("productId")) && !"CONTRACTED".equals(intent.get("status"))) {
                    intent.put("status", "CANCELED");
                    intent.put("canceledAt", nowText());
                    canceledIntents++;
                }
            }
            addEventLocked("产品删除", product.get("productName") + " 已从链主消费搜索下架，底层数据源与 MinIO 对象保留", "DELETED");
            Map<String, Object> resp = cloneMap(product);
            resp.put("canceledIntents", canceledIntents);
            return resp;
        }
    }

    public List<Map<String, Object>> listIntents() {
        synchronized (lock) {
            return cloneList(intents);
        }
    }

    public Map<String, Object> createIntent(Map<String, Object> body) {
        synchronized (lock) {
            return cloneMap(createIntentLocked(body));
        }
    }

    public List<Map<String, Object>> listContracts() {
        synchronized (lock) {
            autoDispatchDueContractsLocked();
            return cloneList(contracts);
        }
    }

    public Map<String, Object> createContract(Map<String, Object> body) {
        synchronized (lock) {
            Map<String, Object> contract = createContractLocked(body);
            autoDispatchOneLocked(contract);
            return cloneMap(contract);
        }
    }

    public Map<String, Object> dispatchContract(String contractId) {
        synchronized (lock) {
            Map<String, Object> contract = mustGet(contracts, contractId, "合约不存在");
            contract.put("sendTime", nowText());
            autoDispatchOneLocked(contract);
            return cloneMap(contract);
        }
    }

    public List<Map<String, Object>> listDeliveries() {
        synchronized (lock) {
            return cloneList(deliveries);
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void autoDispatchDueContracts() {
        synchronized (lock) {
            autoDispatchDueContractsLocked();
        }
    }

    private void seedChainOwnerLocked() {
        Map<String, Object> consumerSpace = mapOf(
                "spaceId", "sp-haier-consumer",
                "orgName", CHAIN_OWNER_NAME,
                "role", "CONSUMER",
                "authStatus", "VERIFIED",
                "businessCode", "chain-owner",
                "businessName", "链主企业",
                "description", "家电产业链链主企业，作为数据消费方统一发起数据消费需求",
                "createdAt", nowText());
        spaces.put(String.valueOf(consumerSpace.get("spaceId")), consumerSpace);
    }

    private void seedProviderSpacesLocked() {
        for (String[] item : BUSINESS_DOMAIN_ITEMS) {
            String code = item[0];
            String name = item[1];
            String id = "sp-provider-" + code;
            Map<String, Object> space = mapOf(
                    "spaceId", id,
                    "orgName", "产业链" + name + "业务中心",
                    "role", "PROVIDER",
                    "authStatus", "VERIFIED",
                    "businessCode", code,
                    "businessName", name,
                    "description", "家电产业链" + name + "业务数据供给方，面向" + CHAIN_OWNER_NAME + "提供可信数据资源",
                    "createdAt", nowText());
            spaces.put(id, space);
        }
    }

    private void seedPublishedProductLocked(String businessCode,
                                            String key,
                                            String sourceName,
                                            String catalogName,
                                            String datasetName,
                                            String productName,
                                            String description,
                                            String tags,
                                            String modalityType,
                                            double qualityScore,
                                            int sampleCount,
                                            int imageCount,
                                            int textCount) {
        Map<String, Object> domain = businessDomainByCode(businessCode);
        if (domain == null) {
            return;
        }
        String businessName = String.valueOf(domain.get("name"));
        String spaceId = "sp-provider-" + businessCode;
        String sourceId = "src-" + key;
        String catalogId = "cat-" + key;
        String datasetId = "ds-" + key + "-v1";
        String productId = "prd-" + key;
        Map<String, Object> space = spaces.get(spaceId);
        String providerName = space == null ? "产业链" + businessName + "业务中心" : String.valueOf(space.get("orgName"));
        long bytes = (long) sampleCount * 256 * 1024;

        Map<String, Object> source = mapOf(
                "sourceId", sourceId,
                "spaceId", spaceId,
                "providerName", providerName,
                "businessCode", businessCode,
                "businessName", businessName,
                "chainOwner", CHAIN_OWNER_NAME,
                "sourceName", sourceName,
                "sourceType", "OBJECT_STORAGE",
                "modalityType", modalityType,
                "uploadMethods", List.of("本地文件", "文件夹批量", "对象存储清单", "接口接入"),
                "description", description,
                "fileCount", Math.max(600, sampleCount / 6),
                "sampleCount", sampleCount,
                "imageCount", imageCount,
                "captionCount", textCount,
                "totalBytes", bytes,
                "totalSize", humanBytes(bytes),
                "status", "CONNECTED",
                "createdAt", nowText());
        sources.put(sourceId, source);

        Map<String, Object> catalog = mapOf(
                "catalogId", catalogId,
                "sourceId", sourceId,
                "spaceId", spaceId,
                "businessCode", businessCode,
                "businessName", businessName,
                "catalogName", catalogName,
                "modalityType", modalityType,
                "visibility", "产业链数据空间内可检索",
                "tags", splitTags(tags),
                "status", "ACTIVE",
                "createdAt", nowText());
        catalogs.put(catalogId, catalog);

        Map<String, Object> dataset = mapOf(
                "datasetId", datasetId,
                "sourceId", sourceId,
                "catalogId", catalogId,
                "spaceId", spaceId,
                "businessCode", businessCode,
                "businessName", businessName,
                "datasetName", datasetName,
                "version", "v2026.06",
                "modalityType", modalityType,
                "governanceStatus", "READY",
                "indexStatus", "READY",
                "qualityScore", qualityScore,
                "sampleCount", sampleCount,
                "imageCount", imageCount,
                "textCount", textCount,
                "description", "面向家电产业链业务，经过去重、字段标准化、质量抽检和索引构建后的治理数据集",
                "createdAt", nowText());
        datasets.put(datasetId, dataset);

        Map<String, Object> product = mapOf(
                "productId", productId,
                "datasetId", datasetId,
                "sourceId", sourceId,
                "spaceId", spaceId,
                "businessCode", businessCode,
                "businessName", businessName,
                "productName", productName,
                "providerName", providerName,
                "consumerName", CHAIN_OWNER_NAME,
                "modalityType", modalityType,
                "qualityScore", qualityScore,
                "sampleCount", sampleCount,
                "imageCount", imageCount,
                "textCount", textCount,
                "deliveryRule", "按合约发送时间自动推送给链主企业",
                "rights", "合约授权 / 可追溯交付 / 仅限家电产业链场景",
                "description", description,
                "status", "PUBLISHED",
                "registeredAt", nowText());
        products.put(productId, product);
    }

    private Map<String, Object> registerSpaceLocked(Map<String, Object> body) {
        String orgName = str(body, "orgName", "未命名组织");
        String role = str(body, "role", "PROVIDER").toUpperCase(Locale.ROOT);
        Map<String, Object> business = "PROVIDER".equals(role) ? requireBusinessDomain(body) : null;
        Map<String, Object> space = mapOf(
                "spaceId", id("sp"),
                "orgName", orgName,
                "role", role,
                "authStatus", "PENDING_AUTH",
                "description", str(body, "description", ""),
                "createdAt", nowText());
        if (business != null) {
            space.put("businessCode", business.get("code"));
            space.put("businessName", business.get("name"));
        }
        spaces.put(String.valueOf(space.get("spaceId")), space);
        addEventLocked("注册认证", orgName + " 已提交数据空间注册申请", "PENDING_AUTH");
        return space;
    }

    private Map<String, Object> createIntentLocked(Map<String, Object> body) {
        String productId = str(body, "productId", "");
        Map<String, Object> product = mustGet(products, productId, "数据产品不存在");
        Map<String, Object> intent = mapOf(
                "intentId", id("intent"),
                "productId", productId,
                "sourceId", product.get("sourceId"),
                "businessCode", product.get("businessCode"),
                "businessName", product.get("businessName"),
                "productName", product.get("productName"),
                "providerName", product.get("providerName"),
                "consumerName", str(body, "consumerName", CHAIN_OWNER_NAME),
                "purpose", str(body, "purpose", "产业链数据消费"),
                "expectedSendTime", str(body, "expectedSendTime", "待确认"),
                "status", "PENDING_CONFIRM",
                "createdAt", nowText());
        intents.put(String.valueOf(intent.get("intentId")), intent);
        addEventLocked("消费意向", intent.get("consumerName") + " 已向 " + intent.get("businessName") + "业务发起消费意向：" + intent.get("productName"), "PENDING_CONFIRM");
        return intent;
    }

    private Map<String, Object> createContractLocked(Map<String, Object> body) {
        String intentId = str(body, "intentId", "");
        Map<String, Object> intent = mustGet(intents, intentId, "消费意向不存在");
        String productId = str(intent, "productId", "");
        Map<String, Object> product = mustGet(products, productId, "数据产品不存在");
        double threshold = doubleValue(body.get("qualityThreshold"), 90.0);
        String sendTimeText = formatTime(parseTime(str(body, "sendTime", nowText())));
        Map<String, Object> contract = mapOf(
                "contractId", id("ct"),
                "contractName", str(body, "contractName", "产业链数据流通合约"),
                "intentId", intentId,
                "productId", productId,
                "sourceId", product.get("sourceId"),
                "datasetId", product.get("datasetId"),
                "businessCode", product.get("businessCode"),
                "businessName", product.get("businessName"),
                "providerName", product.get("providerName"),
                "consumerName", intent.get("consumerName"),
                "productName", product.get("productName"),
                "sendTime", sendTimeText,
                "qualityThreshold", threshold,
                "receiverEndpoint", str(body, "receiverEndpoint", "consumer://haier-chain-owner-inbox"),
                "contractStatus", "ACTIVE",
                "pushStatus", parseTime(sendTimeText).isAfter(now()) ? "SCHEDULED" : "WAITING",
                "createdAt", nowText());
        contracts.put(String.valueOf(contract.get("contractId")), contract);
        intent.put("status", "CONTRACTED");
        intent.put("contractId", contract.get("contractId"));
        addEventLocked("合约确认", contract.get("consumerName") + " 与 " + contract.get("providerName") + " 已确认" + contract.get("businessName") + "业务数据源和发送时间", "ACTIVE");
        return contract;
    }

    private Map<String, Object> createCatalogLocked(String sourceId, Map<String, Object> body) {
        Map<String, Object> source = mustGet(sources, sourceId, "数据资源不存在");
        Map<String, Object> catalog = mapOf(
                "catalogId", id("cat"),
                "sourceId", sourceId,
                "spaceId", source.get("spaceId"),
                "businessCode", source.get("businessCode"),
                "businessName", source.get("businessName"),
                "catalogName", str(body, "catalogName", str(source, "sourceName", "资源目录")),
                "modalityType", source.get("modalityType"),
                "visibility", str(body, "visibility", "产业链数据空间内可检索"),
                "tags", splitTags(str(body, "tags", "家电,数据资源,目录")),
                "status", "ACTIVE",
                "createdAt", nowText());
        catalogs.put(String.valueOf(catalog.get("catalogId")), catalog);
        addEventLocked("资源目录", catalog.get("catalogName") + " 已创建并注册到产业链数据空间目录", "ACTIVE");
        return catalog;
    }

    private DatasetStats scanDataset(Path imageDir, Path captionsJson) throws IOException {
        if (!Files.isDirectory(imageDir)) {
            throw new IOException("图片目录不存在: " + imageDir);
        }
        if (!Files.isRegularFile(captionsJson)) {
            throw new IOException("标注文件不存在: " + captionsJson);
        }
        long imageCount;
        long imageBytes;
        try (Stream<Path> stream = Files.list(imageDir)) {
            List<Path> images = stream
                    .filter(Files::isRegularFile)
                    .filter(DataSpaceFlowService::isImageFile)
                    .toList();
            imageCount = images.size();
            imageBytes = 0L;
            for (Path path : images) {
                imageBytes += Files.size(path);
            }
        }
        JsonNode root = objectMapper.readTree(captionsJson.toFile());
        int jsonImageCount = root.path("images").isArray() ? root.path("images").size() : 0;
        int annotationCount = root.path("annotations").isArray() ? root.path("annotations").size() : 0;
        Set<String> annotatedImageIds = new HashSet<>();
        if (root.path("annotations").isArray()) {
            for (JsonNode annotation : root.path("annotations")) {
                JsonNode imageId = annotation.path("image_id");
                if (!imageId.isMissingNode()) {
                    annotatedImageIds.add(imageId.asText());
                }
            }
        }
        long totalBytes = imageBytes + Files.size(captionsJson);
        return new DatasetStats(
                imageDir.toAbsolutePath().normalize().toString(),
                captionsJson.toAbsolutePath().normalize().toString(),
                (int) imageCount,
                jsonImageCount,
                annotationCount,
                annotatedImageIds.size(),
                totalBytes);
    }

    private List<Map<String, Object>> readCaptionSamples(String productId, Path imageDir, Path captionsJson, int limit) throws IOException {
        JsonNode root = objectMapper.readTree(captionsJson.toFile());
        Map<String, String> fileByImageId = new LinkedHashMap<>();
        if (root.path("images").isArray()) {
            for (JsonNode image : root.path("images")) {
                String imageId = image.path("id").asText("");
                String fileName = image.path("file_name").asText("");
                if (!imageId.isBlank() && !fileName.isBlank()) {
                    fileByImageId.put(imageId, fileName);
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> usedFiles = new HashSet<>();
        if (root.path("annotations").isArray()) {
            for (JsonNode annotation : root.path("annotations")) {
                if (out.size() >= limit) {
                    break;
                }
                String imageId = annotation.path("image_id").asText("");
                String fileName = fileByImageId.get(imageId);
                if (fileName == null || !usedFiles.add(fileName)) {
                    continue;
                }
                Path imagePath = imageDir.resolve(fileName).normalize();
                if (!Files.isRegularFile(imagePath)) {
                    continue;
                }
                out.add(mapOf(
                        "imageId", imageId,
                        "annotationId", annotation.path("id").asText(""),
                        "fileName", fileName,
                        "caption", annotation.path("caption").asText(""),
                        "size", Files.size(imagePath),
                        "previewUrl", "/api/data-space/products/" + productId + "/files/" + fileName));
            }
        }
        return out;
    }

    private void mergeSourceFilesLocked(String sourceId, List<Map<String, Object>> fileItems) {
        List<Map<String, Object>> records = sourceFiles.computeIfAbsent(sourceId, ignored -> new ArrayList<>());
        for (Map<String, Object> fileItem : fileItems) {
            String fileName = normalizeObjectPath(str(fileItem, "fileName", ""));
            records.removeIf(item -> normalizeObjectPath(str(item, "fileName", "")).equals(fileName));
            records.add(fileItem);
        }
    }

    private void mergeSourceJsonFilesLocked(String sourceId, List<UploadedJson> jsonFiles) {
        if (jsonFiles.isEmpty()) {
            return;
        }
        List<UploadedJson> records = sourceJsonFiles.computeIfAbsent(sourceId, ignored -> new ArrayList<>());
        for (UploadedJson jsonFile : jsonFiles) {
            String fileName = normalizeObjectPath(jsonFile.fileName());
            records.removeIf(item -> normalizeObjectPath(item.fileName()).equals(fileName));
            records.add(jsonFile);
        }
    }

    private List<Map<String, Object>> rebuildSourceSamplesLocked(String sourceId) {
        List<Map<String, Object>> samples = new ArrayList<>();
        appendGenericSamples(samples, sourceFiles.getOrDefault(sourceId, List.of()));
        for (UploadedJson jsonFile : sourceJsonFiles.getOrDefault(sourceId, List.of())) {
            appendJsonSamples(sourceId, samples, jsonFile.fileName(), jsonFile.bytes());
        }
        sourceSamples.put(sourceId, samples);
        return samples;
    }

    private void appendGenericSamples(List<Map<String, Object>> samples, List<Map<String, Object>> fileItems) {
        for (Map<String, Object> item : fileItems) {
            String mediaType = str(item, "mediaType", "");
            String fileName = str(item, "fileName", "");
            if ("IMAGE".equals(mediaType)) {
                samples.add(mapOf(
                        "sampleId", id("sample"),
                        "fileName", fileName,
                        "imageFileName", fileName,
                        "objectKey", item.get("objectKey"),
                        "contentType", item.get("contentType"),
                        "fileFormat", item.get("fileFormat"),
                        "storageCategory", item.get("storageCategory"),
                        "caption", str(item, "fileName", ""),
                        "modalityType", "IMAGE"));
            } else if ("TEXT".equals(mediaType)) {
                samples.add(mapOf(
                        "sampleId", id("sample"),
                        "fileName", fileName,
                        "objectKey", item.get("objectKey"),
                        "contentType", item.get("contentType"),
                        "fileFormat", item.get("fileFormat"),
                        "storageCategory", item.get("storageCategory"),
                        "caption", str(item, "fileName", ""),
                        "modalityType", "TEXT"));
            } else {
                samples.add(mapOf(
                        "sampleId", id("sample"),
                        "fileName", fileName,
                        "objectKey", item.get("objectKey"),
                        "contentType", item.get("contentType"),
                        "fileFormat", item.get("fileFormat"),
                        "storageCategory", item.get("storageCategory"),
                        "caption", fileName,
                        "modalityType", mediaType));
            }
        }
    }

    private Map<String, Long> formatCounts(List<Map<String, Object>> records) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> record : records) {
            String format = str(record, "fileFormat", "BINARY");
            counts.put(format, counts.getOrDefault(format, 0L) + 1L);
        }
        return counts;
    }

    private Map<String, String> storagePrefixes(String sourceId, List<Map<String, Object>> records) {
        Map<String, String> prefixes = new LinkedHashMap<>();
        for (Map<String, Object> record : records) {
            String category = str(record, "storageCategory", "binary");
            prefixes.putIfAbsent(category, "data-space/sources/" + sourceId + "/" + category + "/");
        }
        return prefixes;
    }

    private void appendJsonSamples(String sourceId, List<Map<String, Object>> samples, String jsonFileName, byte[] bytes) {
        try {
            JsonNode root = objectMapper.readTree(bytes);
            if (root.path("images").isArray() && root.path("annotations").isArray()) {
                appendCocoCaptionSamples(sourceId, samples, root, jsonFileName);
            } else if (root.isArray()) {
                for (JsonNode item : root) {
                    appendGenericJsonSample(sourceId, samples, item, jsonFileName);
                }
            } else {
                JsonNode rows = root.path("items");
                if (rows.isArray()) {
                    for (JsonNode item : rows) {
                        appendGenericJsonSample(sourceId, samples, item, jsonFileName);
                    }
                }
            }
        } catch (IOException ignored) {
            samples.add(mapOf(
                    "sampleId", id("sample"),
                    "fileName", jsonFileName,
                    "caption", jsonFileName,
                    "modalityType", "TEXT"));
        }
    }

    private void appendCocoCaptionSamples(String sourceId, List<Map<String, Object>> samples, JsonNode root, String jsonFileName) {
        Map<String, String> fileByImageId = new LinkedHashMap<>();
        for (JsonNode image : root.path("images")) {
            String imageId = image.path("id").asText("");
            String fileName = image.path("file_name").asText("");
            if (!imageId.isBlank() && !fileName.isBlank()) {
                fileByImageId.put(imageId, fileName);
            }
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode annotation : root.path("annotations")) {
            String imageId = annotation.path("image_id").asText("");
            String fileName = fileByImageId.get(imageId);
            if (fileName == null) {
                continue;
            }
            String caption = annotation.path("caption").asText("");
            String key = imageId + ":" + caption;
            if (!seen.add(key)) {
                continue;
            }
            Map<String, Object> imageFile = findSourceFile(sourceId, fileName);
            samples.add(mapOf(
                    "sampleId", id("sample"),
                    "imageId", imageId,
                    "annotationId", annotation.path("id").asText(""),
                    "fileName", fileName,
                    "imageFileName", fileName,
                    "objectKey", imageFile == null ? null : imageFile.get("objectKey"),
                    "contentType", imageFile == null ? "image/jpeg" : imageFile.get("contentType"),
                    "caption", caption,
                    "annotationFile", jsonFileName,
                    "modalityType", "IMAGE_TEXT"));
        }
    }

    private void appendGenericJsonSample(String sourceId, List<Map<String, Object>> samples, JsonNode item, String jsonFileName) {
        String fileName = firstText(item, "fileName", "file_name", "image", "imageFile", "path");
        String caption = firstText(item, "caption", "text", "description", "label", "prompt");
        if (caption.isBlank()) {
            caption = item.toString();
        }
        Map<String, Object> imageFile = fileName.isBlank() ? null : findSourceFile(sourceId, fileName);
        samples.add(mapOf(
                "sampleId", id("sample"),
                "fileName", fileName.isBlank() ? jsonFileName : fileName,
                "imageFileName", fileName,
                "objectKey", imageFile == null ? null : imageFile.get("objectKey"),
                "contentType", imageFile == null ? null : imageFile.get("contentType"),
                "caption", caption,
                "annotationFile", jsonFileName,
                "modalityType", imageFile == null ? "TEXT" : "IMAGE_TEXT"));
    }

    private Map<String, Object> findSourceFile(String sourceId, String fileName) {
        String normalized = normalizeObjectPath(fileName);
        return sourceFiles.getOrDefault(sourceId, List.of()).stream()
                .filter(item -> normalizeObjectPath(str(item, "fileName", "")).equals(normalized)
                        || normalizeObjectPath(str(item, "fileName", "")).endsWith("/" + normalized))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> sampleForProduct(String productId, Map<String, Object> item) {
        Map<String, Object> out = cloneMap(item);
        String imageFileName = str(out, "imageFileName", str(out, "fileName", ""));
        if (!imageFileName.isBlank() && "IMAGE".equals(str(out, "modalityType", "IMAGE"))) {
            out.put("previewUrl", productFileUrl(productId, imageFileName));
        } else if (!imageFileName.isBlank() && out.get("objectKey") != null) {
            out.put("previewUrl", productFileUrl(productId, imageFileName));
        }
        return out;
    }

    private String productFileUrl(String productId, String fileName) {
        return "/api/data-space/products/" + productId + "/files?fileName="
                + URLEncoder.encode(fileName, StandardCharsets.UTF_8);
    }

    private String sampleText(Map<String, Object> item) {
        return (item.getOrDefault("caption", "") + " "
                + item.getOrDefault("fileName", "") + " "
                + item.getOrDefault("annotationFile", "") + " "
                + item.getOrDefault("modalityType", "")).toString().toLowerCase(Locale.ROOT);
    }

    private void attachDatasetStatsLocked(Map<String, Object> source, DatasetStats stats) {
        source.put("sourceType", "LOCAL_DIRECTORY");
        source.put("imageDir", stats.imageDir());
        source.put("captionsJson", stats.captionsJson());
        source.put("fileCount", stats.imageCount() + 1);
        source.put("sampleCount", stats.imageCount());
        source.put("imageCount", stats.imageCount());
        source.put("captionCount", stats.annotationCount());
        source.put("annotationCount", stats.annotationCount());
        source.put("jsonImageCount", stats.jsonImageCount());
        source.put("annotatedImageCount", stats.annotatedImageCount());
        source.put("totalBytes", stats.totalBytes());
        source.put("totalSize", humanBytes(stats.totalBytes()));
        source.put("lastUploadAt", nowText());
    }

    private void addSourceFilesLocked(Map<String, Object> source, int fileCount, long bytes) {
        int previousCount = intValue(source.get("fileCount"));
        long previousBytes = longValue(source.get("totalBytes"));
        long totalBytes = previousBytes + bytes;
        source.put("fileCount", previousCount + fileCount);
        source.put("totalBytes", totalBytes);
        source.put("totalSize", humanBytes(totalBytes));
    }

    private void autoDispatchDueContractsLocked() {
        for (Map<String, Object> contract : contracts.values()) {
            autoDispatchOneLocked(contract);
        }
    }

    private void autoDispatchOneLocked(Map<String, Object> contract) {
        String pushStatus = String.valueOf(contract.get("pushStatus"));
        if ("DELIVERED".equals(pushStatus)) {
            return;
        }
        LocalDateTime sendTime = parseTime(str(contract, "sendTime", nowText()));
        if (sendTime.isAfter(now())) {
            contract.put("pushStatus", "SCHEDULED");
            return;
        }
        Map<String, Object> product = products.get(String.valueOf(contract.get("productId")));
        Map<String, Object> dataset = product == null ? null : datasets.get(String.valueOf(product.get("datasetId")));
        double qualityScore = product == null ? 0 : doubleValue(product.get("qualityScore"), 0.0);
        double threshold = doubleValue(contract.get("qualityThreshold"), 90.0);
        if (product == null || dataset == null || !"PUBLISHED".equals(product.get("status")) || !"READY".equals(dataset.get("indexStatus")) || qualityScore < threshold) {
            contract.put("pushStatus", "BLOCKED");
            contract.put("lastError", "产品未发布、索引未完成或质量未达到合约阈值");
            return;
        }
        String deliveryId = id("dlv");
        Map<String, Object> delivery = mapOf(
                "deliveryId", deliveryId,
                "contractId", contract.get("contractId"),
                "productId", contract.get("productId"),
                "productName", contract.get("productName"),
                "providerName", contract.get("providerName"),
                "consumerName", contract.get("consumerName"),
                "businessName", contract.get("businessName"),
                "receiverEndpoint", contract.get("receiverEndpoint"),
                "packageName", "data-package-" + contract.get("productId") + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")),
                "deliveryStatus", "DELIVERED",
                "deliveredAt", nowText());
        deliveries.put(deliveryId, delivery);
        contract.put("pushStatus", "DELIVERED");
        contract.put("deliveredAt", delivery.get("deliveredAt"));
        addEventLocked("自动推送", contract.get("productName") + " 已按合约推送至 " + contract.get("receiverEndpoint"), "DELIVERED");
    }

    private Map<String, Object> kpisLocked() {
        long verifiedSpaces = spaces.values().stream().filter(s -> "VERIFIED".equals(s.get("authStatus"))).count();
        long readyDatasets = datasets.values().stream().filter(d -> "READY".equals(d.get("indexStatus"))).count();
        long totalSamples = datasets.values().stream().mapToLong(d -> intValue(d.get("sampleCount"))).sum();
        long scheduledContracts = contracts.values().stream().filter(c -> "SCHEDULED".equals(c.get("pushStatus"))).count();
        long delivered = deliveries.size();
        double avgQuality = datasets.isEmpty() ? 0.0 : datasets.values().stream().mapToDouble(d -> doubleValue(d.get("qualityScore"), 0.0)).average().orElse(0.0);
        return mapOf(
                "verifiedSpaces", verifiedSpaces,
                "sources", sources.size(),
                "businessDomains", BUSINESS_DOMAIN_ITEMS.length,
                "catalogs", catalogs.size(),
                "readyDatasets", readyDatasets,
                "products", products.size(),
                "contracts", contracts.size(),
                "scheduledContracts", scheduledContracts,
                "deliveries", delivered,
                "totalSamples", totalSamples,
                "avgQuality", Math.round(avgQuality * 10.0) / 10.0);
    }

    private boolean containsProductKeyword(Map<String, Object> product, String keyword) {
        String haystack = (product.get("productName") + " "
                + product.get("providerName") + " "
                + product.get("businessName") + " "
                + product.get("description") + " "
                + product.get("modalityType") + " "
                + product.getOrDefault("imageDir", "") + " "
                + "家电 冰箱 海尔 智家 产业链 COCO val2017 图文 caption").toLowerCase(Locale.ROOT);
        for (String part : keyword.split("[\\s,，+]+")) {
            if (!part.isBlank() && haystack.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> chainOwnerInfo() {
        return mapOf(
                "name", CHAIN_OWNER_NAME,
                "role", "CONSUMER",
                "title", "链主企业 / 数据消费方",
                "count", 43);
    }

    private List<Map<String, Object>> businessDomains() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] item : BUSINESS_DOMAIN_ITEMS) {
            out.add(mapOf(
                    "code", item[0],
                    "name", item[1],
                    "count", intValue(item[2]),
                    "role", "PROVIDER",
                    "owner", CHAIN_OWNER_NAME));
        }
        return out;
    }

    private Map<String, Object> businessDomainByCode(String code) {
        if (code == null) {
            return null;
        }
        for (Map<String, Object> item : businessDomains()) {
            if (code.equals(String.valueOf(item.get("code")))) {
                return item;
            }
        }
        return null;
    }

    private Map<String, Object> businessDomainByName(String name) {
        if (name == null) {
            return null;
        }
        for (Map<String, Object> item : businessDomains()) {
            if (name.equals(String.valueOf(item.get("name")))) {
                return item;
            }
        }
        return null;
    }

    private Map<String, Object> findBusiness(Map<String, Object> body) {
        String code = str(body, "businessCode", "");
        Map<String, Object> domain = businessDomainByCode(code);
        if (domain == null) {
            String name = str(body, "businessName", str(body, "businessDomain", ""));
            domain = businessDomainByName(name);
        }
        return domain;
    }

    private Map<String, Object> requireBusinessDomain(Map<String, Object> body) {
        Map<String, Object> domain = findBusiness(body);
        if (domain == null) {
            throw new IllegalArgumentException("创建数据资源必须选择有效业务域，可选值为：" + allowedBusinessNames());
        }
        return domain;
    }

    private String allowedBusinessNames() {
        List<String> names = new ArrayList<>();
        for (String[] item : BUSINESS_DOMAIN_ITEMS) {
            names.add(item[1]);
        }
        return String.join("、", names);
    }

    private String findCatalogIdBySource(String sourceId) {
        return catalogs.values().stream()
                .filter(c -> sourceId.equals(c.get("sourceId")))
                .map(c -> String.valueOf(c.get("catalogId")))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> mustGet(Map<String, Map<String, Object>> map, String id, String message) {
        Map<String, Object> value = map.get(id);
        if (value == null) {
            throw new IllegalArgumentException(message + ": " + id);
        }
        return value;
    }

    private List<Map<String, Object>> cloneList(Map<String, Map<String, Object>> map) {
        return map.values().stream().map(this::cloneMap).toList();
    }

    private List<Map<String, Object>> cloneEvents() {
        return events.stream()
                .sorted(Comparator.comparing(e -> String.valueOf(e.get("createdAt")), Comparator.reverseOrder()))
                .limit(30)
                .map(this::cloneMap)
                .toList();
    }

    private Map<String, Object> cloneMap(Map<String, Object> map) {
        return new LinkedHashMap<>(map);
    }

    private void addEventLocked(String title, String detail, String status) {
        events.add(mapOf("eventId", id("evt"), "title", title, "detail", detail, "status", status, "createdAt", nowText()));
        if (events.size() > 100) {
            events.remove(0);
        }
    }

    private List<String> uploadMethods(Map<String, Object> body) {
        Object value = body.get("uploadMethods");
        if (value instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }
        return splitTags(str(body, "uploadMethods", "本地文件,文件夹批量,对象存储清单,接口接入"));
    }

    private List<String> splitTags(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : value.split("[,，\\s]+")) {
            if (!item.isBlank()) {
                out.add(item.trim());
            }
        }
        return out;
    }

    private String firstText(JsonNode item, String... keys) {
        for (String key : keys) {
            String value = item.path(key).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String normalizeObjectPath(String value) {
        if (value == null || value.isBlank()) {
            return "unnamed-" + UUID.randomUUID();
        }
        String normalized = value.replace('\\', '/').replaceAll("^/+", "");
        while (normalized.contains("../")) {
            normalized = normalized.replace("../", "");
        }
        return normalized.isBlank() ? "unnamed-" + UUID.randomUUID() : normalized;
    }

    private String resolveContentType(String fileName, String contentType) {
        return DataFileFormatClassifier.resolveContentType(fileName, contentType);
    }

    private boolean isJsonFile(String fileName, String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return type.contains("json") || fileName.toLowerCase(Locale.ROOT).endsWith(".json");
    }

    private String str(Map<String, Object> map, String key, String fallback) {
        Object value = map == null ? null : map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value).trim();
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now();
    }

    private String nowText() {
        return now().format(DISPLAY_TIME);
    }

    private String formatTime(LocalDateTime time) {
        return time.format(DISPLAY_TIME);
    }

    private LocalDateTime parseTime(String text) {
        if (text == null || text.isBlank() || "待确认".equals(text)) {
            return now();
        }
        String normalized = text.trim().replace('T', ' ');
        try {
            return LocalDateTime.parse(normalized, DISPLAY_TIME);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(text.trim());
            } catch (DateTimeParseException ignoredAgain) {
                return now();
            }
        }
    }

    private String humanBytes(long bytes) {
        if (bytes <= 0) {
            return "0 KB";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0);
    }

    private String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Map<String, Object> mapOf(Object... args) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < args.length - 1; i += 2) {
            map.put(String.valueOf(args[i]), args[i + 1]);
        }
        return map;
    }

    private void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String... keys) {
        for (String key : keys) {
            if (from.containsKey(key)) {
                to.put(key, from.get(key));
            }
        }
    }

    private static boolean isImageFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".jpg")
                || fileName.endsWith(".jpeg")
                || fileName.endsWith(".png")
                || fileName.endsWith(".bmp")
                || fileName.endsWith(".webp");
    }

    private static String contentType(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (fileName.endsWith(".png")) {
            return "image/png";
        }
        if (fileName.endsWith(".webp")) {
            return "image/webp";
        }
        if (fileName.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "application/octet-stream";
    }

    private static Path defaultPath(String envName, String fallback) {
        String value = System.getenv(envName);
        if (value == null || value.isBlank()) {
            return Path.of(fallback);
        }
        return Path.of(value.trim());
    }

    public record DataSpaceFile(String fileName, String contentType, long size, InputStream inputStream) {
    }

    private record UploadedJson(String fileName, byte[] bytes) {
    }

    private record DatasetStats(
            String imageDir,
            String captionsJson,
            int imageCount,
            int jsonImageCount,
            int annotationCount,
            int annotatedImageCount,
            long totalBytes) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("imageDir", imageDir);
            map.put("captionsJson", captionsJson);
            map.put("imageCount", imageCount);
            map.put("jsonImageCount", jsonImageCount);
            map.put("annotationCount", annotationCount);
            map.put("annotatedImageCount", annotatedImageCount);
            map.put("totalBytes", totalBytes);
            map.put("totalSize", humanBytesStatic(totalBytes));
            return map;
        }

        private static String humanBytesStatic(long bytes) {
            if (bytes <= 0) {
                return "0 KB";
            }
            double kb = bytes / 1024.0;
            if (kb < 1024) {
                return String.format(Locale.ROOT, "%.1f KB", kb);
            }
            double mb = kb / 1024.0;
            if (mb < 1024) {
                return String.format(Locale.ROOT, "%.1f MB", mb);
            }
            return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0);
        }
    }
}
