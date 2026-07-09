package com.fwdrobo.sirius.mapper;

import com.fwdrobo.sirius.entity.mm.MmAsset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

@Mapper
public interface MmAssetMapper {
    MmAsset selectById(@Param("assetId") UUID assetId);

    List<MmAsset> selectByDatasetVersionId(@Param("datasetVersionId") UUID datasetVersionId,
                                           @Param("keyword") String keyword,
                                           @Param("tagName") String tagName,
                                           @Param("categoryName") String categoryName,
                                           @Param("limit") int limit,
                                           @Param("offset") int offset);

    long countByDatasetVersionId(@Param("datasetVersionId") UUID datasetVersionId,
                                 @Param("keyword") String keyword,
                                 @Param("tagName") String tagName,
                                 @Param("categoryName") String categoryName);

    List<String> selectCaptions(@Param("assetId") UUID assetId);

    List<String> selectTags(@Param("assetId") UUID assetId);

    List<String> selectCategories(@Param("assetId") UUID assetId);

    UUID findSpaceIdByAssetId(@Param("assetId") UUID assetId);

    UUID findDatasetIdByAssetId(@Param("assetId") UUID assetId);

    UUID findOrCreateTag(@Param("spaceId") UUID spaceId,
                         @Param("tagName") String tagName,
                         @Param("createdBy") UUID createdBy);

    int bindTag(@Param("assetId") UUID assetId,
                @Param("tagId") UUID tagId,
                @Param("sourceType") String sourceType);

    int deleteTagsByAssetId(@Param("assetId") UUID assetId);

    int bindCategory(@Param("assetId") UUID assetId,
                     @Param("categoryId") UUID categoryId,
                     @Param("sourceType") String sourceType);

    int deleteCategoriesByAssetId(@Param("assetId") UUID assetId);

    int deleteByDatasetVersionId(@Param("datasetVersionId") UUID datasetVersionId);

    int upsertBatch(@Param("items") List<MmAsset> items);
}
