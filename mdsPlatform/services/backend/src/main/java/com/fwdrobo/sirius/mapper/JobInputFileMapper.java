package com.fwdrobo.sirius.mapper;

import com.fwdrobo.sirius.entity.job.JobInputFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

@Mapper
public interface JobInputFileMapper {

    int batchInsertJobInputFiles(@Param("jobId") UUID jobId, @Param("repoId") UUID repoId, @Param("logicalPaths") List<String> logicalPaths);

    int deleteByJobId(@Param("jobId") UUID jobId);

    // NOTE: logicalPath may be null when mapper SQL maps sentinel '*ALL*' to null.
    // Repo-wide sentinel stored in DB should be mapped to paths=[] in service layer.
    List<JobInputFile> findByJobId(@Param("jobId") UUID jobId);
}
