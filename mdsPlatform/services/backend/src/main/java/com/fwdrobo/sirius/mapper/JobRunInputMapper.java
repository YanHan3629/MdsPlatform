package com.fwdrobo.sirius.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

@Mapper
public interface JobRunInputMapper {


    int insert(@Param("runId") UUID runId,
               @Param("jobId") UUID jobId,
               @Param("repoId") UUID repoId,
               @Param("commitId") UUID commitId,
               @Param("order") int order,
               @Param("paramsJson") String paramsJson);
}
