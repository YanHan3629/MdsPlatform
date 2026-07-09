package com.fwdrobo.sirius.mapper;

import com.fwdrobo.sirius.dto.artifact.ListArtifactReposQuery;
import com.fwdrobo.sirius.entity.artifact.ArtifactRepo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

@Mapper
public interface ArtifactRepoMapper {

    int insert(ArtifactRepo repo);

    int update(ArtifactRepo repo);
    
    ArtifactRepo selectById(@Param("repoId") UUID repoId);

    ArtifactRepo selectByName(@Param("repoName") String repoName);

    List<ArtifactRepo> selectAll();
    
    /**
     * 分页查询 artifact repos（支持排序、过滤）
     */
    List<ArtifactRepo> selectByPage(@Param("query") ListArtifactReposQuery query);
    
    /**
     * 统计符合条件的 artifact repos 总数
     */
    long countByQuery(@Param("query") ListArtifactReposQuery query);

    // 当前仅查询 repo_id 和 repo_name，预留方法名用于后续扩展字段。
    List<ArtifactRepo> selectArtifactsByIds(@Param("repoIds") List<UUID> repoIds);

    int deleteById(@Param("repoId") UUID repoId);

}
