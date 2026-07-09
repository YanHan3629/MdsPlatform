package com.fwdrobo.sirius.dto.artifact;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /api/artifacts/{artifactId}/commits/{commitId}/publish
 */
@Getter
@Setter
public class PublishCommitReq {

    /**
     * commit 说明/注释，描述本次发布的内容或变更
     */
    @NotBlank(message = "comment is required")
    @Size(max = 2000, message = "comment 长度不能超过 2000 字符")
    private String comment;
}
