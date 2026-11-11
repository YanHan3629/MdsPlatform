package com.fwdrobo.sirius.port;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.dto.mm.InternalImageToTextResp;
import com.fwdrobo.sirius.dto.mm.InternalTextToImageReq;
import com.fwdrobo.sirius.dto.mm.InternalTextToImageResp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import com.fwdrobo.sirius.security.IndexCacheScopeService;
import java.util.Map;

@Component
public class SearchServiceClient {

    // A cold query can stream several verified index artifacts before execution.
    private static final Duration COLD_INDEX_QUERY_TIMEOUT = Duration.ofMinutes(4);

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String internalToken;
    private final HttpClient httpClient;
    private final com.fwdrobo.sirius.security.WorkerEvidenceScopeService evidenceScope;

    public SearchServiceClient(@Value("${mm.search.base-url}") String baseUrl,
                               @Value("${mm.internal.token:}") String internalToken,
                               ObjectMapper objectMapper,com.fwdrobo.sirius.security.WorkerEvidenceScopeService evidenceScope) {
        this.evidenceScope=evidenceScope;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.internalToken = internalToken;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private String scopedBearer(Object body,String path){
        if(body!=null){
            var value=objectMapper.valueToTree(body);
            String[] keys={"dataset","version","index_version"};String[] camel={"datasetId","versionId","indexVersionId"};String[] snake={"dataset_id","version_id","index_version_id"};
            UUID[] ids=new UUID[3];
            for(int i=0;i<3;i++){
                String first=value.path(camel[i]).asText(""),second=value.path(snake[i]).asText("");
                if(!first.isBlank()&&!second.isBlank()&&!first.equals(second))throw new IllegalArgumentException("Ambiguous frozen scope");
                ids[i]=UUID.fromString(first.isBlank()?second:first);
            }
            return evidenceScope.bearer(ids[0],ids[1],ids[2]);
        }
        var match=java.util.regex.Pattern.compile("^/indexes/([0-9a-fA-F-]{36})/([0-9a-fA-F-]{36})/([0-9a-fA-F-]{36})/capabilities$").matcher(path);
        if(!match.matches())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"SCOPED_SEARCH_REQUEST_REQUIRED");
        return evidenceScope.bearer(UUID.fromString(match.group(1)),UUID.fromString(match.group(2)),UUID.fromString(match.group(3)));
    }

