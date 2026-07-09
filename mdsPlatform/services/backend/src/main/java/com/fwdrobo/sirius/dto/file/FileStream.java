package com.fwdrobo.sirius.dto.file;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.io.InputStream;

@Getter
@Accessors(fluent = true)
@AllArgsConstructor
public class FileStream implements AutoCloseable {
    private String path;
    private String contentType;
    private String objectKey;
    private long size;
    private InputStream inputStream;

    @Override
    public void close() throws Exception {
        if (inputStream != null) {
            inputStream.close();
        }
    }
}
