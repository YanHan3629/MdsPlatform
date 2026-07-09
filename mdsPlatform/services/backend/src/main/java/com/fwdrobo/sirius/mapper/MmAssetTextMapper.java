package com.fwdrobo.sirius.mapper;

import com.fwdrobo.sirius.entity.mm.MmAssetText;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

@Mapper
public interface MmAssetTextMapper {
    int insertBatch(@Param("items") List<MmAssetText> items);

    List<MmAssetText> selectByAssetId(@Param("assetId") UUID assetId);

    int deleteByAssetIds(@Param("assetIds") List<UUID> assetIds);
}
