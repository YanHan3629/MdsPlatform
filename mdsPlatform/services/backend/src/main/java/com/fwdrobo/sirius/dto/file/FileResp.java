package com.fwdrobo.sirius.dto.file;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fwdrobo.sirius.util.DataFileFormatClassifier;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FileResp {
    private UUID fileId;
    @JsonIgnore
    private UUID commitId;
    private UUID deviceId;
    private String logicalPath;
    @JsonIgnore
    private String fileKey;
    private Long sizeBytes;
    private String contentType;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public String getFileFormat() {
        return DataFileFormatClassifier.classify(logicalPath, contentType).format();
    }

    public String getStorageCategory() {
        return DataFileFormatClassifier.classify(logicalPath, contentType).storageCategory();
    }
}
