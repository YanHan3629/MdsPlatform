package com.fwdrobo.sirius.security;

import com.fwdrobo.sirius.entity.job.JobRun;
import com.fwdrobo.sirius.mapper.JobRunMapper;
import io.jsonwebtoken.JwtException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Recheck a human execution at allocation boundaries without borrowing a service identity. */
@Service
public class JobLaunchAuthority {
 private final JdbcTemplate jdbc;private final JobRunMapper runs;private final JwtService jwt;
 private final IdentitySessionService sessions;private final AuthorizationService auth;
 private final IndexJobAuthority index;private final JobResourceAuthority jobs;
 @org.springframework.beans.factory.annotation.Autowired
 private JobExecutionDefinitionService definitions;
 @org.springframework.beans.factory.annotation.Autowired private ControlledViewPreviewAuthority previews;
 public JobLaunchAuthority(JdbcTemplate jdbc,JobRunMapper runs,JwtService jwt,IdentitySessionService sessions,
   AuthorizationService auth,IndexJobAuthority index,JobResourceAuthority jobs){
  this.jdbc=jdbc;this.runs=runs;this.jwt=jwt;this.sessions=sessions;this.auth=auth;this.index=index;this.jobs=jobs;
 }
 /** Must run inside a short transaction. Identity mutations use the same lock order. */
 public JobRun lockRun(UUID run,boolean identity){
  if(identity)auth.lockIdentityMutation();
  jdbc.execute("SELECT pg_advisory_xact_lock(805014630400001::bigint)");
  if(jdbc.queryForList("SELECT run_id FROM job_run WHERE run_id=? FOR UPDATE",run).isEmpty())return null;
  return runs.selectByRunId(run).orElse(null);
 }
 public boolean current(JobRun run,String bearer){
  try{return currentClaims(run,jwt.parseClaims(bearer,JwtService.TokenType.JOB_IO));}
  catch(JwtException|IllegalArgumentException|ResponseStatusException denied){return false;}
 }
 /** Uses the same frozen identity without storing or issuing a reusable token. */
 public boolean currentRecorded(JobRun run){
  try{return currentClaims(run,definitions.recordedClaims(run.getRunId()));}
  catch(JwtException|IllegalArgumentException denied){return false;}
  catch(ResponseStatusException denied){if(denied.getStatusCode().value()>=500)throw denied;return false;}
 }
 private boolean currentClaims(JobRun run,io.jsonwebtoken.Claims claims){
  if(!definitions.current(claims))return false;
  var actor=sessions.authenticateAccess(claims);Map<?,?> scope=claims.get("job_io_scope",Map.class);
  if(scope==null||!run.getRunId().equals(id(scope,"runId"))||!run.getJobId().equals(id(scope,"jobId"))||
    !run.getCreatedBy().equals(actor.userId())||!run.getOrgId().equals(actor.orgId())||
    !actor.userId().equals(id(scope,"userId"))||!actor.orgId().equals(id(scope,"orgId")))return false;
  if("INDEX_BUILD".equals(scope.get("jobKind")))return index.revalidateRecordedOrigin(claims).run().equals(run.getRunId());
  if(ControlledViewPreviewAuthority.KIND.equals(scope.get("jobKind")))return previews.revalidateRecordedOrigin(claims).job().equals(run.getJobId());
  return !scope.containsKey("jobKind");
 }
 private static UUID id(Map<?,?> scope,String key){return UUID.fromString((String)scope.get(key));}
}
