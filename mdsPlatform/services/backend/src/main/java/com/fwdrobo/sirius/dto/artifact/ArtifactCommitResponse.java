package com.fwdrobo.sirius.dto.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fwdrobo.sirius.dto.user.UserInfo;
import com.fwdrobo.sirius.entity.artifact.CommitStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class ArtifactCommitResponse {
    private UUID commitId;
    private UUID repoId;
    private CommitStatus commitStatus;
    private String commitSource;
    private UserInfo createdBy;
    private UserInfo publishedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime publishedAt;
    private String comment;
    private JsonNode meta;
}
