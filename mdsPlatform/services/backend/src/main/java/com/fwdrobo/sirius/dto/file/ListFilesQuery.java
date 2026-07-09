package com.fwdrobo.sirius.dto.file;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class ListFilesQuery {
    private UUID repoId;
    private UUID commitId;
    private String globPattern;
    private Integer pageIdx;
    private Integer pageSize;
}
