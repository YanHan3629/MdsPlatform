package com.fwdrobo.sirius.dto.artifact;

import com.fwdrobo.sirius.entity.artifact.ArtifactVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * POST /artifacts
 */
@Getter
@Setter
public class ArtifactRepoReq {
    @NotBlank(message = "repoName is required")
    @Size(max = 255, message = "repoName 长度不能超过 255 字符")
    private String repoName;

    @NotBlank(message = "repoType is required")
    @Size(max = 64, message = "repoType 长度不能超过 64 字符")
    private String repoType;

    private ArtifactVisibility visibility;

    @Size(max = 200, message = "description 超出最大长度限制，200字符")
    private String description;
}
