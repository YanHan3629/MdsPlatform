package com.fwdrobo.sirius.mapper;

import com.fwdrobo.sirius.entity.mm.MmDataset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

@Mapper
public interface MmDatasetMapper {
    int insert(MmDataset dataset);

    MmDataset selectById(@Param("datasetId") UUID datasetId);

    MmDataset selectBySpaceIdAndName(@Param("spaceId") UUID spaceId, @Param("datasetName") String datasetName);

    List<MmDataset> selectBySpaceId(@Param("spaceId") UUID spaceId, @Param("limit") int limit, @Param("offset") int offset);

    long countBySpaceId(@Param("spaceId") UUID spaceId);
}
