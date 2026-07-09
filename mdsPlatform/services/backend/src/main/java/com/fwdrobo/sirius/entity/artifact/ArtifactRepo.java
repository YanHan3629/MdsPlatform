package com.fwdrobo.sirius.entity.artifact;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class ArtifactRepo {
    private UUID repoId;
    private String repoName;
    private String repoType;
    private String description;
    private UUID ownerUserId;
    private String visibility;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private UUID orgId;
}
