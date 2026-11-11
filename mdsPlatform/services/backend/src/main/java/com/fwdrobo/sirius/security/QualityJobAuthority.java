package com.fwdrobo.sirius.security;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.regex.Pattern;

/** A quality worker consumes only work delegated by one live original human authority. */
@Service
public class QualityJobAuthority {
 public static final String ATTRIBUTE="ds.qualityWorkerScope",OP="dataset.quality.run",PURPOSE="GENERAL_READ";
 private final JdbcTemplate db;private final AuthorizationService auth;private final ObjectProvider<GenericFileOriginService> origins;
 public QualityJobAuthority(JdbcTemplate db,AuthorizationService auth,ObjectProvider<GenericFileOriginService> origins){this.db=db;this.auth=auth;this.origins=origins;}
 public static final class Scope {
  private final AuthorizationService.Actor actor;private final Claims claims;
  private UUID report,lease;private boolean finished;
  Scope(AuthorizationService.Actor actor,Claims claims){this.actor=actor;this.claims=claims;}
  public AuthorizationService.Actor actor(){return actor;}
 }
 public record Claim(UUID report,UUID lease){}
 private static void fail(HttpStatus status,String code){throw new ResponseStatusException(status,code);}
 private static UUID id(Object value){try{return UUID.fromString(Objects.toString(value,""));}catch(Exception e){fail(HttpStatus.UNAUTHORIZED,"QUALITY_SCOPE_ID_REQUIRED");return null;}}
 private static long number(Object value){if(!(value instanceof Number))fail(HttpStatus.UNAUTHORIZED,"QUALITY_SCOPE_VERSION_REQUIRED");return ((Number)value).longValue();}
 private HttpServletRequest request(){if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a)return a.getRequest();fail(HttpStatus.UNAUTHORIZED,"QUALITY_WORKER_REQUEST_REQUIRED");return null;}
 public boolean active(){return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a&&a.getRequest().getAttribute(ATTRIBUTE) instanceof Scope;}
 private boolean exists(String sql,Object... args){return Boolean.TRUE.equals(db.queryForObject(sql,Boolean.class,args));}
 private AuthorizationService.GrantBinding binding(Map<String,Object> row,String prefix){return new AuthorizationService.GrantBinding(id(row.get(prefix+"_grant_id")),number(row.get(prefix+"_grant_version")));}
 private Map<String,Object> row(UUID report){
  var rows=db.queryForList("SELECT a.*,j.status AS job_status,j.lease_token,j.lease_until>clock_timestamp() AS lease_current FROM ds_quality_authority a JOIN ds_quality_job j ON j.report_id=a.report_id WHERE a.report_id=?",report);
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"QUALITY_ORIGINAL_AUTHORITY_NOT_AVAILABLE");return rows.get(0);
 }
 private void sourceCurrent(Map<String,Object> row){
  var human=auth.actorFor(id(row.get("actor_user_id")));
  if(!human.orgId().equals(row.get("actor_org_id"))||human.tokenVersion()!=number(row.get("actor_token_version"))||
    !exists("SELECT EXISTS(SELECT 1 FROM ds_auth_session WHERE session_id=? AND user_id=? AND org_id=? AND token_version=? AND purpose='ACCESS' AND revoked_at IS NULL AND expires_at>clock_timestamp())",row.get("parent_session_id"),human.userId(),human.orgId(),human.tokenVersion()))
   fail(HttpStatus.FORBIDDEN,"QUALITY_REQUESTER_SESSION_REVOKED");
  UUID d=id(row.get("dataset_id")),v=id(row.get("version_id"));
  if(!auth.persistedSourceGrantCurrent(human.userId(),human.orgId(),d,v,null,Set.of(OP),PURPOSE,binding(row,"source"))||
     !origins.getObject().datasetInputsCurrent(d,v,null,Set.of(OP),PURPOSE,human))fail(HttpStatus.FORBIDDEN,"QUALITY_ORIGINAL_SOURCE_AUTHORITY_REVOKED");
 }
 private void authorityCurrent(Map<String,Object> row,AuthorizationService.Actor worker){
  if(!worker.serviceId().equals(row.get("service_subject_id"))||!worker.orgId().equals(row.get("actor_org_id"))||worker.permissionVersion()!=number(row.get("service_version")))
   fail(HttpStatus.FORBIDDEN,"QUALITY_ASSIGNED_SERVICE_CHANGED");
  UUID d=id(row.get("dataset_id")),v=id(row.get("version_id"));
  if(!auth.persistedServiceGrantCurrent(worker.serviceId(),worker.orgId(),d,v,null,Set.of(OP),PURPOSE,binding(row,"service")))
   fail(HttpStatus.FORBIDDEN,"QUALITY_ORIGINAL_SERVICE_GRANT_REVOKED");
  if(!exists("""
   SELECT EXISTS(SELECT 1 FROM mm_dataset d JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
    JOIN artifact_commit c ON c.repo_id=d.raw_repo_id AND c.commit_id=v.raw_commit_id AND c.commit_status='PUBLISHED'
    WHERE d.dataset_id=? AND d.org_id=? AND d.status='ACTIVE' AND v.version_id=? AND d.raw_repo_id=? AND v.raw_commit_id=?)
   """,d,worker.orgId(),v,row.get("raw_repo_id"),row.get("raw_commit_id")))fail(HttpStatus.FORBIDDEN,"QUALITY_FROZEN_SNAPSHOT_CHANGED");
  sourceCurrent(row);
 }
 /** Called in the same transaction as the original user request, never on retry. */
 public void capture(UUID report,UUID dataset,UUID version,Object requestedService){
  auth.lockIdentityMutation();
  var human=auth.requireOperation(OP);if(human.userId()==null||human.serviceId()!=null)fail(HttpStatus.FORBIDDEN,"QUALITY_INTERACTIVE_REQUEST_REQUIRED");
  Object value=request().getAttribute("ds.accessClaims");if(!(value instanceof Claims))fail(HttpStatus.UNAUTHORIZED,"QUALITY_ORIGINAL_SESSION_REQUIRED");Claims claims=(Claims)value;
  var lease=auth.lease(human,dataset,version,null,Set.of(OP),PURPOSE,null);
  if(lease.authority().grant()==null)fail(HttpStatus.FORBIDDEN,"QUALITY_ORIGINAL_RESOURCE_GRANT_REQUIRED");
  var snapshots=db.queryForList("""
   SELECT d.raw_repo_id,v.raw_commit_id FROM mm_dataset d JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
    JOIN artifact_commit c ON c.repo_id=d.raw_repo_id AND c.commit_id=v.raw_commit_id AND c.commit_status='PUBLISHED'
   WHERE d.dataset_id=? AND d.org_id=? AND d.status='ACTIVE' AND v.version_id=?
   """,dataset,human.orgId(),version);
  if(snapshots.size()!=1)fail(HttpStatus.NOT_FOUND,"QUALITY_OWN_PUBLISHED_SNAPSHOT_REQUIRED");
  UUID selected=requestedService==null?null:id(requestedService);
  var services=db.queryForList("""
   SELECT s.service_subject_id,s.version AS service_version,g.grant_id,g.version AS grant_version
   FROM ds_service_subject s JOIN ds_resource_grant g ON g.subject_kind='SERVICE' AND g.subject_id=s.service_subject_id
   WHERE s.org_id=? AND s.purpose='QUALITY_CHECK' AND s.active AND s.closed_at IS NULL AND g.dataset_id=? AND g.state='ACTIVE'
    AND (?::uuid IS NULL OR s.service_subject_id=?) ORDER BY s.service_subject_id,g.grant_id
   """,human.orgId(),dataset,selected,selected);
  Map<String,Object> service=null;
  for(var candidate:services){
   if(auth.persistedServiceGrantCurrent(id(candidate.get("service_subject_id")),human.orgId(),dataset,version,null,Set.of(OP),PURPOSE,
      new AuthorizationService.GrantBinding(id(candidate.get("grant_id")),number(candidate.get("grant_version"))))){service=candidate;break;}
  }
  if(service==null)fail(HttpStatus.SERVICE_UNAVAILABLE,"QUALITY_SERVICE_BINDING_REQUIRED");
  var frozen=snapshots.get(0);var grant=lease.authority().grant();
  db.update("""
   INSERT INTO ds_quality_authority(report_id,actor_user_id,actor_org_id,actor_token_version,parent_session_id,
    source_grant_id,source_grant_version,service_subject_id,service_version,service_grant_id,service_grant_version,
    dataset_id,version_id,raw_repo_id,raw_commit_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
   """,report,human.userId(),human.orgId(),human.tokenVersion(),id(claims.get("sid")),grant.grantId(),grant.version(),
   service.get("service_subject_id"),service.get("service_version"),service.get("grant_id"),service.get("grant_version"),dataset,version,frozen.get("raw_repo_id"),frozen.get("raw_commit_id"));
 }
 private AuthorizationService.Actor credential(Claims claims){
  if(!"SERVICE_IO".equals(claims.get("token_type"))||!"QUALITY_WORKER".equals(claims.get("service_kind"))||!"QUALITY_CHECK".equals(claims.get("purpose"))||!OP.equals(claims.get("operation"))||claims.containsKey("uid")||claims.containsKey("sid")||claims.getId()==null||claims.getIssuedAt()==null||claims.getExpiration()==null||claims.getExpiration().getTime()-claims.getIssuedAt().getTime()>120000||claims.getIssuedAt().getTime()>System.currentTimeMillis()+5000||!claims.getExpiration().after(new Date()))
   fail(HttpStatus.UNAUTHORIZED,"QUALITY_WORKER_CREDENTIAL_REQUIRED");
  var actor=auth.serviceActorFor(id(claims.get("service_subject_id")));
  if(!actor.orgId().equals(id(claims.get("org_id")))||actor.permissionVersion()!=number(claims.get("service_version"))||!actor.operations().equals(Set.of(OP)))fail(HttpStatus.UNAUTHORIZED,"QUALITY_WORKER_IDENTITY_CHANGED");
  auth.requireOperation(actor,OP);return actor;
 }
 private void validate(Scope scope,boolean output){
  var live=credential(scope.claims);
  if(!live.serviceId().equals(scope.actor.serviceId()))fail(HttpStatus.UNAUTHORIZED,"QUALITY_WORKER_CHANGED");
  if(scope.report!=null){
   var row=row(scope.report);authorityCurrent(row,live);
   if(!scope.lease.equals(row.get("lease_token")))fail(HttpStatus.CONFLICT,"QUALITY_LEASE_CHANGED");
   boolean running="RUNNING".equals(row.get("job_status"))&&Boolean.TRUE.equals(row.get("lease_current"));
   if(!running&&!(output&&scope.finished&&Set.of("COMPLETED","FAILED","QUEUED").contains(row.get("job_status"))))fail(HttpStatus.CONFLICT,"QUALITY_LEASE_EXPIRED");
  }
 }
 public Scope authenticate(HttpServletRequest request,Claims claims){
  var actor=credential(claims);
  if(request.getAttribute(ATTRIBUTE) instanceof Scope prior){
   if(!Objects.equals(prior.claims.getId(),claims.getId()))fail(HttpStatus.UNAUTHORIZED,"QUALITY_CREDENTIAL_CHANGED");validate(prior,true);return prior;
  }
  var scope=new Scope(actor,claims);String path=request.getRequestURI();
  if(path.equals("/api/mm/service/quality/claim")){
   if(!"POST".equals(request.getMethod())||!request.getParameterMap().isEmpty())fail(HttpStatus.FORBIDDEN,"QUALITY_WORKER_ROUTE_DENIED");
  }else{
   var matcher=Pattern.compile("^/api/mm/service/quality/([a-fA-F0-9-]{36})/(heartbeat|content|complete|fail)$").matcher(path);
   if(!matcher.matches())fail(HttpStatus.FORBIDDEN,"QUALITY_WORKER_ROUTE_DENIED");String action=matcher.group(2);
   if(!("content".equals(action)?"GET":"POST").equals(request.getMethod()))fail(HttpStatus.FORBIDDEN,"QUALITY_WORKER_METHOD_DENIED");
   Set<String> allowed="content".equals(action)?Set.of("leaseToken","path"):Set.of("leaseToken");
   if(!request.getParameterMap().keySet().equals(allowed)||request.getParameterMap().values().stream().anyMatch(v->v.length!=1))fail(HttpStatus.FORBIDDEN,"QUALITY_WORKER_PARAMETERS_DENIED");
   scope.report=id(matcher.group(1));scope.lease=id(request.getParameter("leaseToken"));validate(scope,false);
  }
  request.setAttribute(ATTRIBUTE,scope);return scope;
 }
 public Scope current(){Object value=request().getAttribute(ATTRIBUTE);if(!(value instanceof Scope))fail(HttpStatus.UNAUTHORIZED,"QUALITY_WORKER_SCOPE_REQUIRED");var scope=(Scope)value;validate(scope,false);return scope;}
 public void requireJob(UUID report,UUID lease){var scope=current();if(!report.equals(scope.report)||!lease.equals(scope.lease))fail(HttpStatus.FORBIDDEN,"QUALITY_JOB_OUTSIDE_SCOPE");}
 public void markFinished(UUID report,UUID lease){
  Object value=request().getAttribute(ATTRIBUTE);if(!(value instanceof Scope s)||!report.equals(s.report)||!lease.equals(s.lease))fail(HttpStatus.FORBIDDEN,"QUALITY_JOB_OUTSIDE_SCOPE");
  ((Scope)value).finished=true;
 }
 @Transactional public Claim claim(){
  auth.lockIdentityMutation();var scope=current();if(scope.report!=null)fail(HttpStatus.FORBIDDEN,"QUALITY_CLAIM_ROUTE_REQUIRED");
  var candidates=db.queryForList("""
   SELECT j.report_id,j.attempt_count FROM ds_quality_job j JOIN ds_quality_authority a ON a.report_id=j.report_id
   WHERE a.service_subject_id=? AND ((j.status='QUEUED' AND j.next_attempt_at<=clock_timestamp()) OR
    (j.status='RUNNING' AND j.lease_until<clock_timestamp())) ORDER BY j.updated_at FOR UPDATE OF j SKIP LOCKED LIMIT 16
   """,scope.actor.serviceId());
  for(var candidate:candidates){
   UUID report=id(candidate.get("report_id"));String error=null;
   try{authorityCurrent(row(report),scope.actor);}catch(ResponseStatusException denied){
    if(!Set.of(401,403,404).contains(denied.getStatusCode().value()))throw denied;error="QUALITY_ORIGINAL_AUTHORITY_REVOKED";
   }
   if(error==null&&number(candidate.get("attempt_count"))>=3)error="QUALITY_ATTEMPT_LIMIT_REACHED";
   if(error!=null){
    db.update("UPDATE ds_quality_report SET status='FAILED',blocking_count=1,completed_at=clock_timestamp(),coverage=jsonb_build_object('authorization',?::text) WHERE report_id=? AND completed_at IS NULL",error,report);
    db.update("UPDATE ds_quality_job SET status='FAILED',last_error=?,lease_until=null,updated_at=clock_timestamp() WHERE report_id=?",error,report);
    audit(report,scope.actor,error);continue;
   }
   UUID token=UUID.randomUUID();db.update("UPDATE ds_quality_job SET status='RUNNING',attempt_count=attempt_count+1,lease_token=?,lease_until=clock_timestamp()+interval '120 seconds',updated_at=clock_timestamp() WHERE report_id=?",token,report);
   scope.report=report;scope.lease=token;validate(scope,false);audit(report,scope.actor,"QUALITY_ORIGINAL_AUTHORITIES_CLAIMED");return new Claim(report,token);
  }
  return null;
 }
 private void audit(UUID report,AuthorizationService.Actor actor,String reason){
  db.update("INSERT INTO ds_authorization_audit(actor_service_id,actor_org_id,operation_code,target_kind,target_id,outcome,request_id,scope,reason_code) VALUES(?,?,'dataset.quality.run','QUALITY_REPORT',?,?,?,jsonb_build_object('reportId',?::text),?)",
   actor.serviceId(),actor.orgId(),report.toString(),reason.endsWith("CLAIMED")?"ALLOW":"DENY",UUID.randomUUID().toString(),report.toString(),reason);
 }
 public void requireArtifact(UUID repo,UUID commit,boolean write){
  var scope=current();if(scope.report==null||write)fail(HttpStatus.FORBIDDEN,"QUALITY_RAW_SNAPSHOT_ONLY");var r=row(scope.report);
  if(!repo.equals(r.get("raw_repo_id"))||!Objects.equals(commit,r.get("raw_commit_id")))fail(HttpStatus.FORBIDDEN,"QUALITY_RAW_SNAPSHOT_ONLY");
 }
 public void requireFile(UUID file){
  var scope=current();if(scope.report==null)fail(HttpStatus.FORBIDDEN,"QUALITY_JOB_REQUIRED");var r=row(scope.report);
  if(!exists("SELECT EXISTS(SELECT 1 FROM artifact_file f JOIN artifact_commit c ON c.commit_id=f.commit_id WHERE f.file_id=? AND c.repo_id=? AND c.commit_id=?)",file,r.get("raw_repo_id"),r.get("raw_commit_id")))fail(HttpStatus.NOT_FOUND,"QUALITY_FILE_OUTSIDE_SNAPSHOT");
 }
 @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${ds.quality.authority-reconcile-ms:5000}")
 @Transactional public void reconcile(){
  if(RequestContextHolder.getRequestAttributes()!=null)return;
  auth.lockIdentityMutation();
  var rows=db.queryForList("SELECT j.report_id FROM ds_quality_job j JOIN ds_quality_authority a ON a.report_id=j.report_id WHERE j.status IN('QUEUED','RUNNING') ORDER BY j.updated_at FOR UPDATE OF j SKIP LOCKED LIMIT 16");
  for(var value:rows){UUID report=id(value.get("report_id"));
   try{var a=row(report);authorityCurrent(a,auth.serviceActorFor(id(a.get("service_subject_id"))));}
   catch(ResponseStatusException denied){
    if(!Set.of(401,403,404).contains(denied.getStatusCode().value()))throw denied;
    db.update("UPDATE ds_quality_report SET status='FAILED',blocking_count=1,completed_at=clock_timestamp(),coverage=jsonb_build_object('authorization','QUALITY_ORIGINAL_AUTHORITY_REVOKED') WHERE report_id=? AND completed_at IS NULL",report);
    db.update("UPDATE ds_quality_job SET status='FAILED',last_error='QUALITY_ORIGINAL_AUTHORITY_REVOKED',lease_until=null,updated_at=clock_timestamp() WHERE report_id=?",report);
   }
  }
 }
}
