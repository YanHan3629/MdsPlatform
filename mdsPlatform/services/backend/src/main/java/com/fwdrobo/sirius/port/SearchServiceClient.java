package com.fwdrobo.sirius.port;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.dto.mm.InternalImageToTextResp;
import com.fwdrobo.sirius.dto.mm.InternalTextToImageReq;
import com.fwdrobo.sirius.dto.mm.InternalTextToImageResp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class SearchServiceClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final HttpClient httpClient;

    public SearchServiceClient(@Value("${mm.search.base-url}") String baseUrl,
                               ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.restClient = RestClient.builder().baseUrl(this.baseUrl).build();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
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

