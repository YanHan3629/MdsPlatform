package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.databind.*;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Exact original human authority for a fixed, private materialization preview. */
@Service
public class ControlledViewPreviewAuthority {
 public static final String KIND="CONTROLLED_VIEW_PREVIEW",SHARE="dataset.share.manage",RAW="data.raw.download";
 private final JdbcTemplate db;private final AuthorizationService auth;private final IdentitySessionService sessions;
 private final ControlledViewProposalManagement proposals;private final ControlledViewWorkerScriptFactory scripts;
 private final String image,imageSha;
 public ControlledViewPreviewAuthority(JdbcTemplate db,AuthorizationService auth,IdentitySessionService sessions,
  ControlledViewProposalManagement proposals,ControlledViewWorkerScriptFactory scripts,
  @Value("${mm.controlled-view.preview-image:sirius-mm-index-builder:latest}") String image,
  @Value("${mm.controlled-view.preview-image-sha256:}") String imageSha){
  this.db=db;this.auth=auth;this.sessions=sessions;this.proposals=proposals;this.scripts=scripts;this.image=image;this.imageSha=imageSha;
 }
 public record Attempt(Map<String,Object> row,JsonNode proposal,ControlledViewWorkerScriptFactory.Prepared prepared,AuthorizationService.Actor actor){
  public UUID id(){return uuid(row.get("attempt_id"));}public UUID proposalId(){return uuid(row.get("proposal_id"));}
  public UUID job(){return uuid(row.get("job_id"));}public UUID inputRepo(){return uuid(row.get("input_repo_id"));}
  public UUID inputCommit(){return uuid(row.get("input_commit_id"));}public UUID outputRepo(){return uuid(row.get("output_repo_id"));}
  public UUID outputCommit(){return uuid(row.get("output_commit_id"));}public UUID session(){return uuid(row.get("parent_session_id"));}
 }
 private static void fail(HttpStatus status,String code){throw new ResponseStatusException(status,code);}
 private static UUID uuid(Object value){return value==null?null:UUID.fromString(value.toString());}
 private static long number(Object value){return ((Number)value).longValue();}
 private static UUID id(Map<?,?> scope,String key){return UUID.fromString((String)scope.get(key));}
 private boolean exists(String sql,Object... args){return Boolean.TRUE.equals(db.queryForObject(sql,Boolean.class,args));}
 public static String hash(JsonNode value){return HexFormat.of().formatHex(digest(ControlledViewWorkerScriptFactory.canonical(value)));}
 private static byte[] digest(byte[] value){try{return java.security.MessageDigest.getInstance("SHA-256").digest(value);}catch(Exception e){throw new IllegalStateException(e);}}
 public void configured(){
  if(image==null||image.isBlank()||image.chars().anyMatch(Character::isWhitespace)||imageSha==null||!imageSha.matches("[a-f0-9]{64}"))
   fail(HttpStatus.SERVICE_UNAVAILABLE,"CONTROLLED_VIEW_IMAGE_CONFIGURATION_REQUIRED");
 }
 public String image(){configured();return image;}public String imageSha(){configured();return imageSha;}
 public Claims interactive(){
  if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes w)||!(w.getRequest().getAttribute("ds.accessClaims") instanceof Claims))
   fail(HttpStatus.UNAUTHORIZED,"INTERACTIVE_SESSION_REQUIRED");
  return (Claims)((ServletRequestAttributes)RequestContextHolder.getRequestAttributes()).getRequest().getAttribute("ds.accessClaims");
 }
 public AuthorizationService.Actor interactiveActor(){
  var a=sessions.authenticateAccess(interactive());auth.requireOperation(a,SHARE);
  if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes w)w.getRequest().setAttribute(AuthorizationService.REQUEST_OPERATION,SHARE);
  return a;
 }
 private Map<String,Object> row(UUID attempt){
  var rows=db.queryForList("""
   SELECT a.*,s.state AS preview_state,s.version AS state_version,s.verified_manifest_sha256,s.verified_manifest_object_key
   FROM ds_controlled_view_preview_attempt a JOIN ds_controlled_view_preview_state s ON s.attempt_id=a.attempt_id
   WHERE a.attempt_id=?
   """,attempt);
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"CONTROLLED_VIEW_PREVIEW_NOT_AVAILABLE");return rows.get(0);
 }
 public Attempt forActor(UUID attempt,AuthorizationService.Actor actor,UUID session){
  configured();var r=row(attempt);
  if(!actor.userId().equals(r.get("actor_user_id"))||!actor.orgId().equals(r.get("owner_org_id"))||
   !session.equals(r.get("parent_session_id"))||actor.tokenVersion()!=((Number)r.get("actor_token_version")).intValue())
   fail(HttpStatus.NOT_FOUND,"CONTROLLED_VIEW_PREVIEW_NOT_AVAILABLE");
  var original=proposals.execution(uuid(r.get("proposal_id")),actor,session);JsonNode p=original.proposal();
  var prepared=scripts.preparePreview(uuid(p.path("source").path("rawRepoId").asText()),p.path("inventory"),p.path("rules"),attempt);
  if(!prepared.scriptSha256().equals(r.get("script_sha256").toString().trim())||
   !hash(p).equals(r.get("proposal_sha256").toString().trim())||!image.equals(r.get("image"))||!imageSha.equals(r.get("image_sha256").toString().trim()))
   fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_PREVIEW_CODE_OR_POLICY_CHANGED");
  if(!p.path("source").path("rawRepoId").asText().equals(r.get("input_repo_id").toString())||
   !p.path("source").path("rawCommitId").asText().equals(r.get("input_commit_id").toString())||
   !exists("""
    SELECT EXISTS(SELECT 1 FROM artifact_repo o JOIN artifact_commit c ON c.repo_id=o.repo_id
     WHERE o.repo_id=? AND c.commit_id=? AND o.org_id=? AND o.owner_user_id=? AND o.visibility='PRIVATE'
      AND c.commit_status='DRAFT' AND c.meta->>'managedControlledViewPreview'='true' AND c.meta->>'attemptId'=?
      AND NOT EXISTS(SELECT 1 FROM mm_dataset d WHERE d.raw_repo_id=o.repo_id OR d.index_repo_id=o.repo_id))
    """,r.get("output_repo_id"),r.get("output_commit_id"),actor.orgId(),actor.userId(),attempt.toString()))
   fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_PREVIEW_OUTPUT_BINDING_CHANGED");
  var jobs=db.queryForList("SELECT image,cmd,params,org_id FROM job WHERE job_id=?",r.get("job_id"));
  if(jobs.size()!=1||!actor.orgId().equals(jobs.get(0).get("org_id"))||!image.equals(jobs.get(0).get("image"))||
   !prepared.scriptSha256().equals(JobExecutionDefinitionService.sha(Objects.toString(jobs.get(0).get("cmd"),"")))||
   !hash(parse(jobs.get(0).get("params"))).equals(r.get("parameters_sha256").toString().trim()))
   fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_PREVIEW_JOB_CHANGED");
  if(!db.queryForList("SELECT input_repo_id FROM job_input WHERE job_id=?",UUID.class,r.get("job_id")).equals(List.of(uuid(r.get("input_repo_id"))))||
   !db.queryForList("SELECT output_repo_id FROM job_output WHERE job_id=?",UUID.class,r.get("job_id")).equals(List.of(uuid(r.get("output_repo_id")))))
   fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_PREVIEW_DECLARATION_CHANGED");
  return new Attempt(Collections.unmodifiableMap(new LinkedHashMap<>(r)),p,prepared,actor);
 }
 private JsonNode parse(Object value){try{return new ObjectMapper().readTree(value.toString());}catch(Exception e){throw new IllegalStateException("PREVIEW_METADATA_INVALID");}}
 public Attempt forHuman(UUID attempt){Claims c=interactive();var actor=interactiveActor();return forActor(attempt,actor,uuid(c.get("sid",String.class)));}
 public Attempt preflight(UUID job,UUID attempt,Claims parent){
  if(!"ACCESS".equals(parent.get("token_type",String.class)))fail(HttpStatus.UNAUTHORIZED,"PREVIEW_INTERACTIVE_ORIGIN_REQUIRED");
  var a=forActor(attempt,sessions.authenticateAccess(parent),uuid(parent.get("sid",String.class)));
  if(!a.job().equals(job)||!"BUILDING".equals(a.row().get("preview_state")))fail(HttpStatus.CONFLICT,"PREVIEW_ATTEMPT_ALREADY_SETTLED");
  if(exists("SELECT EXISTS(SELECT 1 FROM ds_controlled_view_preview_origin WHERE attempt_id=?)",attempt))fail(HttpStatus.CONFLICT,"PREVIEW_ATTEMPT_ALREADY_STARTED");
  return a;
 }
 public void prepare(UUID job,UUID attempt,Map<String,String> scope,Claims parent){
  var a=preflight(job,attempt,parent);
  for(var pair:Map.of("inputRepoId",a.inputRepo(),"inputCommitId",a.inputCommit(),"outputRepoId",a.outputRepo(),"outputCommitId",a.outputCommit(),
   "userId",a.actor().userId(),"orgId",a.actor().orgId(),"parentSessionId",a.session()).entrySet())
   if(!pair.getValue().equals(id(scope,pair.getKey())))fail(HttpStatus.FORBIDDEN,"PREVIEW_ORIGIN_COORDINATE_MISMATCH");
  db.update("INSERT INTO ds_controlled_view_preview_origin(run_id,attempt_id,job_id) VALUES(?,?,?)",id(scope,"runId"),attempt,job);
  scope.put("jobKind",KIND);scope.put("previewAttemptId",attempt.toString());scope.put("proposalId",a.proposalId().toString());
 }
 private Attempt resolve(Claims claims){
  var actor=sessions.authenticateAccess(claims);Map<?,?> scope=claims.get("job_io_scope",Map.class);
  if(scope==null||!KIND.equals(scope.get("jobKind")))fail(HttpStatus.FORBIDDEN,"TYPED_CONTROLLED_VIEW_PREVIEW_REQUIRED");
  UUID attempt=id(scope,"previewAttemptId");var a=forActor(attempt,actor,id(scope,"parentSessionId"));
  if(!a.proposalId().equals(id(scope,"proposalId"))||!a.job().equals(id(scope,"jobId"))||!actor.userId().equals(id(scope,"userId"))||!actor.orgId().equals(id(scope,"orgId"))||
   !a.inputRepo().equals(id(scope,"inputRepoId"))||!a.inputCommit().equals(id(scope,"inputCommitId"))||!a.outputRepo().equals(id(scope,"outputRepoId"))||!a.outputCommit().equals(id(scope,"outputCommitId"))||
   !exists("""
    SELECT EXISTS(SELECT 1 FROM ds_controlled_view_preview_origin o JOIN job_run r ON r.run_id=o.run_id AND r.job_id=o.job_id
     WHERE o.attempt_id=? AND o.run_id=? AND o.job_id=? AND r.created_by=? AND r.org_id=?)
    """,attempt,id(scope,"runId"),a.job(),actor.userId(),actor.orgId()))
   fail(HttpStatus.FORBIDDEN,"PREVIEW_EXECUTION_ORIGIN_MISMATCH");
  return a;
 }
 public Attempt revalidateRecordedOrigin(Claims claims){return resolve(claims);}
 public Attempt forRecorded(UUID attempt,JobExecutionDefinitionService definitions){
  var rows=db.queryForList("SELECT run_id FROM ds_controlled_view_preview_origin WHERE attempt_id=?",UUID.class,attempt);
  if(rows.size()!=1)fail(HttpStatus.CONFLICT,"PREVIEW_EXECUTION_ORIGIN_REQUIRED");return resolve(definitions.recordedClaims(rows.get(0)));
 }
 public List<Map<String,Object>> inputs(Map<String,String> scope,String script,String image,AuthorizationService.Actor actor){
  var rows=db.queryForList("SELECT attempt_id FROM ds_controlled_view_preview_origin WHERE run_id=? AND job_id=?",UUID.class,id(scope,"runId"),id(scope,"jobId"));
  if(rows.size()!=1||!rows.get(0).equals(id(scope,"previewAttemptId")))fail(HttpStatus.FORBIDDEN,"PREVIEW_FROZEN_ORIGIN_REQUIRED");
  var a=forActor(rows.get(0),actor,id(scope,"parentSessionId"));
  if(!a.prepared().scriptSha256().equals(JobExecutionDefinitionService.sha(script))||!a.row().get("image").equals(image))fail(HttpStatus.CONFLICT,"PREVIEW_FIXED_PROGRAM_REQUIRED");
  var paths=new ArrayList<String>();for(JsonNode file:a.proposal().path("inventory").path("files"))paths.add(file.path("logical_path").asText());
  return List.of(Map.of("repoId",a.inputRepo().toString(),"commitId",a.inputCommit().toString(),"paths",paths));
 }
 public boolean permits(HttpServletRequest request,Claims claims){
  var a=resolve(claims);Map<?,?> scope=claims.get("job_io_scope",Map.class);
  if(!"BUILDING".equals(a.row().get("preview_state"))||
   !exists("SELECT EXISTS(SELECT 1 FROM job_run WHERE run_id=? AND run_status IN('QUEUED','RUNNING'))",id(scope,"runId")))return false;
  String path=request.getRequestURI(),context=request.getContextPath();if(context!=null&&!context.isEmpty())path=path.substring(context.length());
  if("GET".equals(request.getMethod()))return path.equals(files(a.inputRepo(),a.inputCommit()))||path.equals(files(a.inputRepo(),a.inputCommit())+"/content")||
   (path.equals("/api/jobs/"+a.job()+"/entrypoint/content")&&JobExecutionDefinitionService.entrypoint(request))||path.equals("/api/jobs/internal/script/command");
  return "PUT".equals(request.getMethod())&&(path.equals(files(a.outputRepo(),a.outputCommit()))||path.equals("/api/controlled-view-previews/"+a.id()+"/receipt"));
 }
 public Attempt worker(UUID attempt){
  if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes w)||!(w.getRequest().getAttribute("ds.jobIoClaims") instanceof Claims))
   fail(HttpStatus.FORBIDDEN,"TYPED_CONTROLLED_VIEW_PREVIEW_REQUIRED");
  var request=((ServletRequestAttributes)RequestContextHolder.getRequestAttributes()).getRequest();var claims=(Claims)request.getAttribute("ds.jobIoClaims");
  if(!permits(request,claims))fail(HttpStatus.FORBIDDEN,"PREVIEW_WORKER_SCOPE_DENIED");var a=resolve(claims);
  if(!attempt.equals(a.id()))fail(HttpStatus.FORBIDDEN,"PREVIEW_CALLBACK_ATTEMPT_MISMATCH");return a;
 }
 public boolean managed(UUID repo){
  return exists("SELECT EXISTS(SELECT 1 FROM ds_controlled_view_preview_attempt WHERE output_repo_id=?) OR EXISTS(SELECT 1 FROM artifact_commit WHERE repo_id=? AND meta->>'managedControlledViewPreview'='true')",repo,repo);
 }
 public void requireHumanRepository(UUID repo,UUID commit,boolean write){
  var rows=db.queryForList("SELECT attempt_id FROM ds_controlled_view_preview_attempt WHERE output_repo_id=?",UUID.class,repo);
  if(rows.size()!=1||write)fail(HttpStatus.NOT_FOUND,"MANAGED_PREVIEW_OUTPUT_REQUIRES_CURRENT_PREVIEW_AUTHORITY");
  var a=forHuman(rows.get(0));auth.requireOperation(a.actor(),RAW);
  if(!sourceAllows(a,Set.of(RAW)))fail(HttpStatus.NOT_FOUND,"PREVIEW_ORIGINAL_DOWNLOAD_AUTHORITY_REQUIRED");
  requireVerifiedRepository(a,repo,commit);
 }
 /** Metadata and fixed server-side verification do not confer raw file access. */
 public void requireVerifiedRepository(Attempt a,UUID repo,UUID commit){
  if(!repo.equals(a.outputRepo())||!"AWAITING_REVIEW".equals(a.row().get("preview_state"))||commit!=null&&!commit.equals(a.outputCommit()))
   fail(HttpStatus.NOT_FOUND,"VERIFIED_PREVIEW_NOT_AVAILABLE");
  if(!exists("SELECT EXISTS(SELECT 1 FROM artifact_commit WHERE repo_id=? AND commit_id=? AND manifest_object_key=? AND manifest_sha256=?)",
   repo,a.outputCommit(),a.row().get("verified_manifest_object_key"),a.row().get("verified_manifest_sha256")))
   fail(HttpStatus.CONFLICT,"VERIFIED_PREVIEW_MANIFEST_CHANGED");
 }
 private boolean sourceAllows(Attempt a,Set<String> extra){
  JsonNode source=a.proposal().path("source"),original=a.proposal().path("sourceAuthority");
  var operations=new HashSet<>(ControlledViewProposalManagement.sourceOperations(original));operations.addAll(extra);
  return auth.canUseDatasetBound(a.actor(),uuid(source.path("datasetId").asText()),uuid(source.path("versionId").asText()),
   source.path("indexVersionId").isNull()?null:uuid(source.path("indexVersionId").asText()),operations,"VIEW_BUILD",
   new AuthorizationService.GrantBinding(uuid(original.path("grantId").asText()),original.path("version").asLong()));
 }
 public boolean originalByteCopy(Attempt a,String path){
  for(JsonNode rule:a.proposal().path("rules").path("files"))
   if(path.equals(rule.path("outputPath").asText()))return "COMPLETE_FILE".equals(rule.path("kind").asText());
  return false;
 }
 /** A fixed private projection has an explicit proposal-bound build authority.
  * Complete byte copies keep the separate RAW operation on that same source grant. */
 public String readOperation(Attempt a,String path){
  if(!originalByteCopy(a,path)&&a.actor().operations().contains("data.view.export")&&sourceAllows(a,Set.of()))return "data.view.export";
  return a.actor().operations().contains(RAW)&&sourceAllows(a,Set.of(RAW))?RAW:null;
 }
 public Attempt revalidateRead(UUID attempt,Claims parent,String path,String operation){
  var actor=sessions.authenticateAccess(parent);auth.requireOperation(actor,operation);auth.requireOperation(actor,SHARE);
  var a=forActor(attempt,actor,uuid(parent.get("sid",String.class)));requireVerifiedRepository(a,a.outputRepo(),a.outputCommit());
  if(!Set.of("data.view.export",RAW).contains(operation)||("data.view.export".equals(operation)&&originalByteCopy(a,path))||
    !sourceAllows(a,RAW.equals(operation)?Set.of(RAW):Set.of()))fail(HttpStatus.NOT_FOUND,"PRIVATE_PREVIEW_FILE_READ_NOT_AVAILABLE");
  return a;
 }
 public String workerReadOperation(Claims claims){
  return ControlledViewProposalManagement.internalBuild(resolve(claims).proposal().path("sourceAuthority"))?SHARE:RAW;
 }
 public void requireOutputPath(UUID repo,String path){
  var attrs=RequestContextHolder.getRequestAttributes();
  if(!(attrs instanceof ServletRequestAttributes w)||!(w.getRequest().getAttribute("ds.jobIoClaims") instanceof Claims))
   fail(HttpStatus.FORBIDDEN,"PREVIEW_WRITE_REQUIRES_EXACT_WORKER_PHASE");
  var claims=(Claims)((ServletRequestAttributes)attrs).getRequest().getAttribute("ds.jobIoClaims");var a=resolve(claims);
  if(!repo.equals(a.outputRepo())||!exists("""
   SELECT EXISTS(SELECT 1 FROM ds_controlled_view_preview_receipt r,jsonb_array_elements(r.public_manifest) f
    WHERE r.attempt_id=? AND '/'||(f->>'path')=?)
   """,a.id(),com.fwdrobo.sirius.util.PathUtils.normalizePath(path)))
   fail(HttpStatus.FORBIDDEN,"PREVIEW_OUTPUT_PATH_NOT_IN_PRIVATE_RECEIPT");
 }
 public boolean canInspect(AuthorizationService.Actor actor,UUID job,UUID run,String operation){
  var rows=db.queryForList("SELECT attempt_id,parent_session_id,actor_token_version FROM ds_controlled_view_preview_attempt WHERE job_id=? AND owner_org_id=? AND actor_user_id=?",job,actor.orgId(),actor.userId());
  if(rows.size()!=1)return false;
  try{
   var origin=rows.get(0);UUID originalSession=uuid(origin.get("parent_session_id"));
   if(!exists("SELECT EXISTS(SELECT 1 FROM ds_auth_session WHERE session_id=? AND user_id=? AND org_id=? AND token_version=? AND purpose='ACCESS' AND revoked_at IS NULL AND expires_at>clock_timestamp())",
    originalSession,actor.userId(),actor.orgId(),actor.tokenVersion()))return false;
   var a=forActor(uuid(origin.get("attempt_id")),actor,originalSession);
   if(run!=null&&!exists("SELECT EXISTS(SELECT 1 FROM ds_controlled_view_preview_origin WHERE attempt_id=? AND run_id=? AND job_id=?)",a.id(),run,job))return false;
   JsonNode source=a.proposal().path("source"),grant=a.proposal().path("sourceAuthority");
   var requested=new HashSet<>(ControlledViewProposalManagement.sourceOperations(a.proposal().path("sourceAuthority")));requested.add(operation);
   return auth.canUseDatasetBound(actor,uuid(source.path("datasetId").asText()),uuid(source.path("versionId").asText()),
    source.path("indexVersionId").isNull()?null:uuid(source.path("indexVersionId").asText()),requested,"VIEW_BUILD",
    new AuthorizationService.GrantBinding(uuid(grant.path("grantId").asText()),grant.path("version").asLong()));
  }catch(ResponseStatusException e){if(e.getStatusCode().value()>=500)throw e;return false;}
 }
 public boolean isManagedJob(UUID job){return exists("SELECT EXISTS(SELECT 1 FROM ds_controlled_view_preview_attempt WHERE job_id=?)",job);}
 public UUID run(UUID attempt){
  var rows=db.queryForList("SELECT run_id FROM ds_controlled_view_preview_origin WHERE attempt_id=?",UUID.class,attempt);
  if(rows.size()!=1)fail(HttpStatus.CONFLICT,"PREVIEW_EXECUTION_ORIGIN_REQUIRED");return rows.get(0);
 }
 private static String files(UUID repo,UUID commit){return "/api/artifacts/"+repo+"/commits/"+commit+"/files";}
}
