package com.fwdrobo.sirius.dto.file;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BeginFileWriteResp {
    private String path;
    private String objectKey;
    private String s3UploadUrl;
    private String s3UploadToken;
    private String method;
}
