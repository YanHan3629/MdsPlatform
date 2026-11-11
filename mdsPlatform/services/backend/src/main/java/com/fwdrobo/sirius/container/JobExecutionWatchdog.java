package com.fwdrobo.sirius.container;

import com.fwdrobo.sirius.docker.entity.DockerHostResolver;
import com.fwdrobo.sirius.docker.service.DockerCli;
import com.fwdrobo.sirius.entity.job.*;
import com.fwdrobo.sirius.mapper.JobRunMapper;
import com.fwdrobo.sirius.security.JobLaunchAuthority;
import com.fwdrobo.sirius.service.TrialLimitService;
import jakarta.annotation.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Rechecks human executions independently of log traffic or the job launcher pool. */
@Slf4j
@Service
public class JobExecutionWatchdog {
 private record Target(UUID run,String container,String host,String reason){}
 private final JdbcTemplate jdbc;private final JobRunMapper runs;private final JobLaunchAuthority authority;
 private final DockerCli runtime;private final DockerHostResolver hosts;private final TrialLimitService quotas;
 private final ContainerLogStreamer logs;private final TransactionTemplate tx;
 private final ConcurrentMap<UUID,Target> known=new ConcurrentHashMap<>();
 private final Set<UUID> busy=ConcurrentHashMap.newKeySet(),uncertain=ConcurrentHashMap.newKeySet();
 private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"job-authority-poll");t.setDaemon(true);return t;});
 private final ThreadPoolExecutor workers;
 public JobExecutionWatchdog(JdbcTemplate jdbc,JobRunMapper runs,JobLaunchAuthority authority,DockerCli runtime,
   DockerHostResolver hosts,TrialLimitService quotas,ContainerLogStreamer logs,PlatformTransactionManager manager){
  this.jdbc=jdbc;this.runs=runs;this.authority=authority;this.runtime=runtime;this.hosts=hosts;this.quotas=quotas;this.logs=logs;
  tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  AtomicInteger number=new AtomicInteger();
  workers=new ThreadPoolExecutor(4,8,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),r->{var t=new Thread(r,"job-authority-check-"+number.incrementAndGet());t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
 }
 @PostConstruct public void start(){scheduler.scheduleWithFixedDelay(this::poll,500,500,TimeUnit.MILLISECONDS);}
 public void track(JobRun run,String host){
  if(run.getContainerId()!=null&&!run.getContainerId().isBlank()&&!terminal(run.getRunStatus()))
   known.put(run.getRunId(),new Target(run.getRunId(),run.getContainerId(),host,null));
 }
 private void poll(){
  Set<UUID> ids=new HashSet<>(known.keySet());
  try{ids.addAll(jdbc.queryForList("""
   SELECT r.run_id FROM job_run r JOIN ds_job_execution_definition d ON d.run_id=r.run_id
   WHERE r.run_status IN('QUEUED','RUNNING','CANCELING') ORDER BY r.created_at,r.run_id
   """,UUID.class));}
  catch(RuntimeException unavailable){
   // Only exact runtime identities already observed by this server are used
   // when the authority database is unavailable.
   uncertain.addAll(known.keySet());log.warn("Human task authority scan unavailable");
  }
  for(UUID run:ids)if(busy.add(run)){
   try{workers.execute(()->{try{check(run);}finally{busy.remove(run);}});}
   catch(RejectedExecutionException full){busy.remove(run);log.warn("Human task authority check queue full");}
  }
 }
 private void check(UUID run){
  try{
   Target target=tx.execute(status->{
    // Polling can only cancel; protected I/O independently rechecks full authority.
    // Avoid holding the identity policy row while waiting for the resource fence.
    JobRun current=authority.lockRun(run,false);
    if(current==null||terminal(current.getRunStatus())){known.remove(run);uncertain.remove(run);return null;}
    boolean unavailable=uncertain.contains(run);
    boolean stop=current.getRunStatus()==RunStatus.CANCELING||unavailable||!authority.currentRecorded(current);
    String host=hosts.resolve(current.getDockerHost()==null?"GPU_01":current.getDockerHost());
    track(current,host);
    if(!stop)return null;
    String reason=unavailable?"TASK_EXECUTION_AUTHORITY_UNAVAILABLE":
     current.getRunStatus()==RunStatus.CANCELING?
      (current.getErrorMessage()==null?"TASK_CANCELLATION_REQUESTED":current.getErrorMessage()):
      "TASK_EXECUTION_AUTHORITY_REVOKED_OR_CHANGED";
    if(current.getRunStatus()==RunStatus.QUEUED&&current.getContainerId()==null){
     current.setRunStatus(RunStatus.FAILED);current.setContainerStatus(ContainerStatus.FAILED);
     current.setFinishedAt(OffsetDateTime.now());
    }else current.setRunStatus(RunStatus.CANCELING);
    current.setErrorMessage(reason);
    current.setLastSeenAt(OffsetDateTime.now());runs.updateStatusAndTimes(current);
    if(terminal(current.getRunStatus())){quotas.releaseRunRuntimeQuotaIfNeeded(current);known.remove(run);uncertain.remove(run);return null;}
    return current.getContainerId()==null?null:new Target(run,current.getContainerId(),host,reason);
   });
   if(target!=null)stopAndSettle(target);
  }catch(RuntimeException unavailable){
   Target target=known.get(run);
   if(target!=null){uncertain.add(run);stopRuntime(target);}
   log.warn("Human task authority or settlement unavailable runId={}",run);
  }
 }
 private boolean stopRuntime(Target target){
  try{return runtime.exec(List.of("stop","-t","1",target.container()),target.host()).exitCode()==0;}
  catch(Exception unconfirmed){return false;}
 }
 private void stopAndSettle(Target target){
  boolean stopped=stopRuntime(target);
  tx.executeWithoutResult(status->{
   JobRun current=authority.lockRun(target.run(),false);if(current==null)return;
   if(!target.container().equals(current.getContainerId()))return;
   current.setContainerStatus(stopped?ContainerStatus.STOPPED:ContainerStatus.STOP_FAILED);
   if(!terminal(current.getRunStatus())){
    current.setRunStatus(stopped?RunStatus.CANCELED:RunStatus.CANCELING);
    if(stopped)current.setFinishedAt(OffsetDateTime.now());
   }
   // STOP_FAILED records the unconfirmed external stop; retain its original cause.
   if(current.getRunStatus()==RunStatus.CANCELING||current.getRunStatus()==RunStatus.CANCELED)
    current.setErrorMessage(target.reason());
   current.setLastSeenAt(OffsetDateTime.now());runs.updateStatusAndTimes(current);
   if(terminal(current.getRunStatus())){quotas.releaseRunRuntimeQuotaIfNeeded(current);known.remove(target.run());uncertain.remove(target.run());}
  });
  if(stopped)try{logs.stopStreaming(target.run());}catch(RuntimeException unavailable){log.warn("Stopped human task log flush unavailable runId={}",target.run());}
 }
 private static boolean terminal(RunStatus state){return state==RunStatus.SUCCEEDED||state==RunStatus.FAILED||state==RunStatus.CANCELED;}
 @PreDestroy public void shutdown(){scheduler.shutdownNow();workers.shutdownNow();}
}
