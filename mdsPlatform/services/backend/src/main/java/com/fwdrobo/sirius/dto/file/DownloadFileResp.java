package com.fwdrobo.sirius.dto.file;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DownloadFileResp {
    private String path;
    private String objectKey;
    private String url;
    private String method;
}
