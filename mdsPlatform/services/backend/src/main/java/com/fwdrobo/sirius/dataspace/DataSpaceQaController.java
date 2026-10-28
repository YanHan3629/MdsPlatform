package com.fwdrobo.sirius.dataspace;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fwdrobo.sirius.dto.mm.InternalTextToImageReq;
import com.fwdrobo.sirius.port.SearchServiceClient;
import com.fwdrobo.sirius.service.MmAssetService;
import com.fwdrobo.sirius.service.MmSourceFileService;
import com.fwdrobo.sirius.service.DatasetAccessService;
import com.fwdrobo.sirius.util.SecurityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** Same-origin QA gateway: authenticated dataset scope, exact index and source access. */
@RestController
@RequestMapping("/api/data-space/qa")
public class DataSpaceQaController {
    private static final String[][] SCOPE_FIELDS = {
            {"datasetId", "dataset_id"}, {"versionId", "version_id"},
            {"indexVersionId", "index_version_id"}
    };
    // Local reader: ambiguous requests must be rejected before Map binding can
    // discard duplicate JSON keys, without changing other controllers' parsing.
    private static final ObjectReader REQUEST_READER = new ObjectMapper()
            .readerFor(new TypeReference<LinkedHashMap<String, Object>>() {})
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final JdbcTemplate jdbc;
    private final SearchServiceClient search;
    private final MmAssetService assets;
    private final MmSourceFileService sourceFiles;
    private final RestTemplate http;
    private final String qaUrl;
    private final DatasetAccessService access;

    public DataSpaceQaController(JdbcTemplate jdbc, SearchServiceClient search,
                                 MmAssetService assets, MmSourceFileService sourceFiles, DatasetAccessService access,
                                 @Value("${QA_SERVICE_BASE_URL:http://localhost:18081}") String qaUrl) {
        this.jdbc = jdbc;
        this.search = search;
        this.assets = assets;
        this.sourceFiles = sourceFiles;
        this.access=access;
        this.qaUrl = qaUrl.replaceAll("/+$", "");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(360000);
        this.http = new RestTemplate(factory);
    }

    @GetMapping("/datasets")
    public List<Map<String, Object>> datasets(@RequestParam(defaultValue="false") boolean includeOwnerCandidates) {
        List<Map<String,Object>> result=access.readableReadyScopes(includeOwnerCandidates);
        result.forEach(item -> item.put("capabilities", jdbc.queryForList("""
                SELECT semantic_space AS "semanticSpace",index_type AS "indexType",
                       registration_status AS status,coverage
                FROM mm_index_registration WHERE index_version_id=? ORDER BY semantic_space
                """, UUID.fromString(item.get("indexVersionId").toString()))));
        return result;
    }

    @GetMapping("/access/{datasetId}/{versionId}/{indexVersionId}")
    public ResponseEntity<Map<String,Object>> access(@PathVariable UUID datasetId,@PathVariable UUID versionId,@PathVariable UUID indexVersionId){
        access.requireVersionRead(datasetId,versionId,indexVersionId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("datasetId",datasetId,"versionId",versionId,"indexVersionId",indexVersionId,"authorized",true));
    }

    /** Real durable ingestion progress for the signed-in organisation. */
    @GetMapping("/indexing")
    public List<Map<String, Object>> indexing() {
        UUID org = SecurityUtils.getUserOrgId();
        if (org == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号未绑定组织");
        return jdbc.queryForList("""
                SELECT t.task_id AS "taskId",t.dataset_id AS "datasetId",t.dataset_version_id AS "versionId",
                       d.dataset_name AS "datasetName",v.version_name AS "versionName",
                       t.task_status AS status,t.stage,t.attempt_count AS "attemptCount",
                       t.max_attempts AS "maxAttempts",t.error_message AS error,t.updated_at AS "updatedAt"
                FROM mm_ingest_task t JOIN mm_dataset d ON d.dataset_id=t.dataset_id
                JOIN mm_dataset_version v ON v.version_id=t.dataset_version_id
                WHERE t.org_id=? ORDER BY t.updated_at DESC LIMIT 50
                """, org);
    }

    @GetMapping("/query-assets/{datasetId}/{versionId}")
    public List<Map<String,Object>> queryAssets(@PathVariable UUID datasetId, @PathVariable UUID versionId) {
        access.requireVersionRead(datasetId,versionId,null);
        return jdbc.queryForList("""
                SELECT a.asset_id AS "assetId",a.logical_path AS "logicalPath"
                FROM mm_asset a JOIN mm_dataset_version v ON v.version_id=a.dataset_version_id
                JOIN mm_dataset d ON d.dataset_id=v.dataset_id
                WHERE d.dataset_id=? AND v.version_id=? AND d.status='ACTIVE'
                  AND (lower(a.logical_path) LIKE '%.obj' OR lower(a.logical_path) LIKE '%.step' OR lower(a.logical_path) LIKE '%.stp')
                ORDER BY a.logical_path LIMIT 500
                """,datasetId,versionId);
    }

    // Reject incomplete, mismatched, unpublished or foreign scopes before any
    // retrieval/model call. Owner candidate selection does not activate it;
    // every explicit READY scope still passes the same live authority check.
    void validateScope(Map<String, ?> body) {
        validatedBody(body, false);
    }

    private Map<String, Object> validatedBody(Map<String, ?> body, boolean required) {
        Map<String, Object> normalized = normalizeScope(body);
        String dataset = Objects.toString(normalized.get("dataset_id"), "");
        String version = Objects.toString(normalized.get("version_id"), "");
        String index = Objects.toString(normalized.get("index_version_id"), "");
        if (dataset.isBlank() && version.isBlank() && index.isBlank() && !required) return normalized;
        if (dataset.isBlank() || version.isBlank() || index.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "datasetId、versionId 与 indexVersionId 必须同时提供");
        }
        try {
            UUID datasetId = UUID.fromString(dataset), versionId = UUID.fromString(version),
                    indexId = UUID.fromString(index);
            access.requireVersionRead(datasetId, versionId, indexId);
            normalized.put("dataset_id", datasetId.toString());
            normalized.put("version_id", versionId.toString());
            normalized.put("index_version_id", indexId.toString());
            return normalized;
        } catch (IllegalArgumentException ignored) {
            // Malformed identifiers are reported as an unavailable scope below.
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "数据集、版本或索引不可用，或不属于当前账号可访问的 PUBLISHED/READY 快照");
    }

