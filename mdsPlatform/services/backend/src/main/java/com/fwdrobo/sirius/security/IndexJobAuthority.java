package com.fwdrobo.sirius.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.*;

/** Immutable human-origin authority for exactly one index execution. */
@Service
public class IndexJobAuthority {
 @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private ControlledViewPublicationAuthority publications;
 private final JdbcTemplate jdbc; private final AuthorizationService auth; private final IdentitySessionService sessions;
 public IndexJobAuthority(JdbcTemplate jdbc,AuthorizationService auth,IdentitySessionService sessions){this.jdbc=jdbc;this.auth=auth;this.sessions=sessions;}
 public record Coordinates(UUID dataset,UUID version,UUID index){}
 public record Origin(UUID run,UUID job,UUID user,UUID org,UUID session,int tokenVersion,Coordinates coordinates,
   UUID inputRepo,UUID inputCommit,UUID outputRepo,UUID outputCommit,UUID previousCommit,AuthorizationService.GrantBinding grant){}
 private static void fail(HttpStatus status,String code){throw new ResponseStatusException(status,code);}
 private static UUID id(Map<?,?> map,String key){return UUID.fromString((String)map.get(key));}
 /** Called by the dedicated index launcher, never selected by arbitrary job parameters. */
 public void prepare(Coordinates coordinates,Map<String,String> scope,Claims parent){
  if(!"ACCESS".equals(parent.get("token_type",String.class)))fail(HttpStatus.UNAUTHORIZED,"INDEX_ORIGIN_ACCESS_SESSION_REQUIRED");
  var actor=sessions.authenticateAccess(parent);
  if(!actor.userId().equals(id(scope,"userId"))||!actor.orgId().equals(id(scope,"orgId"))||
     !id(scope,"parentSessionId").equals(UUID.fromString(parent.get("sid",String.class))))
   fail(HttpStatus.FORBIDDEN,"INDEX_ORIGIN_IDENTITY_MISMATCH");
  auth.lockIdentityMutation();jdbc.execute("SELECT pg_advisory_xact_lock(805014630400001::bigint)");sessions.authenticateAccess(parent);
  var rows=jdbc.queryForList("""
   SELECT d.raw_repo_id,v.raw_commit_id,i.index_repo_id,i.index_commit_id
   FROM mm_dataset d JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
   JOIN mm_index_version i ON i.dataset_id=d.dataset_id AND i.dataset_version_id=v.version_id AND i.index_repo_id=d.index_repo_id
   JOIN artifact_commit raw ON raw.repo_id=d.raw_repo_id AND raw.commit_id=v.raw_commit_id AND raw.commit_status='PUBLISHED'
   JOIN artifact_commit output ON output.repo_id=i.index_repo_id AND output.commit_id=i.index_commit_id AND output.commit_status='DRAFT'
   WHERE d.dataset_id=? AND v.version_id=? AND i.index_version_id=? AND d.org_id=? AND i.index_status='QUEUED'
   """,coordinates.dataset(),coordinates.version(),coordinates.index(),actor.orgId());
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"INDEX_ORIGIN_FROZEN_SCOPE_NOT_AVAILABLE");
  var row=rows.get(0);
  for(String key:List.of("InputRepoId","InputCommitId","OutputRepoId","OutputCommitId")){
   String claim=Character.toLowerCase(key.charAt(0))+key.substring(1);
   String column=switch(key){case "InputRepoId"->"raw_repo_id";case "InputCommitId"->"raw_commit_id";case "OutputRepoId"->"index_repo_id";default->"index_commit_id";};
   if(!id(scope,claim).equals(row.get(column)))fail(HttpStatus.FORBIDDEN,"INDEX_ORIGIN_COORDINATE_MISMATCH");
  }
  UUID previous=scope.containsKey("previousIndexCommitId")?id(scope,"previousIndexCommitId"):null;
  if(previous!=null&&!Boolean.TRUE.equals(jdbc.queryForObject("""
   SELECT EXISTS(SELECT 1 FROM mm_index_version i JOIN artifact_commit c ON c.repo_id=i.index_repo_id AND c.commit_id=i.index_commit_id
    WHERE i.dataset_id=? AND i.index_repo_id=? AND i.index_commit_id=? AND i.index_status='READY' AND c.commit_status='PUBLISHED')
   """,Boolean.class,coordinates.dataset(),id(scope,"outputRepoId"),previous)))
   fail(HttpStatus.FORBIDDEN,"INDEX_ORIGIN_PREVIOUS_SNAPSHOT_NOT_AVAILABLE");
  AuthorizationService.GrantBinding binding=null;
  if(publications.managedDataset(coordinates.dataset()))binding=publications.prepareIndex(coordinates,scope,parent);
  else for(var grant:jdbc.queryForList("SELECT grant_id,version FROM ds_resource_grant WHERE dataset_id=? AND state='ACTIVE' AND scope_kind='DATASET_CUSTODY' ORDER BY grant_id",coordinates.dataset())){
   var candidate=new AuthorizationService.GrantBinding((UUID)grant.get("grant_id"),((Number)grant.get("version")).longValue());
   if(auth.canUseDatasetBound(actor,coordinates.dataset(),coordinates.version(),null,Set.of("dataset.index.build"),"VIEW_BUILD",candidate)){binding=candidate;break;}
  }
  if(binding==null)fail(HttpStatus.NOT_FOUND,"INDEX_ORIGIN_COMPLETE_GRANT_REQUIRED");
  jdbc.update("""
   INSERT INTO ds_index_execution_origin(run_id,job_id,actor_user_id,actor_org_id,parent_session_id,token_version,
     dataset_id,version_id,index_version_id,input_repo_id,input_commit_id,output_repo_id,output_commit_id,
     previous_index_commit_id,grant_id,grant_version)
   VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
   """,id(scope,"runId"),id(scope,"jobId"),actor.userId(),actor.orgId(),id(scope,"parentSessionId"),actor.tokenVersion(),
    coordinates.dataset(),coordinates.version(),coordinates.index(),id(scope,"inputRepoId"),id(scope,"inputCommitId"),
    id(scope,"outputRepoId"),id(scope,"outputCommitId"),previous,binding.grantId(),binding.version());
  scope.put("jobKind","INDEX_BUILD");scope.put("datasetId",coordinates.dataset().toString());
  scope.put("versionId",coordinates.version().toString());scope.put("indexVersionId",coordinates.index().toString());
 }
 private Origin resolve(Claims claims){
  Map<?,?> scope=claims.get("job_io_scope",Map.class);
  if(scope==null||!"INDEX_BUILD".equals(scope.get("jobKind")))fail(HttpStatus.FORBIDDEN,"TYPED_INDEX_EXECUTION_REQUIRED");
  var rows=jdbc.queryForList("SELECT * FROM ds_index_execution_origin WHERE run_id=? AND job_id=?",id(scope,"runId"),id(scope,"jobId"));
  if(rows.size()!=1)fail(HttpStatus.FORBIDDEN,"INDEX_ORIGIN_NOT_AVAILABLE");
  var r=rows.get(0);
  var actor=sessions.authenticateAccess(claims);
  Map<String,String> mapping=Map.ofEntries(Map.entry("userId","actor_user_id"),Map.entry("orgId","actor_org_id"),
   Map.entry("parentSessionId","parent_session_id"),Map.entry("datasetId","dataset_id"),Map.entry("versionId","version_id"),
   Map.entry("indexVersionId","index_version_id"),Map.entry("inputRepoId","input_repo_id"),Map.entry("inputCommitId","input_commit_id"),
   Map.entry("outputRepoId","output_repo_id"),Map.entry("outputCommitId","output_commit_id"));
  for(var entry:mapping.entrySet())if(!id(scope,entry.getKey()).equals(r.get(entry.getValue())))fail(HttpStatus.FORBIDDEN,"INDEX_ORIGIN_SCOPE_MISMATCH");
  UUID previous=scope.containsKey("previousIndexCommitId")?id(scope,"previousIndexCommitId"):null;
  if(!Objects.equals(previous,r.get("previous_index_commit_id"))||!actor.userId().equals(r.get("actor_user_id"))||
     !actor.orgId().equals(r.get("actor_org_id"))||actor.tokenVersion()!=((Number)r.get("token_version")).intValue())
   fail(HttpStatus.UNAUTHORIZED,"INDEX_ORIGIN_IDENTITY_CHANGED");
  Coordinates c=new Coordinates((UUID)r.get("dataset_id"),(UUID)r.get("version_id"),(UUID)r.get("index_version_id"));
  var binding=new AuthorizationService.GrantBinding((UUID)r.get("grant_id"),((Number)r.get("grant_version")).longValue());
  if(publications.managedDataset(c.dataset()))publications.revalidateIndex(c.dataset(),c.version(),c.index(),id(scope,"jobId"),id(scope,"runId"),actor.userId(),id(scope,"parentSessionId"),binding);
  else if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ds_resource_grant WHERE grant_id=? AND version=? AND dataset_id=? AND scope_kind='DATASET_CUSTODY')",Boolean.class,binding.grantId(),binding.version(),c.dataset()))||!auth.canUseDatasetBound(actor,c.dataset(),c.version(),null,Set.of("dataset.index.build"),"VIEW_BUILD",binding))
   fail(HttpStatus.NOT_FOUND,"INDEX_ORIGIN_GRANT_REVOKED_OR_CHANGED");
  var current=jdbc.queryForList("""
   SELECT i.index_version_id FROM mm_index_version i JOIN mm_dataset d ON d.dataset_id=i.dataset_id
   JOIN mm_dataset_version v ON v.version_id=i.dataset_version_id AND v.dataset_id=d.dataset_id
   JOIN job_run j ON j.run_id=? AND j.job_id=? AND j.created_by=? AND j.org_id=?
   WHERE i.dataset_id=? AND i.dataset_version_id=? AND i.index_version_id=?
    AND i.build_job_id=j.job_id AND i.build_run_id=j.run_id
    AND d.raw_repo_id=? AND v.raw_commit_id=? AND d.index_repo_id=? AND i.index_repo_id=? AND i.index_commit_id=?
   """,id(scope,"runId"),id(scope,"jobId"),actor.userId(),actor.orgId(),c.dataset(),c.version(),c.index(),
    id(scope,"inputRepoId"),id(scope,"inputCommitId"),id(scope,"outputRepoId"),id(scope,"outputRepoId"),id(scope,"outputCommitId"));
  if(current.size()!=1)fail(HttpStatus.FORBIDDEN,"INDEX_ORIGIN_RUNTIME_TUPLE_CHANGED");
  return new Origin(id(scope,"runId"),id(scope,"jobId"),actor.userId(),actor.orgId(),id(scope,"parentSessionId"),actor.tokenVersion(),c,
   id(scope,"inputRepoId"),id(scope,"inputCommitId"),id(scope,"outputRepoId"),id(scope,"outputCommitId"),previous,binding);
 }
 public Origin revalidateRecordedOrigin(Claims claims){
  if(!"JOB_IO".equals(claims.get("token_type",String.class)))fail(HttpStatus.FORBIDDEN,"TYPED_INDEX_ORIGIN_CREDENTIAL_REQUIRED");
  return resolve(claims);
 }
 public boolean permits(HttpServletRequest request,Claims claims){
  Origin o=resolve(claims);
  String path=request.getRequestURI(),context=request.getContextPath();
  if(context!=null&&!context.isEmpty())path=path.substring(context.length());
  String base="/api/mm/worker/datasets/"+o.coordinates().dataset()+"/versions/"+o.coordinates().version()+"/indexes/"+o.coordinates().index();
  boolean callback="POST".equals(request.getMethod())&&(path.equals(base+"/ready")||path.equals(base+"/failed"));
  boolean running=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM job_run WHERE run_id=? AND run_status IN('QUEUED','RUNNING'))",Boolean.class,o.run()));
  if(!running&&!(callback&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ds_index_callback_receipt WHERE run_id=?)",Boolean.class,o.run()))))return false;
  if(callback)return true;
  if(!running||Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ds_index_callback_receipt WHERE run_id=?)",Boolean.class,o.run())))return false;
  String input=files(o.inputRepo(),o.inputCommit()),output=files(o.outputRepo(),o.outputCommit());
  if("GET".equals(request.getMethod())){
   if(path.equals(input)||path.equals(input+"/content")||(path.equals("/api/jobs/"+o.job()+"/entrypoint/content")&&JobExecutionDefinitionService.entrypoint(request))||path.equals("/api/jobs/internal/script/command"))return true;
   if(o.previousCommit()!=null){String p=files(o.outputRepo(),o.previousCommit());return path.equals(p)||path.equals(p+"/content");}
  }
  return "PUT".equals(request.getMethod())&&path.equals(output);
 }
 public Origin lockAndCurrent(UUID dataset,UUID version,UUID index){
  auth.lockIdentityMutation();jdbc.execute("SELECT pg_advisory_xact_lock(805014630400001::bigint)");
  if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs))fail(HttpStatus.UNAUTHORIZED,"INDEX_WORKER_REQUEST_REQUIRED");
  var request=((ServletRequestAttributes)RequestContextHolder.getRequestAttributes()).getRequest();
  if(!(request.getAttribute("ds.jobIoClaims") instanceof Claims))fail(HttpStatus.FORBIDDEN,"TYPED_INDEX_EXECUTION_REQUIRED");
  var claims=(Claims)request.getAttribute("ds.jobIoClaims");
  if(claims.getExpiration()==null||!claims.getExpiration().after(new Date())||!permits(request,claims))fail(HttpStatus.FORBIDDEN,"INDEX_WORKER_SCOPE_DENIED");
  Origin o=resolve(claims);
  if(!o.coordinates().equals(new Coordinates(dataset,version,index)))fail(HttpStatus.FORBIDDEN,"INDEX_CALLBACK_SCOPE_MISMATCH");
  return o;
 }
 private final ThreadLocal<Origin> callbackMetadata=new ThreadLocal<>();
 public final class CallbackMetadata implements AutoCloseable {
  private final Origin origin;private final Thread owner;private boolean closed;
  private CallbackMetadata(Origin origin){this.origin=origin;this.owner=Thread.currentThread();}
  public void close(){
   if(closed)return;
   if(owner!=Thread.currentThread()||callbackMetadata.get()!=origin)throw new IllegalStateException("INDEX_CALLBACK_METADATA_SCOPE_CHANGED");
   callbackMetadata.remove();closed=true;
  }
 }
 /** Only the already validated native callback may inspect its exact version metadata before consumer READY grants. */
 public CallbackMetadata enterCallbackMetadata(Origin origin){
  if(callbackMetadata.get()!=null)fail(HttpStatus.CONFLICT,"INDEX_CALLBACK_METADATA_NESTING_DENIED");
  Origin current=lockAndCurrent(origin.coordinates().dataset(),origin.coordinates().version(),origin.coordinates().index());
  if(!current.equals(origin))fail(HttpStatus.FORBIDDEN,"INDEX_CALLBACK_METADATA_ORIGIN_CHANGED");
  callbackMetadata.set(origin);return new CallbackMetadata(origin);
 }
 public boolean canReadCallbackMetadata(UUID dataset,UUID version,UUID index){
  Origin scoped=callbackMetadata.get();if(scoped==null)return false;
  Coordinates c=scoped.coordinates();
  if(!c.dataset().equals(dataset)||version!=null&&!c.version().equals(version)||index!=null&&!c.index().equals(index))
   fail(HttpStatus.NOT_FOUND,"INDEX_CALLBACK_METADATA_OUTSIDE_SCOPE");
  Origin current=lockAndCurrent(c.dataset(),c.version(),c.index());
  if(!current.equals(scoped))fail(HttpStatus.FORBIDDEN,"INDEX_CALLBACK_METADATA_ORIGIN_CHANGED");
  return true;
 }
 private static String files(UUID repo,UUID commit){return "/api/artifacts/"+repo+"/commits/"+commit+"/files";}
}
