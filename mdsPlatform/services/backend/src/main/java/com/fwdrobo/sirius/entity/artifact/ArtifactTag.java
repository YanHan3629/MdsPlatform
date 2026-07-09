package com.fwdrobo.sirius.entity.artifact;

import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Artifact 标签实体。
 */
@Getter
@Setter
public class ArtifactTag {

    /**
     * 仓库 ID。
     */
    private UUID repoId;

    /**
     * 标签名。
     */
    private String tagName;

    /**
     * 标签指向的提交 ID。
     */
    private UUID commitId;

    /**
     * 创建人。
     */
    private UUID createdBy;

    /**
     * 创建时间。
     */
    private OffsetDateTime createdAt;

    /**
     * 更新时间。
     */
    private OffsetDateTime updatedAt;
}
