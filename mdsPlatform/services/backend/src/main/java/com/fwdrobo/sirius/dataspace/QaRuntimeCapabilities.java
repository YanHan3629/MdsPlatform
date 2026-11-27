package com.fwdrobo.sirius.dataspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** The same packaged record is bound to the QA worker and inference launcher. */
final class QaRuntimeCapabilities {
    static final String DEADLINE_HEADER = "X-MDS-QA-Deadline-Ms";
    static final String REQUEST_ID_HEADER = "X-MDS-QA-Request-Id";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_PROFILE = "standard";
    private final JsonNode definition;
    private final String checksum;
    private final String active;
    private final int contextLimit;

    QaRuntimeCapabilities(String activeProfile) {
        try (var resource = QaRuntimeCapabilities.class.getResourceAsStream("/qa-runtime/qa-capabilities.json")) {
            if (resource == null) throw new IllegalStateException("QA capability resource is missing");
            byte[] bytes = resource.readAllBytes();
            definition = JSON.readTree(bytes);
            checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (IOException | NoSuchAlgorithmException invalid) {
            throw new IllegalStateException("QA capability resource is invalid", invalid);
        }
        active = activeProfile;
        contextLimit = definition.path("profiles").path(active).path("contextTokens").asInt();
        if (contextLimit < 1) throw new IllegalStateException("Unknown QA runtime profile");
        if (!definition.path("maxDataScopes").isIntegralNumber() || !definition.path("maxDataScopes").canConvertToInt()
                || maxDataScopes() < 1 || maxDataScopes() > 8)
            throw new IllegalStateException("Invalid QA scope capacity");
    }

    JsonNode validateProfile(Map<String, Object> body) {
        String first = Objects.toString(body.get("profile"), "");
        String alias = Objects.toString(body.get("qaProfile"), "");
        if (!first.isBlank() && !alias.isBlank() && !first.equals(alias))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问答档位字段冲突");
        String selected = !first.isBlank() ? first : !alias.isBlank() ? alias : DEFAULT_PROFILE;
        JsonNode profile = definition.path("profiles").path(selected);
        if (profile.isMissingNode() || profile.path("contextTokens").asInt() > contextLimit)
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "当前部署未启用所选问答档位");
        validateOutputTokens(body, profile.path("maxOutputTokens").asInt());
        long characters = characters(body.get("question")) + characters(body.get("context"));
        if (body.get("texts") instanceof Iterable<?> texts) {
            for (Object text : texts) characters += characters(text);
        }
        Object previous = body.containsKey("priorQuestions") ? body.get("priorQuestions") : body.get("prior_questions");
        if (previous instanceof Iterable<?> history) {
            for (Object text : history) characters += characters(text);
        }
        if (characters > definition.path("maxUserCharacters").asLong())
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "问题和补充文字超过字符上限");
        body.remove("qaProfile");
        body.put("profile", selected);
        return profile;
    }

    private static long characters(Object value) {
        if (!(value instanceof String text)) return 0;
        return text.codePointCount(0, text.length());
    }

    private static void validateOutputTokens(Map<String, Object> body, int maximum) {
        String camel = Objects.toString(body.get("maxTokens"), "").strip();
        String snake = Objects.toString(body.get("max_tokens"), "").strip();
        if (!camel.isEmpty() && !snake.isEmpty() && !camel.equals(snake))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回答预算字段冲突");
        String requested = snake.isEmpty() ? camel : snake;
        body.remove("maxTokens");
        body.remove("max_tokens");
        if (requested.isEmpty()) return;
        try {
            int amount = Integer.parseInt(requested);
            if (amount >= 1 && amount <= maximum) {
                body.put("max_tokens", amount);
                return;
            }
        } catch (NumberFormatException invalid) {
            // The effective range is the same for malformed and oversized values.
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "回答预算超出当前档位允许范围");
    }

    int maxImageCount() { return definition.path("maxImageCount").asInt(); }
    int maxDataScopes() { return definition.path("maxDataScopes").asInt(); }
    long maxImageBytes() { return definition.path("maxImageBytes").asLong(); }
    long maxAttachmentBytes() { return definition.path("maxAttachmentBytes").asLong(); }
    int outerTimeoutMillis() { return (definition.path("profiles").path(active).path("deadlineSeconds").asInt() + 8) * 1000; }

    boolean matchesWorker(byte[] body) {
        if (body == null) return false;
        try {
            JsonNode value = JSON.readTree(body);
            return checksum.equals(value.path("capabilities").path("sha256").asText())
                    && active.equals(value.path("capabilities").path("runtimeProfile").asText())
                    && definition.path("model").asText().equals(value.path("model").path("name").asText())
                    && definition.path("modelRevision").asText().equals(value.path("model").path("revision").asText());
        } catch (IOException | IllegalArgumentException invalid) {
            return false;
        }
    }
}
