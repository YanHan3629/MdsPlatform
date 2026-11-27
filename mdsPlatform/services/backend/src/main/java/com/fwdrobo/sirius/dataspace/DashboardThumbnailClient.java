package com.fwdrobo.sirius.dataspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.security.InternalTokenFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

@Component
class DashboardThumbnailClient {
    static final String RECIPE = "dashboard-preview-2";
    private static final int MAX_BYTES = 200 * 1024;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final URI endpoint;
    private final String token;

    DashboardThumbnailClient(ObjectMapper json,
            @Value("${mm.evidence-worker.base-url:http://evidence-worker:18083}") String base,
            @Value("${mm.internal.token:}") String token) {
        this.json = json;
        this.endpoint = URI.create(base + "/internal/v1/evidence/dashboard-thumbnail");
        this.token = token;
    }

    byte[] render(String format, String sha, byte[] content) throws Exception {
        if (token.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "内容预览服务尚未配置。");
        byte[] payload = json.writeValueAsBytes(Map.of("format", format, "sourceSha256", sha, "recipeVersion", RECIPE,
                "sourceBase64", Base64.getEncoder().encodeToString(content)));
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header(InternalTokenFilter.HEADER, token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status != 200) {
                var errorStatus = switch (status) {
                    case 413 -> HttpStatus.PAYLOAD_TOO_LARGE;
                    case 422 -> HttpStatus.UNPROCESSABLE_ENTITY;
                    case 429 -> HttpStatus.TOO_MANY_REQUESTS;
                    case 504 -> HttpStatus.GATEWAY_TIMEOUT;
                    default -> HttpStatus.SERVICE_UNAVAILABLE;
                };
                throw new ResponseStatusException(errorStatus);
            }
            byte[] body = response.body();
            if (body.length > MAX_BYTES || body.length < 4 || body[0] != (byte) 0xff || body[1] != (byte) 0xd8 ||
                    !sha.equals(response.headers().firstValue("X-Source-Sha256").orElse("")) ||
                    !RECIPE.equals(response.headers().firstValue("X-Render-Recipe").orElse("")))
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "内容预览校验失败，请重试。");
            return body;
        } catch (HttpTimeoutException ex) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "内容预览超时，请重试。");
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "内容预览服务暂时无法连接。");
        }
    }
}
