package com.fwdrobo.sirius.mapper;

import com.fwdrobo.sirius.entity.file.AddOrReplaceFileResult;
import com.fwdrobo.sirius.dto.file.FileResp;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

@Mapper
public interface ArtifactFileMapper {
    // 新增或替换
    AddOrReplaceFileResult addOrReplaceFile(
            @Param("commitId") UUID commitId,
            @Param("logicalPath") String logicalPath,
            @Param("bucketName") String bucketName,
            @Param("fileKey") String fileKey,
            @Param("contentType") String contentType,
            @Param("deviceId") UUID deviceId
    );

    // 新增或替换
    AddOrReplaceFileResult addOrReplaceFileFull(
            @Param("commitId") UUID commitId,
            @Param("logicalPath") String logicalPath,
            @Param("bucketName") String bucketName,
            @Param("fileKey") String fileKey,
            @Param("versionId") String versionId,
            @Param("etag") String etag,
            @Param("size") long size,
            @Param("contentType") String contentType,
            @Param("deviceId") UUID deviceId
    );

    // 通过 ID 集合分页查询对应信息,请保证集合里有元素
    List<FileResp> selectFilesByIds(@Param("ids") List<UUID> ids);

    // 通过 ID 删除对应记录
    int deleteByIdAndCommitId(@Param("fileId") UUID fileId, @Param("commitId") UUID commitId);

    // 通过 commitId 查询该 commit 下所有对象存储 key（用于清理存储侧对象）
    List<String> selectFileKeysByCommitId(@Param("commitId") UUID commitId);

    /**
     * 通过 commitId 与逻辑路径查询文件信息。
     */
    FileResp selectByCommitIdAndPath(@Param("commitId") UUID commitId, @Param("logicalPath") String logicalPath);
}
