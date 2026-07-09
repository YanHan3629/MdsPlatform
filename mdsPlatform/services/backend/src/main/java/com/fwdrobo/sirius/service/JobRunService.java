package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.dto.PageRes;
import com.fwdrobo.sirius.dto.job.RunResponse;
import com.fwdrobo.sirius.dto.user.UserInfo;
import com.fwdrobo.sirius.entity.job.JobRun;
import com.fwdrobo.sirius.mapper.JobRunMapper;
import com.fwdrobo.sirius.util.ExceptionUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class JobRunService {

    private final JobRunMapper jobRunMapper;
    private final UserService userService;


    public JobRunService(JobRunMapper jobRunMapper, UserService userService) {
        this.jobRunMapper = jobRunMapper;
        this.userService = userService;
    }

    public RunResponse getJobRunById(UUID runId) {
        JobRun jobRun = jobRunMapper.selectByRunId(runId)
                .orElseThrow(() -> ExceptionUtils.notFound("JobRun不存在"));
        return toResponse(jobRun);
    }

    public PageRes<RunResponse> listPage(int page, int size, String jobId, String dockerStatus, String containerStatus, String containerId, String containerName) {
        List<JobRun> items;
        // 计算总记录数
        long total = jobRunMapper.countByFilters(jobId, dockerStatus, containerStatus, containerId, containerName);
        if (total == 0) {
            // 无记录，直接赋为空页
            items = List.of();
        } else {
            // 获取分页数据
            int offset = Math.max(0, (page - 1) * size);
            items = jobRunMapper.selectPageByFilters(jobId, dockerStatus, containerStatus, containerId, containerName, offset, size);
            items = items != null ? items : List.of();  // 强保护，防止返回 null
        }
        List<RunResponse> responses = items.stream().map(this::toResponse).toList();

        // 计算总页数
        int totalPages = (int) (total + size - 1) / size;
        if (totalPages > 0 && page > totalPages) {
            throw ExceptionUtils.notFound("页数超过上限");
        }

        return new PageRes<>(total, totalPages, page, size, responses);
    }

    private RunResponse toResponse(JobRun jobRun) {
        if (jobRun == null) {
            return null;
        }
        UserInfo createdBy = new UserInfo(jobRun.getCreatedBy(), userService.resolveUsername(jobRun.getCreatedBy()));
        return new RunResponse(
                jobRun.getRunId(),
                jobRun.getJobId(),
                jobRun.getRunStatus(),
                jobRun.getStartedAt(),
                jobRun.getFinishedAt(),
                jobRun.getErrorMessage(),
                jobRun.getLogsUri(),
                jobRun.getMetrics(),
                createdBy,
                jobRun.getCreatedAt(),
                jobRun.getContainerId(),
                jobRun.getContainerName()
        );
    }
}
