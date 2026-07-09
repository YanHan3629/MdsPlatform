package com.fwdrobo.sirius.mapper;

import com.fwdrobo.sirius.entity.mm.MmIndexVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

@Mapper
public interface MmIndexVersionMapper {
    int insert(MmIndexVersion indexVersion);

    int update(MmIndexVersion indexVersion);

    MmIndexVersion selectById(@Param("indexVersionId") UUID indexVersionId);

    MmIndexVersion selectLatestByDatasetVersionId(@Param("datasetVersionId") UUID datasetVersionId);

    List<MmIndexVersion> selectByDatasetVersionId(@Param("datasetVersionId") UUID datasetVersionId);

    int markReady(@Param("indexVersionId") UUID indexVersionId,
                  @Param("indexCommitId") UUID indexCommitId,
                  @Param("imageIndexPath") String imageIndexPath,
                  @Param("textIndexPath") String textIndexPath,
                  @Param("metadataPath") String metadataPath,
                  @Param("manifestPath") String manifestPath,
                  @Param("embeddingDim") Integer embeddingDim,
                  @Param("imageCount") Long imageCount,
                  @Param("textCount") Long textCount);

    int markFailed(@Param("indexVersionId") UUID indexVersionId,
                   @Param("errorMessage") String errorMessage);

    int markDeprecated(@Param("indexVersionId") UUID indexVersionId);
}
