package com.fwdrobo.sirius.dto.artifact;

import com.fwdrobo.sirius.dto.user.UserInfo;
import com.fwdrobo.sirius.entity.artifact.ArtifactVisibility;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class ArtifactRepoResponse {
    private UUID repoId;
    private String repoName;
    private String repoType;
    private String description;
    private UserInfo ownerUser;
    private ArtifactVisibility visibility;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Long publishedCount;  // published commit 数量
    private Long draftCount;      // draft commit 数量
}
