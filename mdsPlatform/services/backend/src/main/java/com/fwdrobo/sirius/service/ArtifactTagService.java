package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.dto.artifact.ArtifactTagItemResponse;
import com.fwdrobo.sirius.dto.artifact.ArtifactTagsResponse;
import com.fwdrobo.sirius.entity.artifact.ArtifactCommit;
import com.fwdrobo.sirius.entity.artifact.ArtifactRepo;
import com.fwdrobo.sirius.entity.artifact.ArtifactTag;
import com.fwdrobo.sirius.mapper.ArtifactCommitMapper;
import com.fwdrobo.sirius.mapper.ArtifactTagMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Artifact 标签查询服务。
 */
@Service
public class ArtifactTagService {

    private static final String SPECIAL_LATEST = "LATEST";

    private final ArtifactRepoService artifactRepoService;
    private final ArtifactCommitMapper artifactCommitMapper;
    private final ArtifactTagMapper artifactTagMapper;

    public ArtifactTagService(ArtifactRepoService artifactRepoService,
                              ArtifactCommitMapper artifactCommitMapper,
                              ArtifactTagMapper artifactTagMapper) {
        this.artifactRepoService = artifactRepoService;
        this.artifactCommitMapper = artifactCommitMapper;
        this.artifactTagMapper = artifactTagMapper;
    }

    /**
     * 查询仓库标签列表。
     */
    public ArtifactTagsResponse getArtifactTags(UUID artifactId) {
        ArtifactRepo repo = artifactRepoService.getRepoOrNotFound(artifactId);
        artifactRepoService.assertUrdfRepoType(repo);

        List<ArtifactTagItemResponse> tags = new ArrayList<>();
        ArtifactCommit latestCommit = artifactCommitMapper.selectLatestPublishedByRepoId(artifactId);
        UUID latestCommitId = latestCommit == null ? null : latestCommit.getCommitId();
        if (latestCommitId != null) {
            tags.add(new ArtifactTagItemResponse(SPECIAL_LATEST, true, latestCommitId));
        }

        List<ArtifactTag> dbTags = artifactTagMapper.selectByRepoId(artifactId);
        for (ArtifactTag dbTag : dbTags) {
            tags.add(new ArtifactTagItemResponse(dbTag.getTagName(), false, dbTag.getCommitId()));
        }
        return new ArtifactTagsResponse(artifactId, tags);
    }
}
