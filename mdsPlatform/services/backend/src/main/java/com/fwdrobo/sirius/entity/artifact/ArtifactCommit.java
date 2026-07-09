package com.fwdrobo.sirius.entity.artifact;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Artifact Commit：对某个 Artifact Repo 的一次不可变快照。
 *
 * 注意：
 * - DRAFT：草稿态（同一个 repo 约束只允许存在一个 DRAFT）
 * - PUBLISHED：发布态（可作为"HEAD"被下游引用）
 */
@Getter
@Setter
public class ArtifactCommit {
    private UUID commitId;
    private UUID repoId;

    /**
     * {@link CommitStatus}
     */
    private CommitStatus commitStatus;

    /**
     * 可选的 commit 来源标识符（如 UPLOAD/JOB/IMPORT/SYSTEM 等）
     */
    private String commitSource;

    private UUID createdBy;
    private UUID publishedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime publishedAt;

    /**
     * commit 说明/注释，特别用于发布时的说明
     */
    private String comment;

    /**
     * 扩展元信息，存储为 JSONB
     */
    private JsonNode meta;
}
