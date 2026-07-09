package com.fwdrobo.sirius.dto.file;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UploadFileResp {
    private UUID artifactId;
    private UUID commitId;
    private UUID fileId;
    private UUID deviceId;
    private String path;
    private long size;
    private String contentType;
}
