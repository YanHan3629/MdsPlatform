package com.fwdrobo.sirius.container;

import com.fwdrobo.sirius.docker.service.DockerCli;
import com.fwdrobo.sirius.entity.job.ContainerStatus;
import com.fwdrobo.sirius.entity.job.JobRun;
import com.fwdrobo.sirius.entity.job.RunStatus;
import com.fwdrobo.sirius.mapper.JobRunMapper;
import com.fwdrobo.sirius.service.TrialLimitService;
import com.fwdrobo.sirius.security.JobLaunchAuthority;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class ContainerLauncher {
 @org.springframework.beans.factory.annotation.Autowired private JobExecutionWatchdog watchdog;
 private final JobRunMapper repo;private final DockerCli dockerCli;
 private final TransactionTemplate tx;private final ContainerInspector inspector;
 private final ContainerLogStreamer logs;private final TrialLimitService quotas;private final JobLaunchAuthority authority;
 public ContainerLauncher(JobRunMapper repo,DockerCli dockerCli,PlatformTransactionManager manager,
   ContainerInspector inspector,ContainerLogStreamer logs,TrialLimitService quotas,JobLaunchAuthority authority){
  this.repo=repo;this.dockerCli=dockerCli;this.inspector=inspector;this.logs=logs;this.quotas=quotas;this.authority=authority;
  tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
 }
 /** Invoked only after the submitting transaction commits. */
 @Async("jobLauncherExecutor")
 public void launch(UUID runId,List<String> volumes,List<String> arguments,String host,String workerToken){
  String created=null;
  try{
   boolean start=Boolean.TRUE.equals(tx.execute(status->{
    JobRun current=authority.lockRun(runId,true);
    if(current==null||current.getRunStatus()!=RunStatus.QUEUED)return false;
    if(!authority.current(current,workerToken)){failLocked(current,"JOB_LAUNCH_AUTHORITY_REVOKED_OR_CHANGED");return false;}
    current.setRunStatus(RunStatus.RUNNING);current.setLastSeenAt(OffsetDateTime.now());repo.updateStatusAndTimes(current);return true;
   }));
   if(!start)return;
   // Neither authority locks nor a database transaction span resource allocation.
   var result=dockerCli.exec(arguments,host);
   if(result.exitCode()!=0){failRun(runId,"TASK_CREATE_FAILED");return;}
   created=result.stdout().trim();
   if(created.isBlank()||created.chars().anyMatch(Character::isWhitespace))throw new IllegalStateException("INVALID_CREATED_RUNTIME_ID");
   final String container=created;
   JobRun attached=tx.execute(status->{
    JobRun current=authority.lockRun(runId,true);
    if(current==null)return null;
    current.setContainerId(container);current.setContainerStatus(ContainerStatus.CREATED);
    if(!StatusMapper.isTerminalOrCanceling(current.getRunStatus())&&!authority.current(current,workerToken)){
     current.setRunStatus(RunStatus.CANCELING);current.setErrorMessage("JOB_LAUNCH_AUTHORITY_REVOKED_OR_CHANGED");
    }
    current.setLastSeenAt(OffsetDateTime.now());repo.updateContainerByRunId(current);return current;
   });
   if(attached==null||StatusMapper.isTerminalOrCanceling(attached.getRunStatus())){
    stopCreatedRuntime(runId,container,host);return;
   }
   watchdog.track(attached,host);
   try{logs.startStreaming(runId,attached.getJobId(),container,host);}
   catch(Exception unavailable){log.warn("Task log streaming unavailable runId={}",runId);}
   inspector.refreshByRunId(runId,container,host);
  }catch(Exception unavailable){
   // An allocated runtime is always addressed by the exact ID returned above.
   if(created!=null&&!created.isBlank())stopCreatedRuntime(runId,created,host);
   else failRun(runId,"TASK_LAUNCH_FAILED");
  }
 }
 public void rejectBeforeDispatch(UUID runId){failRun(runId,"TASK_DISPATCH_REJECTED");}
 private void stopCreatedRuntime(UUID runId,String container,String host){
  boolean stopped=false;
  try{stopped=dockerCli.exec(List.of("stop","-t","10",container),host).exitCode()==0;}
  catch(Exception unavailable){log.warn("Late task stop unconfirmed runId={}",runId);}
  final boolean confirmed=stopped;
  tx.executeWithoutResult(status->{
   JobRun current=authority.lockRun(runId,false);if(current==null)return;
   current.setContainerId(container);
   current.setContainerStatus(confirmed?ContainerStatus.STOPPED:ContainerStatus.STOP_FAILED);
   if(!terminal(current.getRunStatus())){
    current.setRunStatus(confirmed?RunStatus.CANCELED:RunStatus.CANCELING);
    if(confirmed)current.setFinishedAt(OffsetDateTime.now());
   }
   current.setLastSeenAt(OffsetDateTime.now());repo.updateContainerByRunId(current);
   if(terminal(current.getRunStatus()))quotas.releaseRunRuntimeQuotaIfNeeded(current);
  });
 }
 private static boolean terminal(RunStatus status){return status==RunStatus.SUCCEEDED||status==RunStatus.FAILED||status==RunStatus.CANCELED;}
 private void failLocked(JobRun current,String code){
  if(terminal(current.getRunStatus()))return;
  // A canceled in-progress allocation still needs its eventual runtime ID.
  if(current.getRunStatus()==RunStatus.CANCELING)return;
  current.setRunStatus(RunStatus.FAILED);current.setContainerStatus(ContainerStatus.FAILED);
  current.setErrorMessage(code);current.setFinishedAt(OffsetDateTime.now());current.setLastSeenAt(OffsetDateTime.now());
  repo.updateStatusAndTimes(current);quotas.releaseRunRuntimeQuotaIfNeeded(current);
 }
 private void failRun(UUID runId,String code){
  tx.executeWithoutResult(status->{JobRun current=authority.lockRun(runId,false);if(current!=null)failLocked(current,code);});
 }
}
