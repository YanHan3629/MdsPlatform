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
    @org.springframework.beans.factory.annotation.Autowired
    private com.fwdrobo.sirius.security.JobResourceAuthority jobResourceAuthority;

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
        if(page<1||size<1||size>1000)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"INVALID_PAGINATION");
        List<UUID> visible=jobResourceAuthority.visibleRunIds(UUID.fromString(jobId),dockerStatus,containerStatus,containerId,containerName);
        long total=visible.size();
        long offset=(long)(page-1)*size;
        List<JobRun> items=offset>=total?List.of():visible.subList((int)offset,(int)Math.min(total,offset+size))
                .stream().map(id->jobRunMapper.selectByRunId(id).orElseThrow(()->ExceptionUtils.notFound("JobRun不存在"))).toList();
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
        jobResourceAuthority.require(jobRun.getJobId(), jobRun.getRunId(), "job.read");
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
