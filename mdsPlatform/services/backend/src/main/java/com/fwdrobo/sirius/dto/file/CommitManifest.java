package com.fwdrobo.sirius.dto.file;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@Builder
public class CommitManifest {
    @JsonProperty("artifact_id")
    private UUID artifactId;

    @JsonProperty("commit_id")
    private UUID commitId;

    @JsonProperty("version")
    private long version;

    @JsonProperty("created_at")
    private OffsetDateTime createdAt;

    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;

    @Builder.Default
    @JsonProperty("files")
    @JsonSetter(value = "files", nulls = Nulls.AS_EMPTY)
    private List<File> files = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class File {
        @JsonProperty("path")
        private String path;

        @JsonProperty("file_id")
        private UUID fileId;
    }
}
