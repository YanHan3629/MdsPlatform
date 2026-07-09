package com.fwdrobo.sirius.dto.file;

import com.fwdrobo.sirius.entity.artifact.CommitStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ListGetResp {
    private UUID artifactId;
    private UUID commitId;
    private List<FileResp> fileRespList;
    private CommitStatus commitStatus;
    private OffsetDateTime createdAt;
    private OffsetDateTime publishedAt;
    private int pageIdx;
    private int pageSize;
    private long total;

    public ListFilesResp toListFilesResp() {
        return new ListFilesResp(
                artifactId,
                commitId,
                fileRespList,
                commitStatus,
                createdAt,
                publishedAt,
                pageIdx,
                pageSize,
                total
        );
    }
}
