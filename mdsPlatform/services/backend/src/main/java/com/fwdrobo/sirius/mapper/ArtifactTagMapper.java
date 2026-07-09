package com.fwdrobo.sirius.mapper;

import com.fwdrobo.sirius.entity.artifact.ArtifactTag;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

/**
 * Artifact 标签查询 Mapper。
 */
public interface ArtifactTagMapper {

    /**
     * 按仓库与标签查询。
     */
    ArtifactTag selectByRepoAndTag(@Param("repoId") UUID repoId, @Param("tagName") String tagName);

    /**
     * 按仓库查询全部标签。
     */
    List<ArtifactTag> selectByRepoId(@Param("repoId") UUID repoId);
}
