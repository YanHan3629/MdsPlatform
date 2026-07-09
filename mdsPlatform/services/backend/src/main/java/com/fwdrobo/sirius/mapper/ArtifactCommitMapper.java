package com.fwdrobo.sirius.mapper;

import com.fwdrobo.sirius.dto.artifact.ListCommitsQuery;
import com.fwdrobo.sirius.entity.artifact.ArtifactCommit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

@Mapper
public interface ArtifactCommitMapper {
    int insert(ArtifactCommit commit);

    ArtifactCommit selectByRepoAndCommit(@Param("repoId") UUID repoId, @Param("commitId") UUID commitId);

    List<ArtifactCommit> selectByRepoId(@Param("repoId") UUID repoId);

    /**
     * 分页查询 commits
     */
    List<ArtifactCommit> selectByRepoIdPaged(@Param("query") ListCommitsQuery query);

    /**
     * 统计 commits 总数
     */
    long countByRepoId(@Param("query") ListCommitsQuery query);

    ArtifactCommit selectDraftByRepoId(@Param("repoId") UUID repoId);

    int publishDraft(
            @Param("repoId") UUID repoId,
            @Param("commitId") UUID commitId,
            @Param("publishedBy") UUID publishedBy,
            @Param("comment") String comment
    );

    ArtifactCommit selectLatestPublishedByRepoId(@Param("repoId") UUID repoId);

    // 查 commit 状态，不存在返回 null
    String selectStatusByRepoAndId(@Param("repoId") UUID repoId, @Param("commitId") UUID commitId);

    // 更新 commit 状态
    int updateStatus(@Param("commitId") UUID commitId, @Param("status") String status);

    List<UUID> selectCommitIdsByRepoId(@Param("repoId") UUID repoId);

    /**
     * 统计指定 repo 下指定状态的 commit 数量
     * @param repoId repo ID
     * @param status commit 状态（DRAFT, PUBLISHED），如果为 null 则统计所有
     * @return commit 数量
     */
    long countByRepoIdAndStatus(@Param("repoId") UUID repoId, @Param("status") String status);

}
