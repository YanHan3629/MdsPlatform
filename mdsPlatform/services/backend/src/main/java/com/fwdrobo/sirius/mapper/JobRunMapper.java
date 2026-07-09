package com.fwdrobo.sirius.mapper;

import com.fwdrobo.sirius.entity.job.JobRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface JobRunMapper {

    int insert(JobRun jobRun);

    Optional<JobRun> selectByRunId(@Param("runId") UUID runId);

    /**
     * 推荐：执行流程里常用的安全更新（状态/时间/错误/exit code）
     */
    int updateStatusAndTimes(JobRun jobRun);

    Optional<JobRun> findByContainerId(String containerId);


    int updateContainerByRunId(JobRun e); // where job_id=? and version=?


    List<JobRun> findRunsByJobId(@Param("jobId") UUID jobId);

    /**
     * 查询单个 Job 的最近一次运行记录
     * @param jobId Job ID
     * @return 该 Job 的最近一次 JobRun，如果不存在则返回 null
     */
    JobRun selectLatestRunByJobId(@Param("jobId") UUID jobId);

    /**
     * 批量查询指定 Job 的最近一次运行记录
     * @param jobIds Job ID 列表
     * @return 每个 Job 的最近一次 JobRun（如果存在）
     */
    List<JobRun> selectLatestRunsByJobIds(@Param("jobIds") List<UUID> jobIds);

    List<JobRun> selectExpiredRuns(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * 将运行时资源释放标记置为已释放（仅未释放记录会更新）。
     */
    int markRuntimeQuotaReleased(@Param("runId") UUID runId);

    long countByFilters(@Param("jobId") String jobId,
                        @Param("dockerStatus") String dockerStatus,
                        @Param("containerStatus") String containerStatus,
                        @Param("containerId") String containerId,
                        @Param("containerName") String containerName);

    List<JobRun> selectPageByFilters(@Param("jobId") String jobId,
                                     @Param("dockerStatus") String dockerStatus,
                                     @Param("containerStatus") String containerStatus,
                                     @Param("containerId") String containerId,
                                     @Param("containerName") String containerName,
                                     @Param("offset") int offset,
                                     @Param("size") int size);
}
