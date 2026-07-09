package com.fwdrobo.sirius.container;

import com.fwdrobo.sirius.docker.service.DockerCli;
import com.fwdrobo.sirius.entity.job.ContainerStatus;
import com.fwdrobo.sirius.entity.job.JobRun;
import com.fwdrobo.sirius.entity.job.RunStatus;
import com.fwdrobo.sirius.mapper.JobMapper;
import com.fwdrobo.sirius.mapper.JobRunMapper;
import com.fwdrobo.sirius.service.TrialLimitService;
import com.fwdrobo.sirius.util.ExceptionUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class ContainerLauncher {

    private final JobRunMapper repo;
    private final DockerCli dockerCli;
    private final TransactionTemplate tx;
    private final ContainerInspector containerInspector;
    private final ContainerLogStreamer logStreamer;
    private final JobMapper jobMapper;
    private final TrialLimitService trialLimitService;

    public ContainerLauncher(
            JobRunMapper repo,
            DockerCli dockerCli,
            PlatformTransactionManager txManager,
            ContainerInspector containerInspector,
            ContainerLogStreamer logStreamer,
            JobMapper jobMapper,
            TrialLimitService trialLimitService) {
        this.repo = repo;
        this.dockerCli = dockerCli;
        this.tx = new TransactionTemplate(txManager);
        this.containerInspector = containerInspector;
        this.logStreamer = logStreamer;
        this.jobMapper = jobMapper;
        this.trialLimitService = trialLimitService;
    }
    /**
     * 异步启动容器：volume create + docker run + 写回containerId + inspect刷新
     * dockerHost：本次容器应创建/运行/inspect/port 查询所在的 docker daemon
     */
    @Async("jobLauncherExecutor")
    public void launch(UUID runId, List<String> volArgs, List<String> runArgs, String dockerHost) {
        log.info("[launcher] launch begin runId={}, dockerHost={}", runId, dockerHost);

        // 先把 runStatus 从 QUEUED 推进到 RUNNING（幂等）
        tx.executeWithoutResult(status -> {
            repo.selectByRunId(runId).ifPresent(jr -> {
                if (jr.getRunStatus() == null || jr.getRunStatus() == RunStatus.QUEUED) {
                    jr.setRunStatus(RunStatus.RUNNING);
                    jr.setLastSeenAt(OffsetDateTime.now());
                    repo.updateStatusAndTimes(jr);
                }
            });
        });

        try {
            //todo 暂时注释掉，服务器上因为大量测试的job volume占用了很多资源
//            DockerCli.ExecResult vr = dockerCli.exec(volArgs, dockerHost);
//            if (vr.exitCode() != 0) {
//                failRun(runId, "docker volume create failed: " + vr.stderr());
//                return;
//            }

            //docker run（事务外）
            log.info("[launcher] docker run: {}", String.join(" ", runArgs));
            DockerCli.ExecResult rr = dockerCli.exec(runArgs, dockerHost);
            if (rr.exitCode() != 0) {
                failRun(runId, "docker run failed: " + rr.stderr());
                return;
            }

            String containerId = rr.stdout().trim();
            log.info("[launcher] docker run succeeded runId={}, containerId={}", runId, containerId);

            //读出 jobRun（短事务）
            JobRun jobRun = tx.execute(status ->
                    repo.selectByRunId(runId)
                            .orElseThrow(() -> ExceptionUtils.notFound("JobRun不存在, runId=" + runId))
            );

            //写回 containerId + containerStatus（短事务）
            tx.executeWithoutResult(status -> {
                jobRun.setContainerId(containerId);
                jobRun.setContainerStatus(ContainerStatus.CREATED);
                if (!StatusMapper.isTerminalOrCanceling(jobRun.getRunStatus())) {
                    jobRun.setRunStatus(RunStatus.RUNNING);
                }
                jobRun.setLastSeenAt(OffsetDateTime.now());
                repo.updateContainerByRunId(jobRun);
            });

            //启动日志流采集（注意：如果 logStreamer 内部会 docker logs/follow，也需要支持 dockerHost）
            try {
                logStreamer.startStreaming(runId, jobRun.getJobId(), containerId, dockerHost);
                log.info("[launcher] started log streaming for runId={}", runId);
            } catch (Exception ex) {
                log.warn("[launcher] failed to start log streaming for runId={}, err={}", runId, ex.getMessage());
            }

            //inspect 刷新
            try {
                containerInspector.refreshByRunId(runId, containerId, dockerHost);
            } catch (Exception ex) {
                log.warn("[launcher] refreshFromInspect failed runId={}, err={}", runId, ex.getMessage());
            }

            log.info("[launcher] launch done runId={}", runId);
        } catch (Exception ex) {
            log.error("[launcher] unexpected error runId={}", runId, ex);
            failRun(runId, "launcher error: " + ex.getMessage());
        }
    }

    private void failRun(UUID runId, String msg) {
        log.warn("[launcher] fail runId={}, msg={}", runId, msg);
        tx.executeWithoutResult(status -> {
            repo.selectByRunId(runId).ifPresent(jr -> {
                if (jr.getRunStatus() == RunStatus.CANCELING || jr.getRunStatus() == RunStatus.CANCELED) {
                    jr.setErrorMessage(msg);
                    jr.setLastSeenAt(OffsetDateTime.now());
                    repo.updateContainerByRunId(jr);
                    trialLimitService.releaseRunRuntimeQuotaIfNeeded(jr);
                    return;
                }
                jr.setRunStatus(RunStatus.FAILED);
                jr.setContainerStatus(ContainerStatus.FAILED);
                jr.setErrorMessage(msg);
                jr.setFinishedAt(OffsetDateTime.now());
                jr.setLastSeenAt(OffsetDateTime.now());
                repo.updateContainerByRunId(jr);
                trialLimitService.releaseRunRuntimeQuotaIfNeeded(jr);
            });
        });
    }

}
