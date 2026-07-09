package com.fwdrobo.sirius.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

@Mapper
public interface JobInputMapper {

    int deleteByJobId(@Param("jobId") UUID jobId);

    int insert(@Param("jobId") UUID jobId,
               @Param("repoId") UUID repoId,
               @Param("paramsJson") String paramsJson);
}


