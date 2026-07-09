package com.fwdrobo.sirius.dto.file;

import com.fwdrobo.sirius.validation.ValidLogicalPath;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BeginFileWriteReq {
    @ValidLogicalPath
    private String path;
    private String contentType;
    @Min(value = 1, message = "expireSeconds 范围: [1, 43200]")
    @Max(value = 12 * 60 * 60, message = "expireSeconds 范围: [1, 43200]")
    private int expireSeconds;
}
