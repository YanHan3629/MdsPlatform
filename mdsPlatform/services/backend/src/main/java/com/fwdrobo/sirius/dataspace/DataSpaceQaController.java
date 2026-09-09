package com.fwdrobo.sirius.dataspace;

import com.fwdrobo.sirius.dto.mm.InternalTextToImageReq;
import com.fwdrobo.sirius.port.SearchServiceClient;
import com.fwdrobo.sirius.service.MmAssetService;
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
    private final JdbcTemplate jdbc;
    private final SearchServiceClient search;
    private final MmAssetService assets;
    private final RestTemplate http;
    private final String qaUrl;

    public DataSpaceQaController(JdbcTemplate jdbc, SearchServiceClient search,
                                 MmAssetService assets,
                                 @Value("${QA_SERVICE_BASE_URL:http://localhost:18081}") String qaUrl) {
        this.jdbc = jdbc;
        this.search = search;
        this.assets = assets;
        this.qaUrl = qaUrl.replaceAll("/+$", "");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(360000);
        this.http = new RestTemplate(factory);
    }

    @GetMapping("/datasets")
    public List<Map<String, Object>> datasets() {
        UUID org = SecurityUtils.getUserOrgId();
        if (org == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号未绑定组织");
        return jdbc.query("""
                SELECT d.dataset_id, d.dataset_name, d.modality_type, v.version_id, v.version_name,
                       i.index_version_id, i.index_status, v.sample_count
                FROM mm_dataset d JOIN mm_dataset_version v ON v.dataset_id = d.dataset_id
                JOIN mm_index_version i ON i.index_version_id = v.active_index_version_id
                  AND i.dataset_id = d.dataset_id AND i.dataset_version_id = v.version_id
                WHERE d.org_id = ? AND d.status = 'ACTIVE' AND i.index_status = 'READY'
                ORDER BY d.dataset_name, v.created_at DESC
                """, (rs, row) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("datasetId", rs.getString("dataset_id"));
            item.put("datasetName", rs.getString("dataset_name"));
            item.put("versionId", rs.getString("version_id"));
            item.put("versionName", rs.getString("version_name"));
            item.put("indexVersionId", rs.getString("index_version_id"));
            item.put("indexStatus", rs.getString("index_status"));
            item.put("modalityType", rs.getString("modality_type"));
            item.put("sampleCount", rs.getLong("sample_count"));
            return item;
        }, org);
    }

    // Reject stale, mismatched or foreign scopes before any retrieval or model call.
    void validateScope(Map<String, ?> body) {
        String dataset = field(body, "datasetId", "dataset_id");
        String version = field(body, "versionId", "version_id");
        String index = field(body, "indexVersionId", "index_version_id");
        if (dataset.isBlank() && version.isBlank() && index.isBlank()) return;
        boolean allowed = datasets().stream().anyMatch(d -> dataset.equals(d.get("datasetId"))
                && version.equals(d.get("versionId")) && index.equals(d.get("indexVersionId")));
        if (!allowed) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "数据集、版本或索引不可用，请刷新并选择当前账号可访问的 READY 数据集");
    }

    private static String field(Map<String, ?> body, String camel, String snake) {
        Object value = body.containsKey(camel) ? body.get(camel) : body.get(snake);
        return value == null ? "" : value.toString();
    }

    @GetMapping("/health")
    public ResponseEntity<byte[]> health() {
        return forward("/health", HttpMethod.GET, null, new HttpHeaders());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> askJson(@RequestBody Map<String, Object> body,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        validateScope(body);
        var headers = headers(authorization, MediaType.APPLICATION_JSON);
        return forward("/api/v1/qa", HttpMethod.POST, body, headers);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> askMultipart(@RequestParam MultiValueMap<String, String> fields,
            @RequestParam(required = false) List<MultipartFile> images,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) throws Exception {
        validateScope(fields.toSingleValueMap());
        if (images != null && images.size() > 2)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单次最多上传 2 张图片");
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        fields.forEach((k, values) -> values.forEach(v -> body.add(k, v)));
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
    public Map<String, Object> search(@RequestBody Map<String, Object> body) {
        validateScope(body);
        UUID dataset = UUID.fromString(field(body, "datasetId", "dataset_id"));
        UUID version = UUID.fromString(field(body, "versionId", "version_id"));
        UUID index = UUID.fromString(field(body, "indexVersionId", "index_version_id"));
        int topK = Math.max(1, Math.min(50, Integer.parseInt(body.getOrDefault("topK", 8).toString())));
        var result = search.textToImage(new InternalTextToImageReq(dataset, version, index,
                Objects.toString(body.get("query"), ""), topK));
        List<Map<String, Object>> items = new ArrayList<>();
        if (result != null && result.items() != null) for (var hit : result.items()) {
            var asset = assets.getAsset(dataset, version, hit.assetId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("assetId", hit.assetId());
            item.put("logicalPath", asset.logicalPath());
            item.put("score", hit.score());
            // Unified-index evidence contains temporal windows, not just legacy captions.
            item.put("texts", hit.texts() == null || hit.texts().isEmpty() ? asset.captions() : hit.texts());
            item.put("contentType", asset.contentType());
            item.put("previewUrl", asset.previewUrl());
            items.add(item);
        }
        return Map.of("items", items, "indexVersionId", index);
    }

    @GetMapping("/assets/{datasetId}/{versionId}/{assetId}")
    public ResponseEntity<byte[]> source(@PathVariable UUID datasetId, @PathVariable UUID versionId,
                                         @PathVariable UUID assetId) {
        var asset = assets.getAsset(datasetId, versionId, assetId);
        if (asset.previewUrl() == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "来源文件不可用");
        // URL is generated from a validated stored asset, never supplied by the browser.
        // Presigned query parameters are already encoded; the String overload encodes '%' again.
        var response = http.getForEntity(java.net.URI.create(asset.previewUrl()), byte[].class);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(
                asset.contentType() == null ? "application/octet-stream" : asset.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(asset.fileName(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(response.getBody());
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
