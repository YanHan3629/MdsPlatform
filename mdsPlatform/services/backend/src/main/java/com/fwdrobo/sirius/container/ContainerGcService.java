package com.fwdrobo.sirius.container;

import com.fwdrobo.sirius.docker.entity.DockerHostResolver;
import com.fwdrobo.sirius.docker.service.DockerCli;
import com.fwdrobo.sirius.entity.job.ContainerStatus;
import com.fwdrobo.sirius.entity.job.JobRun;
import com.fwdrobo.sirius.mapper.JobRunMapper;
import com.fwdrobo.sirius.service.MinioService;
import com.fwdrobo.sirius.service.TrialLimitService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ContainerGcService {

    private final JobRunMapper jobRunMapper;
    private final DockerCli dockerCli;
    @SuppressWarnings("unused")
    private final MinioService minioService;
    private final DockerHostResolver resolver;
    private final TrialLimitService trialLimitService;

    public ContainerGcService(
            JobRunMapper jobRunMapper,
            DockerCli dockerCli,
            MinioService minioService,
            DockerHostResolver resolver,
            TrialLimitService trialLimitService
    ) {
        this.jobRunMapper = jobRunMapper;
        this.dockerCli = dockerCli;
        this.minioService = minioService;
        this.resolver = resolver;
        this.trialLimitService = trialLimitService;
    }

    // 每天凌晨三点跑
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void gcExpiredRuns() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(7);
        List<JobRun> expired = jobRunMapper.selectExpiredRuns(cutoff);

        for (JobRun e : expired) {
            UUID runId = e.getRunId();
            String containerStr = (e.getContainerId() != null && !e.getContainerId().isBlank())
                    ? e.getContainerId()
                    : e.getContainerName();

            String volumeName = "vol_" + runId;
            String dockerHost = resolver.resolve(e.getDockerHost() == null ? "GPU_01" : e.getDockerHost());

            // 强制删除容器（即使还在跑也会被 kill）
            if (containerStr != null && !containerStr.isBlank()) {
                try {
                    DockerCli.ExecResult rr = dockerCli.exec(List.of("-H", dockerHost, "rm", "-f", containerStr));
                    if (rr.exitCode() != 0) {
                        // 容器可能已经没了，允许继续
                        log.warn("gc rm container failed dockerHost={}, runId={}, stderr={}", e.getDockerHost(), runId, rr.stderr());
                    }
                } catch (Exception ex) {
                    log.warn("gc rm container exception dockerHost={}, runId={}, ex={}", e.getDockerHost(), runId, ex.toString());
                }
            }

            // 删除 volume（如果还被引用会失败，但上面 rm -f 后通常就能删）
            try {
                DockerCli.ExecResult vr = dockerCli.exec(List.of("-H", dockerHost, "volume", "rm", volumeName));
                if (vr.exitCode() != 0) {
                    log.warn("gc rm volume failed dockerHost={}, runId={}, stderr={}", e.getDockerHost(), runId, vr.stderr());
                }
            } catch (Exception ex) {
                log.warn("gc rm volume exception dockerHost={}, runId={}, ex={}", e.getDockerHost(), runId, ex.toString());
            }

            // 3) 更新DB状态
            trialLimitService.releaseRunRuntimeQuotaIfNeeded(e);
            e.setContainerStatus(ContainerStatus.REMOVED);
            e.setFinishedAt(OffsetDateTime.now());
            e.setLastSeenAt(OffsetDateTime.now());
            jobRunMapper.updateStatusAndTimes(e);
            
            // TODO: 删除 MinIO 中的日志（可选，30天后删除）
            // 目前暂不删除日志，保留用于审计和问题排查
            // 如果需要删除，可以取消注释以下代码：
            /*
            try {
                String logPrefix = String.format("job/%s/%s/logs/", e.getJobId(), runId);
                List<String> logParts = minioService.listObjects(logPrefix);
                for (String logKey : logParts) {
                    minioService.removeObject(logKey);
                }
                log.info("Deleted {} log files for runId={}", logParts.size(), runId);
            } catch (Exception ex) {
                log.warn("Failed to delete logs for runId={}", runId, ex);
            }
            */
        }
    }
}
