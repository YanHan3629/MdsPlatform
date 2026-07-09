package com.fwdrobo.sirius.dto.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /api/artifacts/{artifactId}/commits
 */
@Getter
@Setter
public class CreateCommitReq {

    /**
     * 可选的 commit 来源标识符（如 UPLOAD/JOB/IMPORT/SYSTEM 等）
     */
    @Size(max = 32, message = "commitSource 长度不能超过 32 字符")
    private String commitSource;

    /**
     * 可选的 commit 说明/注释
     */
    @Size(max = 500, message = "comment 长度不能超过 500 字符")
    private String comment;

    /**
     * 可选的 commit metadata（JSON）
     */
    private JsonNode meta;
}