    /** Preserve validation/service status codes; credentials stay server-side. */
    public org.springframework.http.ResponseEntity<byte[]> scopedJson(String path, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1" + path))
                    .timeout("/search/execute".equals(path) ? COLD_INDEX_QUERY_TIMEOUT : Duration.ofMinutes(2))
                    .header("Accept", "application/json").header("X-MM-Internal-Token", internalToken)
                    .header("Authorization",scopedBearer(body,path));
            if (body == null) builder.GET();
            else builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return org.springframework.http.ResponseEntity.status(response.statusCode())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .header("Cache-Control", "no-store").body(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "检索请求已中断");
        } catch (IOException error) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "检索服务不可用或请求超时");
        }
    }

    public org.springframework.http.ResponseEntity<byte[]> scopedImage(UUID dataset, UUID version, UUID index,
                                                                        String plan, MultipartFile file) {
        try {
            String boundary = "----ScopedSearch" + UUID.randomUUID();
            byte[] original = buildImageToTextMultipartBody(boundary, dataset, version, index, 8, file);
            byte[] closing = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            var buffer = new ByteArrayOutputStream();
            buffer.write(original, 0, original.length - closing.length);
            writeTextPart(buffer, boundary, "plan", plan, "\r\n");
            buffer.write(closing);
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/v1/search/image-candidates"))
                    .timeout(COLD_INDEX_QUERY_TIMEOUT)
                    .header("X-MM-Internal-Token", internalToken)
                    .header("Authorization",evidenceScope.bearer(dataset,version,index))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(buffer.toByteArray())).build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return org.springframework.http.ResponseEntity.status(response.statusCode())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("Cache-Control", "no-store").body(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"图搜文请求已中断");
        } catch (IOException error) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"图搜文服务不可用");
        }
    }

    public InternalTextToImageResp textToImage(InternalTextToImageReq req) {
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(req);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("serialize text-to-image request failed", e);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/internal/v1/search/text-to-image"))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-MM-Internal-Token", internalToken)
                .header("Authorization",scopedBearer(req,null))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException(response.statusCode() + " " + response.body());
            }
            return objectMapper.readValue(response.body(), InternalTextToImageResp.class);
        } catch (IOException e) {
            throw new RuntimeException("call text-to-image request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("call text-to-image request interrupted", e);
        }
    }

    public void preload(UUID datasetId, UUID versionId, UUID indexVersionId,String authorization,String authorityKind) {
        preload(datasetId, versionId, indexVersionId, authorization, authorityKind, Duration.ofSeconds(120));
    }

    public void preloadPrepared(IndexCacheScopeService.Ticket ticket) {
        var scope = ticket.scope();
        var credential = ticket.credential();
        if (!"ORIGINAL_BUILD".equals(scope.kind()) || !"HUMAN".equals(credential.authorityKind())) {
            throw new IllegalArgumentException("Original build authority is required");
        }
        preload(scope.dataset(), scope.version(), scope.index(), credential.authorization(),
                credential.authorityKind(), IndexCacheScopeService.PREPARED_PRELOAD_TIMEOUT);
    }

    private void preload(UUID datasetId, UUID versionId, UUID indexVersionId,
                         String authorization, String authorityKind, Duration timeout) {
        if(authorization==null||!authorization.startsWith("Bearer ")||!java.util.Set.of("HUMAN","SERVICE").contains(authorityKind))
            throw new IllegalArgumentException("Scoped index preload authority is required");
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(
                    Map.of("datasetId", datasetId.toString(), "versionId", versionId.toString(),
                            "indexVersionId", indexVersionId.toString()));
        } catch (JsonProcessingException exception) {
            throw new RuntimeException("serialize index preload request failed", exception);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/internal/v1/indexes/preload"))
                .timeout(timeout)
                .header("Authorization",authorization).header("X-MDS-Index-Authority",authorityKind)
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-MM-Internal-Token", internalToken)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("scoped index preload rejected HTTP " + response.statusCode());
            }
        } catch (IOException exception) {
            throw new RuntimeException("call index preload request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("call index preload request interrupted", exception);
        }
    }

    public InternalImageToTextResp imageToText(UUID datasetId, UUID versionId, UUID indexVersionId, Integer topK, MultipartFile file) {
        String boundary = "----SiriusBoundary" + System.currentTimeMillis();
        byte[] requestBody;
        try {
            requestBody = buildImageToTextMultipartBody(boundary, datasetId, versionId, indexVersionId, topK, file);
        } catch (IOException e) {
            throw new RuntimeException("build image-to-text multipart body failed", e);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/internal/v1/search/image-to-text"))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .header("X-MM-Internal-Token", internalToken)
                .header("Authorization",evidenceScope.bearer(datasetId,versionId,indexVersionId))
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException(response.statusCode() + " " + response.body());
            }
            return objectMapper.readValue(response.body(), InternalImageToTextResp.class);
        } catch (IOException e) {
            throw new RuntimeException("call image-to-text request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("call image-to-text request interrupted", e);
        }
    }

    private byte[] buildImageToTextMultipartBody(String boundary, UUID datasetId, UUID versionId, UUID indexVersionId, Integer topK, MultipartFile file) throws IOException {
        String crlf = "\r\n";
        int safeTopK = topK == null ? 5 : topK;
        String fileName = file == null || file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "upload.bin" : file.getOriginalFilename();
        String fileContentType = file == null || file.getContentType() == null || file.getContentType().isBlank()
                ? "application/octet-stream" : file.getContentType();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTextPart(out, boundary, "datasetId", datasetId.toString(), crlf);
        writeTextPart(out, boundary, "versionId", versionId.toString(), crlf);
        writeTextPart(out, boundary, "indexVersionId", indexVersionId.toString(), crlf);
        writeTextPart(out, boundary, "topK", String.valueOf(safeTopK), crlf);

        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"" + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + fileContentType + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(file.getBytes());
        out.write(crlf.getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private void writeTextPart(ByteArrayOutputStream out, String boundary, String name, String value, String crlf) throws IOException {
        out.write(("--" + boundary + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write(crlf.getBytes(StandardCharsets.UTF_8));
    }
}

