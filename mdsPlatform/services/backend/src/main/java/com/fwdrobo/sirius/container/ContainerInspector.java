package com.fwdrobo.sirius.container;

import com.fwdrobo.sirius.docker.service.DockerCli;
import com.fwdrobo.sirius.entity.job.ContainerStatus;
import com.fwdrobo.sirius.entity.job.JobRun;
import com.fwdrobo.sirius.entity.job.RunStatus;
import com.fwdrobo.sirius.mapper.JobRunMapper;
import com.fwdrobo.sirius.service.TrialLimitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class ContainerInspector {

    private final DockerCli dockerCli;
    private final JobRunMapper repo;
    private final TransactionTemplate tx;
    private final TrialLimitService trialLimitService;

    public ContainerInspector(
            DockerCli dockerCli,
            JobRunMapper repo,
            PlatformTransactionManager txManager,
            TrialLimitService trialLimitService
    ) {
        this.dockerCli = dockerCli;
        this.repo = repo;
        this.tx = new TransactionTemplate(txManager);
        this.trialLimitService = trialLimitService;
    }

    public void refreshByRunId(UUID runId, String containerIdHint, String dockerHost) throws Exception {
        JobRun jr = tx.execute(status ->
                repo.selectByRunId(runId).orElse(null)
        );
        if (jr == null) {
            log.warn("[inspect] JobRun not found runId={}", runId);
            return;
        }
        if ((jr.getContainerId() == null || jr.getContainerId().isBlank())
                && containerIdHint != null && !containerIdHint.isBlank()) {
            jr.setContainerId(containerIdHint);
        }
        if (jr.getContainerId() == null || jr.getContainerId().isBlank()) {
            log.debug("[inspect] skip: no containerId runId={}", runId);
            return;
        }

        try {
            DockerCli.ExecResult r = dockerCli.exec(List.of("inspect", jr.getContainerId()), dockerHost);
            if (r.exitCode() != 0) {
                String err = r.stderr();
                tx.executeWithoutResult(status -> {
                    if (err.contains("No such object")) {
                        jr.setContainerStatus(ContainerStatus.MISSING);
                        jr.setDockerStatus(null);
                        // canceling -> canceled；非终态 -> failed
                        RunStatus cur = jr.getRunStatus();
                        if (cur == RunStatus.CANCELING) jr.setRunStatus(RunStatus.CANCELED);
                        else if (!StatusMapper.isTerminalOrCanceling(cur)) jr.setRunStatus(RunStatus.FAILED);
                        jr.setFinishedAt(OffsetDateTime.now());
                    } else {
                        jr.setContainerStatus(ContainerStatus.UNKNOWN);
                        jr.setErrorMessage("inspect failed: " + err);
                        RunStatus cur = jr.getRunStatus();
                        if (!StatusMapper.isTerminalOrCanceling(cur)) jr.setRunStatus(RunStatus.FAILED);
                    }
                    jr.setLastSeenAt(OffsetDateTime.now());
                    repo.updateContainerByRunId(jr);
                    if (isTerminalStatus(jr.getRunStatus())) {
                        trialLimitService.releaseRunRuntimeQuotaIfNeeded(jr);
                    }
                });
                return;
            }

            InspectParser.ContainerState st = InspectParser.parse(r.stdout());
            ContainerStatus cs = StatusMapper.mapToContainerStatus(st.dockerStatus());
            RunStatus rs = StatusMapper.mapToRunStatus(st.dockerStatus(), st.exitCode());

            tx.executeWithoutResult(status -> {
                jr.setDockerStatus(st.dockerStatus());
                jr.setExitCode(st.exitCode());
                jr.setStartedAt(st.startedAt());
                jr.setFinishedAt(st.finishedAt());
                jr.setContainerStatus(cs);
                RunStatus cur = jr.getRunStatus();
                if (!StatusMapper.isTerminalOrCanceling(cur)) {
                    jr.setRunStatus(rs);
                }
                jr.setErrorMessage(null);
                jr.setLastSeenAt(OffsetDateTime.now());
                repo.updateContainerByRunId(jr);
                if (isTerminalStatus(jr.getRunStatus())) {
                    trialLimitService.releaseRunRuntimeQuotaIfNeeded(jr);
                }
            });
        } catch (Exception ex) {
            log.warn("[inspect] refresh failed runId={}, err={}", runId, ex.getMessage());
        }
    }

    /**
     * 判断运行状态是否终态。
     */
    private boolean isTerminalStatus(RunStatus runStatus) {
        return runStatus == RunStatus.SUCCEEDED
                || runStatus == RunStatus.FAILED
                || runStatus == RunStatus.CANCELED;
    }
}
