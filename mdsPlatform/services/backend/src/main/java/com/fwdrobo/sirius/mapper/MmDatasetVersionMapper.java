package com.fwdrobo.sirius.mapper;

import com.fwdrobo.sirius.entity.mm.MmDatasetVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

@Mapper
public interface MmDatasetVersionMapper {
    int insert(MmDatasetVersion version);

    int update(MmDatasetVersion version);

    MmDatasetVersion selectById(@Param("versionId") UUID versionId);

    MmDatasetVersion selectByDatasetIdAndVersionName(@Param("datasetId") UUID datasetId,
                                                     @Param("versionName") String versionName);

    List<MmDatasetVersion> selectByDatasetId(@Param("datasetId") UUID datasetId,
                                             @Param("limit") int limit,
                                             @Param("offset") int offset);

    long countByDatasetId(@Param("datasetId") UUID datasetId);

    int updateActiveIndexVersion(@Param("versionId") UUID versionId,
                                 @Param("activeIndexVersionId") UUID activeIndexVersionId,
                                 @Param("versionStatus") String versionStatus);
}