    static Map<String, Object> parseJson(String rawBody) {
        try {
            Map<String, Object> body = REQUEST_READER.readValue(rawBody);
            if (body != null) return body;
        } catch (java.io.IOException ignored) {
            // Do not echo payloads or parser excerpts containing user content.
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求必须是 JSON 对象，且不能包含重复字段");
    }

    private static String scopeValue(Object value) {
        if (value == null) return "";
        if (!(value instanceof String) && !(value instanceof UUID))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据范围字段必须是 UUID 字符串");
        return value.toString().strip();
    }

    static Map<String, Object> normalizeScope(Map<String, ?> body) {
        Map<String, Object> normalized = new LinkedHashMap<>(body);
        for (String[] names : SCOPE_FIELDS) {
            String camel = scopeValue(body.get(names[0])), snake = scopeValue(body.get(names[1]));
            if (body.containsKey(names[0]) && body.containsKey(names[1]) && !camel.equals(snake))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, names[0] + " 的字段别名值冲突");
            String value = body.containsKey(names[0]) ? camel : snake;
            normalized.remove(names[0]);
            normalized.remove(names[1]);
            if (!value.isBlank()) normalized.put(names[1], value);
        }
        return normalized;
    }

    static Map<String, Object> multipartFields(MultiValueMap<String, String> fields) {
        for (String[] names : SCOPE_FIELDS) for (String name : names) {
            List<String> values = fields.get(name);
            if (values != null && values.stream().map(DataSpaceQaController::scopeValue).distinct().count() > 1)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " 包含冲突的重复字段");
        }
        return normalizeScope(fields.toSingleValueMap());
    }

    @GetMapping("/health")
    public ResponseEntity<byte[]> health() {
        return forward("/health", HttpMethod.GET, null, new HttpHeaders());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> askJson(@RequestBody String rawBody,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        Map<String, Object> body = validatedBody(parseJson(rawBody), false);
        var headers = headers(authorization, MediaType.APPLICATION_JSON);
        return forward("/api/v1/qa", HttpMethod.POST, body, headers);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> askMultipart(@RequestParam MultiValueMap<String, String> fields,
            @RequestParam(required = false) List<MultipartFile> images,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) throws Exception {
        Map<String, Object> normalized = validatedBody(multipartFields(fields), false);
        if (images != null && images.size() > 2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单次最多上传 2 张图片");
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        fields.forEach((k, values) -> values.forEach(v -> body.add(k, v)));
        for (String[] names : SCOPE_FIELDS) {
            body.remove(names[0]);
            body.remove(names[1]);
            if (normalized.containsKey(names[1])) body.add(names[1], normalized.get(names[1]));
        }
        if (images != null) for (MultipartFile image : images) {
            if (image.getSize() > 10 * 1024 * 1024)
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "每张图片不得超过 10 MB");
            var partHeaders = new HttpHeaders();
            partHeaders.setContentType(MediaType.parseMediaType(
                    image.getContentType() == null ? "application/octet-stream" : image.getContentType()));
            var resource = new ByteArrayResource(image.getBytes()) {
                @Override public String getFilename() { return image.getOriginalFilename(); }
            };
            body.add("images", new HttpEntity<>(resource, partHeaders));
        }
        return forward("/api/v1/qa", HttpMethod.POST, body,
                headers(authorization, MediaType.MULTIPART_FORM_DATA));
    }

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody String rawBody) throws Exception {
        Map<String, Object> body = validatedBody(parseJson(rawBody), true);
        UUID dataset = UUID.fromString(body.get("dataset_id").toString());
        UUID version = UUID.fromString(body.get("version_id").toString());
        UUID index = UUID.fromString(body.get("index_version_id").toString());
        int topK = Math.max(1, Math.min(50, Integer.parseInt(body.getOrDefault("topK", 8).toString())));
        var result = search.textToImage(new InternalTextToImageReq(dataset, version, index,
                Objects.toString(body.get("query"), ""), topK));
        List<Map<String, Object>> items = new ArrayList<>();
        Map<UUID, MmSourceFileService.Source> resolved = new HashMap<>();
        if (result != null && result.items() != null)
            for (var source : sourceFiles.resolve(dataset, version,
                    result.items().stream().map(hit -> hit.assetId()).toList()))
                resolved.put(source.assetId(), source);
        if (result != null && result.items() != null) for (var hit : result.items()) {
            var asset = resolved.get(hit.assetId());
            if (!asset.available()) continue; // Stale index entry must not become a broken source link.
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("assetId", hit.assetId());
            item.put("logicalPath", asset.logicalPath());
            item.put("score", hit.score());
            // Unified-index evidence contains temporal windows, not just legacy captions.
            var texts = hit.texts();
            if (texts == null || texts.isEmpty()) {
                try { texts = assets.getAsset(dataset, version, hit.assetId()).captions(); }
                catch (ResponseStatusException error) {
                    // A snapshot source (e.g. COCO JSON) need not have imported captions.
                    if (error.getStatusCode() != HttpStatus.NOT_FOUND) throw error;
                    texts = List.of();
                }
            }
            item.put("texts", texts);
            item.put("contentType", asset.contentType());
            item.put("previewUrl", sourceFiles.downloadUrl(dataset, version, asset));
            items.add(item);
        }
        return Map.of("items", items, "indexVersionId", index);
    }

    @GetMapping("/capabilities/{datasetId}/{versionId}/{indexVersionId}")
    public ResponseEntity<byte[]> capabilities(@PathVariable UUID datasetId, @PathVariable UUID versionId,
                                               @PathVariable UUID indexVersionId) {
        validateScope(Map.of("datasetId", datasetId, "versionId", versionId, "indexVersionId", indexVersionId));
        return search.scopedJson("/indexes/" + datasetId + "/" + versionId + "/" + indexVersionId + "/capabilities", null);
    }

    @PostMapping("/retrieval/execute")
    public ResponseEntity<byte[]> retrieve(@RequestBody String rawBody) {
        Map<String, Object> body = validatedBody(parseJson(rawBody), true);
        return search.scopedJson("/search/execute", body);
    }

    @PostMapping(value="/retrieval/image-to-text", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> imageRetrieval(@RequestParam MultiValueMap<String, String> fields,
            @RequestParam String plan, @RequestParam MultipartFile file) {
        Map<String, Object> body = validatedBody(multipartFields(fields), true);
        UUID datasetId = UUID.fromString(body.get("dataset_id").toString());
        UUID versionId = UUID.fromString(body.get("version_id").toString());
        UUID indexVersionId = UUID.fromString(body.get("index_version_id").toString());
        if (file.getSize()>10*1024*1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"查询图片不得超过10 MB");
        return search.scopedImage(datasetId,versionId,indexVersionId,plan,file);
    }

    @PostMapping("/source-files/{datasetId}/{versionId}")
    public List<MmSourceFileService.Source> sourceFiles(@PathVariable UUID datasetId,
            @PathVariable UUID versionId, @RequestBody List<UUID> assetIds) throws Exception {
        return sourceFiles.resolve(datasetId, versionId, assetIds);
    }

    @GetMapping("/assets/{datasetId}/{versionId}/{assetId}")
    public ResponseEntity<org.springframework.core.io.InputStreamResource> source(@PathVariable UUID datasetId, @PathVariable UUID versionId,
                                         @PathVariable UUID assetId) throws Exception {
        var asset = sourceFiles.resolve(datasetId, versionId, List.of(assetId)).get(0);
        var stream=sourceFiles.open(datasetId,versionId,asset);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(
                asset.contentType() == null ? "application/octet-stream" : asset.contentType()))
                .contentLength(stream.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(asset.logicalPath().substring(asset.logicalPath().lastIndexOf('/') + 1),
                                java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(new org.springframework.core.io.InputStreamResource(stream.inputStream()));
    }

    private static HttpHeaders headers(String authorization, MediaType type) {
        var headers = new HttpHeaders();
        headers.setContentType(type);
        headers.set(HttpHeaders.AUTHORIZATION, authorization);
        return headers;
    }

    private ResponseEntity<byte[]> forward(String path, HttpMethod method, Object body, HttpHeaders headers) {
        try {
            var response = http.exchange(qaUrl + path, method, new HttpEntity<>(body, headers), byte[].class);
            return ResponseEntity.status(response.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store").body(response.getBody());
        } catch (HttpStatusCodeException exc) {
            return ResponseEntity.status(exc.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                    .body(exc.getResponseBodyAsByteArray());
        } catch (ResourceAccessException exc) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "问答服务尚未就绪或请求超时，请检查模型状态");
        }
    }
}
