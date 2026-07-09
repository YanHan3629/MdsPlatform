package com.fwdrobo.sirius.container;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;

public class InspectParser {
    private static final ObjectMapper M = new ObjectMapper();

    public record ContainerState(
            String dockerStatus,
            Integer exitCode,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt
    ) {}

    public static ContainerState parse(String inspectJson) throws Exception {
        JsonNode arr = M.readTree(inspectJson);
        JsonNode root = arr.get(0);
        JsonNode state = root.path("State");

        String status = state.path("Status").asText(null);
        Integer exit = state.hasNonNull("ExitCode") ? state.get("ExitCode").asInt() : null;

        OffsetDateTime startedAt = parseTime(state.path("StartedAt").asText(null));
        OffsetDateTime finishedAt = parseTime(state.path("FinishedAt").asText(null));

        return new ContainerState(status, exit, startedAt, finishedAt);
    }

    private static OffsetDateTime parseTime(String s) {
        if (s == null || s.isBlank() || s.startsWith("0001-")) return null;
        return OffsetDateTime.parse(s);
    }
}
