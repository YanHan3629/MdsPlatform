package com.fwdrobo.sirius.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fwdrobo.sirius.entity.job.Job;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

@Mapper
public interface JobMapper {


    UUID insertJob(@Param("jobName") String jobName,
                   @Param("jobCategory") String jobCategory,
                   @Param("image") String image,
                   @Param("cmd") String cmd,
                   @Param("paramsJson") JsonNode paramsJson,
                   @Param("createdBy") UUID createdBy,
                   @Param("orgId") UUID orgId);


    int updateJob(@Param("jobId") UUID jobId,
                  @Param("jobName") String jobName,
                  @Param("jobCategory") String jobCategory,
                  @Param("image") String image,
                  @Param("cmd") String cmd,
                  @Param("paramsJson") JsonNode paramsJson);

    Job findById(@Param("jobId") UUID jobId);

    long countByFilters(@Param("jobName") String jobName,
                        @Param("jobCategory") String jobCategory,
                        @Param("image") String image);

    List<Job> selectPageByFilters(@Param("jobName") String jobName,
                                  @Param("jobCategory") String jobCategory,
                                  @Param("image") String image,
                                  @Param("offset") int offset,
                                  @Param("size") int size);
}
