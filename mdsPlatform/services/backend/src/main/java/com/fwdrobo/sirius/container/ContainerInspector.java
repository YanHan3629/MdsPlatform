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
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

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
                    JobRun live=lockedRun(runId);if(live==null)return;
                    if(preserveConfirmedTerminalStop(live))return;
                    if (err.contains("No such object")) {
                        live.setContainerStatus(ContainerStatus.MISSING);
                        live.setDockerStatus(null);
                        // canceling -> canceled；非终态 -> failed
                        RunStatus cur = live.getRunStatus();
                        if (cur == RunStatus.CANCELING) live.setRunStatus(RunStatus.CANCELED);
                        else if (!StatusMapper.isTerminalOrCanceling(cur)) live.setRunStatus(RunStatus.FAILED);
                        if(live.getFinishedAt()==null)live.setFinishedAt(OffsetDateTime.now());
                    } else {
                        live.setContainerStatus(ContainerStatus.UNKNOWN);
                        live.setErrorMessage("TASK_INSPECTION_FAILED");
                        RunStatus cur = live.getRunStatus();
                        if (!StatusMapper.isTerminalOrCanceling(cur)) live.setRunStatus(RunStatus.FAILED);
                    }
                    live.setLastSeenAt(OffsetDateTime.now());
                    repo.updateContainerByRunId(live);
                    if (isTerminalStatus(live.getRunStatus())) {
                        trialLimitService.releaseRunRuntimeQuotaIfNeeded(live);
                    }
                });
                return;
            }

            InspectParser.ContainerState st = InspectParser.parse(r.stdout());
            ContainerStatus cs = StatusMapper.mapToContainerStatus(st.dockerStatus());
            RunStatus rs = StatusMapper.mapToRunStatus(st.dockerStatus(), st.exitCode());

            tx.executeWithoutResult(status -> {
                JobRun live=lockedRun(runId);if(live==null)return;
                    if(preserveConfirmedTerminalStop(live))return;
                // The observation began before this transaction. A cancellation
                // or terminal result committed meanwhile takes priority.
                if(StatusMapper.isTerminalOrCanceling(live.getRunStatus())&&rs==RunStatus.RUNNING){
                    live.setLastSeenAt(OffsetDateTime.now());repo.updateStatusAndTimes(live);return;
                }
                live.setDockerStatus(st.dockerStatus());
                live.setExitCode(st.exitCode());
                if(live.getStartedAt()==null)live.setStartedAt(st.startedAt());
                if(live.getFinishedAt()==null)live.setFinishedAt(st.finishedAt());
                live.setContainerStatus(cs);
                RunStatus cur = live.getRunStatus();
                if(cur==RunStatus.CANCELING&&("exited".equals(st.dockerStatus())||"dead".equals(st.dockerStatus())))
                    live.setRunStatus(RunStatus.CANCELED);
                else if(!StatusMapper.isTerminalOrCanceling(cur))live.setRunStatus(rs);
                if(!StatusMapper.isTerminalOrCanceling(cur))live.setErrorMessage(null);
                live.setLastSeenAt(OffsetDateTime.now());
                repo.updateContainerByRunId(live);
                if (isTerminalStatus(live.getRunStatus())) {
                    trialLimitService.releaseRunRuntimeQuotaIfNeeded(live);
                }
            });
        } catch (Exception ex) {
            log.warn("[inspect] refresh failed runId={}, err={}", runId, ex.getMessage());
        }
    }

    /**
     * 判断运行状态是否终态。
     */
    private JobRun lockedRun(UUID run){
        jdbc.execute("SELECT pg_advisory_xact_lock(805014630400001::bigint)");
        if(jdbc.queryForList("SELECT run_id FROM job_run WHERE run_id=? FOR UPDATE",run).isEmpty())return null;
        return repo.selectByRunId(run).orElse(null);
    }

    /** A later exited/missing/error observation cannot undo a confirmed stop. */
    private boolean preserveConfirmedTerminalStop(JobRun live){
        if(!isTerminalStatus(live.getRunStatus())||
            !(live.getContainerStatus()==ContainerStatus.STOPPED||live.getContainerStatus()==ContainerStatus.REMOVED))return false;
        live.setLastSeenAt(OffsetDateTime.now());
        repo.updateStatusAndTimes(live);
        trialLimitService.releaseRunRuntimeQuotaIfNeeded(live);
        return true;
    }

    private boolean isTerminalStatus(RunStatus runStatus) {
        return runStatus == RunStatus.SUCCEEDED
                || runStatus == RunStatus.FAILED
                || runStatus == RunStatus.CANCELED;
    }
}
