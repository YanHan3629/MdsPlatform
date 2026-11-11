package com.fwdrobo.sirius.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.*;

/** Read-only index prewarming: original build authority or the current activator's authority. */
@Service
public class IndexCacheScopeService {
 public static final String ATTRIBUTE="ds.indexCacheScope";
 public static final Duration PREPARED_PRELOAD_TIMEOUT=Duration.ofMinutes(7);
 private static final long DEFAULT_CREDENTIAL_SECONDS=120;
 private static final long PREPARED_CREDENTIAL_SECONDS=PREPARED_PRELOAD_TIMEOUT.plusSeconds(30).toSeconds();
 private final JdbcTemplate jdbc;private final AuthorizationService auth;private final IdentitySessionService sessions;
 private final JwtService jwt;private final IndexJobAuthority jobs;private final ServiceScopeService services;
 public IndexCacheScopeService(JdbcTemplate jdbc,AuthorizationService auth,IdentitySessionService sessions,JwtService jwt,IndexJobAuthority jobs,ServiceScopeService services){
  this.jdbc=jdbc;this.auth=auth;this.sessions=sessions;this.jwt=jwt;this.jobs=jobs;this.services=services;
 }
 public record Scope(AuthorizationService.Actor actor,UUID session,UUID dataset,UUID version,UUID index,
  UUID indexRepo,UUID indexCommit,AuthorizationService.GrantBinding grant,String kind,String operation,UUID originRun){}
 public record Credential(String authorization,String authorityKind){
  @Override public String toString(){return "SCOPED_INDEX_CACHE_CREDENTIAL";}
 }
 public record Ticket(Scope scope,Credential credential){
  @Override public String toString(){return "SCOPED_INDEX_CACHE_TICKET";}
 }
 private static void fail(HttpStatus status,String code){throw new ResponseStatusException(status,code);}
 private static UUID id(Claims c,String key){
  try{return UUID.fromString(c.get(key,String.class));}catch(Exception invalid){fail(HttpStatus.UNAUTHORIZED,"INVALID_INDEX_CACHE_SCOPE");return null;}
 }
 private static long number(Claims c,String key){
  Object value=c.get(key);if(!(value instanceof Number))fail(HttpStatus.UNAUTHORIZED,"INVALID_INDEX_CACHE_SCOPE");return ((Number)value).longValue();
 }
 /** Use the same lock as revocation triggers, only for short metadata transactions. */
 public void fence(){auth.lockIdentityMutation();jdbc.execute("SELECT pg_advisory_xact_lock(805014630400001::bigint)");}
 public void requireLiveRequest(){
  if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs&&attrs.getRequest().getAttribute("ds.liveAuthentication") instanceof Runnable live)live.run();
 }
 /** Restore and revalidate the immutable origin; never substitute an independent service. */
 public IndexJobAuthority.Origin preparedOrigin(UUID dataset,UUID version,UUID index){
  var rows=jdbc.queryForList("""
   SELECT o.* FROM ds_index_execution_origin o
   JOIN ds_index_callback_receipt c ON c.run_id=o.run_id AND c.callback_kind='READY'
   JOIN mm_index_version i ON i.index_version_id=o.index_version_id
   JOIN job_run j ON j.run_id=o.run_id AND j.job_id=o.job_id AND j.created_by=o.actor_user_id AND j.org_id=o.actor_org_id
   WHERE o.dataset_id=? AND o.version_id=? AND o.index_version_id=? AND i.index_status IN('PREWARMING','READY')
     AND j.run_status::text IN('QUEUED','RUNNING','SUCCEEDED')
   """,dataset,version,index);
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"ORIGINAL_INDEX_PUBLICATION_AUTHORITY_REQUIRED");
  var r=rows.get(0);var actor=auth.actorFor((UUID)r.get("actor_user_id"));
  var frozen=new HashMap<String,String>();
  for(var entry:Map.ofEntries(Map.entry("jobId","job_id"),Map.entry("runId","run_id"),Map.entry("userId","actor_user_id"),
   Map.entry("orgId","actor_org_id"),Map.entry("parentSessionId","parent_session_id"),Map.entry("inputRepoId","input_repo_id"),
   Map.entry("inputCommitId","input_commit_id"),Map.entry("outputRepoId","output_repo_id"),Map.entry("outputCommitId","output_commit_id"),
   Map.entry("datasetId","dataset_id"),Map.entry("versionId","version_id"),Map.entry("indexVersionId","index_version_id")).entrySet())
   frozen.put(entry.getKey(),r.get(entry.getValue()).toString());
  frozen.put("jobKind","INDEX_BUILD");
  if(r.get("previous_index_commit_id")!=null)frozen.put("previousIndexCommitId",r.get("previous_index_commit_id").toString());
  String probe=jwt.generateJobIoToken(actor.username(),((Number)r.get("token_version")).intValue(),frozen,120);
  return jobs.revalidateRecordedOrigin(jwt.parseClaims(probe,JwtService.TokenType.JOB_IO));
 }
 private Scope resolve(Claims c){
  if(!"INDEX_CACHE_IO".equals(c.get("token_type",String.class))||!"VIEW_BUILD".equals(c.get("purpose",String.class))||
    c.getExpiration()==null||!c.getExpiration().after(new Date()))fail(HttpStatus.UNAUTHORIZED,"INVALID_OR_EXPIRED_INDEX_CACHE_CREDENTIAL");
  var actor=sessions.authenticateAccess(c);UUID session=id(c,"sid"),d=id(c,"dataset_id"),v=id(c,"version_id"),i=id(c,"index_version_id");
  if(!actor.orgId().equals(id(c,"org_id")))fail(HttpStatus.UNAUTHORIZED,"INDEX_CACHE_IDENTITY_CHANGED");
  var binding=new AuthorizationService.GrantBinding(id(c,"grant_id"),number(c,"grant_version"));
  String kind=c.get("cache_kind",String.class),operation=c.get("operation",String.class);UUID origin=null;
  if("ORIGINAL_BUILD".equals(kind)&&"dataset.index.build".equals(operation)){
   var original=preparedOrigin(d,v,i);origin=id(c,"origin_run_id");
   if(!origin.equals(original.run())||!actor.userId().equals(original.user())||!actor.orgId().equals(original.org())||
     !session.equals(original.session())||!binding.equals(original.grant())||actor.tokenVersion()!=original.tokenVersion())
    fail(HttpStatus.FORBIDDEN,"INDEX_CACHE_ORIGINAL_AUTHORITY_CHANGED");
  }else if("ACTIVATION".equals(kind)&&"dataset.index.activate".equals(operation)){
   if(c.containsKey("origin_run_id")||!auth.canUseDatasetBound(actor,d,v,i,Set.of(operation),"VIEW_BUILD",binding))
    fail(HttpStatus.NOT_FOUND,"INDEX_CACHE_ACTIVATION_AUTHORITY_NOT_AVAILABLE");
  }else fail(HttpStatus.FORBIDDEN,"INDEX_CACHE_PURPOSE_DENIED");
  var rows=jdbc.queryForList("""
   SELECT i.index_repo_id,i.index_commit_id FROM mm_dataset d JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
   JOIN mm_index_version i ON i.dataset_id=d.dataset_id AND i.dataset_version_id=v.version_id AND i.index_repo_id=d.index_repo_id
   JOIN artifact_commit raw ON raw.repo_id=d.raw_repo_id AND raw.commit_id=v.raw_commit_id AND raw.commit_status='PUBLISHED'
   JOIN artifact_commit idx ON idx.repo_id=i.index_repo_id AND idx.commit_id=i.index_commit_id AND idx.commit_status='PUBLISHED'
   WHERE d.dataset_id=? AND v.version_id=? AND i.index_version_id=? AND
     (i.index_status='READY' OR (?='ORIGINAL_BUILD' AND i.index_status='PREWARMING'))
   """,d,v,i,kind);
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"INDEX_CACHE_FROZEN_SNAPSHOT_NOT_AVAILABLE");
  var row=rows.get(0);
  if(!id(c,"index_repo_id").equals(row.get("index_repo_id"))||!id(c,"index_commit_id").equals(row.get("index_commit_id")))
   fail(HttpStatus.FORBIDDEN,"INDEX_CACHE_OUTPUT_SNAPSHOT_CHANGED");
  return new Scope(actor,session,d,v,i,(UUID)row.get("index_repo_id"),(UUID)row.get("index_commit_id"),binding,kind,operation,origin);
 }
 static long credentialLifetimeLimit(Scope scope){
  return scope.originRun()!=null&&"ORIGINAL_BUILD".equals(scope.kind())&&"dataset.index.build".equals(scope.operation())
    ?PREPARED_CREDENTIAL_SECONDS:DEFAULT_CREDENTIAL_SECONDS;
 }
 private Ticket issue(Scope s){
  String token=jwt.generateIndexCacheToken(s,credentialLifetimeLimit(s));
  resolve(jwt.parseClaims(token,JwtService.TokenType.INDEX_CACHE_IO));
  auditIssued(s);
  return new Ticket(s,new Credential("Bearer "+token,"HUMAN"));
 }
 public Ticket forPreparedIndex(UUID dataset,UUID version,UUID index){
  var o=preparedOrigin(dataset,version,index);var actor=auth.actorFor(o.user());
  return issue(new Scope(actor,o.session(),dataset,version,index,o.outputRepo(),o.outputCommit(),o.grant(),"ORIGINAL_BUILD","dataset.index.build",o.run()));
 }
 /** Trusted activation path only; no HTTP endpoint issues these credentials. */
 public Ticket forActivation(UUID dataset,UUID version,UUID index){
  requireLiveRequest();
  if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)||
    !(attrs.getRequest().getAttribute("ds.accessClaims") instanceof Claims))fail(HttpStatus.UNAUTHORIZED,"ACTIVATION_ACCESS_SESSION_REQUIRED");
  var request=((ServletRequestAttributes)RequestContextHolder.getRequestAttributes()).getRequest();
  var claims=(Claims)request.getAttribute("ds.accessClaims");var actor=sessions.authenticateAccess(claims);
  auth.requireOperation(actor,"dataset.index.activate");AuthorizationService.GrantBinding binding=null;
  for(var row:jdbc.queryForList("SELECT grant_id,version FROM ds_resource_grant WHERE dataset_id=? AND state='ACTIVE' ORDER BY grant_id",dataset)){
   var candidate=new AuthorizationService.GrantBinding((UUID)row.get("grant_id"),((Number)row.get("version")).longValue());
   if(auth.canUseDatasetBound(actor,dataset,version,index,Set.of("dataset.index.activate"),"VIEW_BUILD",candidate)){binding=candidate;break;}
  }
  if(binding==null)fail(HttpStatus.NOT_FOUND,"ACTIVATION_COMPLETE_GRANT_REQUIRED");
  var rows=jdbc.queryForList("SELECT index_repo_id,index_commit_id FROM mm_index_version WHERE dataset_id=? AND dataset_version_id=? AND index_version_id=? AND index_status='READY'",dataset,version,index);
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"READY_INDEX_CACHE_SCOPE_REQUIRED");
  return issue(new Scope(actor,id(claims,"sid"),dataset,version,index,(UUID)rows.get(0).get("index_repo_id"),(UUID)rows.get(0).get("index_commit_id"),
    binding,"ACTIVATION","dataset.index.activate",null));
 }
 public void revalidate(Ticket ticket){
  requireLiveRequest();Scope current=resolve(jwt.parseClaims(ticket.credential().authorization().substring(7),JwtService.TokenType.INDEX_CACHE_IO));
  if(!current.equals(ticket.scope()))fail(HttpStatus.UNAUTHORIZED,"INDEX_CACHE_TICKET_CHANGED");
 }
 /** READY cache recovery is an independent institutional action, never a human publication fallback. */
 public Credential forServiceRecovery(UUID dataset,UUID version,UUID index){
  if(RequestContextHolder.getRequestAttributes()!=null)fail(HttpStatus.FORBIDDEN,"BACKGROUND_SERVICE_CACHE_REQUIRED");
  if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM mm_index_version WHERE dataset_id=? AND dataset_version_id=? AND index_version_id=? AND index_status='READY')",Boolean.class,dataset,version,index)))
   fail(HttpStatus.NOT_FOUND,"READY_SERVICE_CACHE_REQUIRED");
  for(UUID service:jdbc.queryForList("""
   SELECT DISTINCT s.service_subject_id FROM ds_service_subject s JOIN ds_resource_grant g
    ON g.subject_kind='SERVICE' AND g.subject_id=s.service_subject_id AND g.dataset_id=? AND g.state='ACTIVE'
   WHERE s.purpose='VIEW_BUILD' AND s.active AND s.closed_at IS NULL ORDER BY s.service_subject_id
   """,UUID.class,dataset)){
   try{return new Credential("Bearer "+services.bearer(service,dataset,version,index),"SERVICE");}
   catch(ResponseStatusException denied){if(!Set.of(401,403,404).contains(denied.getStatusCode().value()))throw denied;}
  }
  fail(HttpStatus.NOT_FOUND,"EXPLICIT_READY_CACHE_SERVICE_GRANT_REQUIRED");return null;
 }
 private void auditIssued(Scope s){
  jdbc.update("""
   INSERT INTO ds_authorization_audit(actor_user_id,actor_org_id,operation_code,target_kind,target_id,outcome,request_id,scope,reason_code)
   VALUES(?,?,?,'INDEX_VERSION',?,'SUCCESS',?,
    jsonb_build_object('datasetId',?::text,'versionId',?::text,'grantId',?::text,'grantVersion',?::bigint,'cacheKind',?::text),
    'SCOPED_INDEX_CACHE_CREDENTIAL_ISSUED')
   """,s.actor().userId(),s.actor().orgId(),s.operation(),s.index().toString(),UUID.randomUUID().toString(),
    s.dataset().toString(),s.version().toString(),s.grant().grantId().toString(),s.grant().version(),s.kind());
 }
 private void auditRequest(HttpServletRequest request,Claims claims,Scope scope,String outcome,String reason){
  String flag="ds.indexCacheAudit."+outcome;if(Boolean.TRUE.equals(request.getAttribute(flag)))return;
  UUID user;try{user=id(claims,"uid");}catch(ResponseStatusException invalid){return;}
  var rows=jdbc.queryForList("SELECT org_id FROM fwd_user WHERE user_id=?",UUID.class,user);if(rows.size()!=1)return;
  String operation=claims.get("operation",String.class);if(operation==null||!Set.of("dataset.index.build","dataset.index.activate").contains(operation))return;
  String target=null;try{target=id(claims,"index_version_id").toString();}catch(ResponseStatusException ignored){}
  String code=reason!=null&&reason.matches("[A-Z0-9_]{1,80}")?reason:"INDEX_CACHE_SCOPE_REJECTED";
  jdbc.update("""
   INSERT INTO ds_authorization_audit(actor_user_id,actor_org_id,operation_code,target_kind,target_id,outcome,request_id,scope,reason_code)
   VALUES(?,?,?,'INDEX_VERSION',?,?,?,
    jsonb_build_object('cacheKind',?::text,'purpose','VIEW_BUILD','method',?::text),?)
   """,user,rows.get(0),operation,target,outcome,UUID.randomUUID().toString(),claims.get("cache_kind",String.class),request.getMethod(),code);
  request.setAttribute(flag,true);
 }
 public Scope authenticate(HttpServletRequest request,Claims claims){
  try{
   Scope s=resolve(claims);String path=request.getRequestURI();
   String base="/api/mm/index-cache/datasets/"+s.dataset()+"/versions/"+s.version()+"/indexes/"+s.index();
   if(!"GET".equals(request.getMethod())||!(path.equals("/api/mm/index-cache/authority")||path.equals(base+"/bundle")||
      path.equals("/api/artifacts/"+s.indexRepo()+"/commits/"+s.indexCommit()+"/files/content")))
    fail(HttpStatus.FORBIDDEN,"INDEX_CACHE_ROUTE_OUTSIDE_SCOPE");
   request.setAttribute(ATTRIBUTE,s);request.setAttribute("ds.indexCacheClaims",claims);
   auditRequest(request,claims,s,"ALLOW","SCOPED_INDEX_CACHE_READ_ALLOWED");return s;
  }catch(ResponseStatusException denied){auditRequest(request,claims,null,"DENY",denied.getReason());throw denied;}
 }
 public Scope current(){
  if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs&&attrs.getRequest().getAttribute("ds.indexCacheClaims") instanceof Claims claims)
   return authenticate(attrs.getRequest(),claims);
  fail(HttpStatus.UNAUTHORIZED,"SCOPED_INDEX_CACHE_CREDENTIAL_REQUIRED");return null;
 }
 public static boolean matches(Scope s,UUID dataset,UUID version,UUID index){return s.dataset().equals(dataset)&&(version==null||s.version().equals(version))&&(index==null||s.index().equals(index));}
 public void requireArtifact(UUID repo,UUID commit,boolean write){
  Scope s=current();if(write||!s.indexRepo().equals(repo)||!s.indexCommit().equals(commit))fail(HttpStatus.FORBIDDEN,"INDEX_CACHE_ARTIFACT_OUTSIDE_SCOPE");
 }
 public Map<String,Object> authority(){
  Scope s=current();var out=new LinkedHashMap<String,Object>();
  out.put("subjectKind","USER");out.put("userId",s.actor().userId());out.put("orgId",s.actor().orgId());out.put("sessionId",s.session());
  out.put("datasetId",s.dataset());out.put("versionId",s.version());out.put("indexVersionId",s.index());
  out.put("operation",s.operation());out.put("purpose","VIEW_BUILD");out.put("cacheKind",s.kind());return out;
 }
}
