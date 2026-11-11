package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.databind.*;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Original review authority for one derived raw snapshot and one native index. */
@Service
public class ControlledViewPublicationAuthority {
 public static final String SHARE="dataset.share.manage",RAW="data.raw.download",PUBLISH="dataset.publish",BUILD="dataset.index.build";
 private final JdbcTemplate db;private final AuthorizationService auth;private final IdentitySessionService sessions;private final ObjectMapper json;
 private final ControlledViewSourceLineage lineage;private final String image,imageSha;private final NativeIndexWorkerScriptFactory nativeScripts;
 private final ThreadLocal<UUID> building=new ThreadLocal<>();
 public ControlledViewPublicationAuthority(JdbcTemplate db,AuthorizationService auth,IdentitySessionService sessions,ObjectMapper json,NativeIndexWorkerScriptFactory nativeScripts,ControlledViewSourceLineage lineage,
  @Value("${mm.index-builder.image:sirius-mm-index-builder:latest}") String image,
  @Value("${mm.index-builder.image-sha256:}") String imageSha){
  this.db=db;this.auth=auth;this.sessions=sessions;this.json=json;this.image=image;this.nativeScripts=nativeScripts;this.imageSha=imageSha;this.lineage=lineage;
 }
 private static void fail(HttpStatus status,String reason){throw new ResponseStatusException(status,reason);}
 public static UUID uuid(Object x){return x==null?null:UUID.fromString(x.toString());}
 private static long num(Object x){return ((Number)x).longValue();}
 private boolean exists(String sql,Object... args){return Boolean.TRUE.equals(db.queryForObject(sql,Boolean.class,args));}
 public JsonNode parse(Object x){try{return json.readTree(x.toString());}catch(Exception e){throw new IllegalStateException("CONTROLLED_VIEW_PUBLICATION_METADATA_INVALID");}}
 public void configured(){
  if(image==null||image.isBlank()||image.chars().anyMatch(Character::isWhitespace)||imageSha==null||!imageSha.matches("[a-f0-9]{64}"))
   fail(HttpStatus.SERVICE_UNAVAILABLE,"CONTROLLED_VIEW_INDEX_IMAGE_CONFIGURATION_REQUIRED");
 }
 public String command(){configured();return nativeScripts.script();}
 public Map<String,Object> indexProgram(){
  configured();return Map.of("image",image,"commandSha256",nativeScripts.scriptSha256(),"imageSha256",imageSha,"modelName","controlled-view-clip","indexType","FLAT","indexConfig",Map.of(),"nativeModuleManifestSha256",nativeScripts.moduleManifestSha256(),"nativeCapsuleSha256",nativeScripts.capsuleSha256(),"callbackProgram",Map.of("source","CURRENT_JAR_CAPSULE_V1","path","/tmp/approved-native-index/index_callback.py","sha256",nativeScripts.callbackSha256()));
 }
 public Claims interactive(){
  if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes w)||!(w.getRequest().getAttribute("ds.accessClaims") instanceof Claims))
   fail(HttpStatus.UNAUTHORIZED,"CONTROLLED_VIEW_PUBLICATION_ACCESS_SESSION_REQUIRED");
  return (Claims)((ServletRequestAttributes)RequestContextHolder.getRequestAttributes()).getRequest().getAttribute("ds.accessClaims");
 }
 public AuthorizationService.Actor actor(){
  var a=sessions.authenticateAccess(interactive());auth.requireOperation(a,SHARE);return a;
 }
 public void fence(){auth.lockIdentityMutation();db.execute("SELECT pg_advisory_xact_lock(805014630400001::bigint)");}
 public Map<String,Object> origin(UUID id){
  var rows=db.queryForList("""
   SELECT p.*,r.payload,r.payload_sha256,r.actor_user_id,r.actor_org_id,r.parent_session_id,r.actor_token_version,
    s.state AS publication_state,s.version AS publication_version,s.reason_code AS publication_reason_code
   FROM ds_controlled_view_publication p JOIN ds_controlled_view_publication_review r ON r.review_id=p.review_id
   JOIN ds_controlled_view_publication_state s ON s.publication_id=p.publication_id WHERE p.publication_id=?
   """,id);
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"CONTROLLED_VIEW_PUBLICATION_NOT_AVAILABLE");return rows.get(0);
 }
 public JsonNode plan(Map<String,Object> r){
  JsonNode p=parse(r.get("payload"));
  if(!ControlledViewPreviewAuthority.hash(p).equals(r.get("payload_sha256").toString().trim()))fail(HttpStatus.SERVICE_UNAVAILABLE,"CONTROLLED_VIEW_REVIEW_IDENTITY_CHANGED");
  return p;
 }
 public AuthorizationService.GrantBinding binding(JsonNode p){
  JsonNode g=p.path("sourceAuthority");return new AuthorizationService.GrantBinding(uuid(g.path("grantId").asText()),g.path("version").asLong());
 }
 public Set<String> required(JsonNode p){
  var out=new TreeSet<>(ControlledViewProposalManagement.sourceOperations(p.path("sourceAuthority")));
  boolean internal=ControlledViewProposalManagement.internalBuild(p.path("sourceAuthority"));
  p.path("providerOperations").forEach(x->out.add("data.view.export".equals(x.asText())?(internal?SHARE:RAW):x.asText()));return out;
 }
 /** Independent live checks remain valid after the private proposal's UI expiry. */
 public AuthorizationService.Actor current(JsonNode p){
  configured();JsonNode who=p.path("actor"),source=p.path("source"),target=p.path("target");
  UUID user=uuid(who.path("userId").asText()),org=uuid(who.path("orgId").asText()),sid=uuid(who.path("sessionId").asText());
  var a=auth.actorFor(user);
  if(!a.orgId().equals(org)||a.tokenVersion()!=who.path("tokenVersion").asInt()||a.permissionVersion()!=who.path("permissionVersion").asLong()||
   !a.username().equals(who.path("username").asText())||!exists("""
    SELECT EXISTS(SELECT 1 FROM ds_auth_session WHERE session_id=? AND user_id=? AND org_id=? AND token_version=?
     AND purpose='ACCESS' AND revoked_at IS NULL AND expires_at>clock_timestamp())
    """,sid,user,org,a.tokenVersion()))fail(HttpStatus.UNAUTHORIZED,"CONTROLLED_VIEW_ORIGINAL_SESSION_OR_IDENTITY_CHANGED");
  for(String operation:required(p))auth.requireOperation(a,operation);
  for(JsonNode operation:p.path("providerOperations"))auth.requireOperation(a,operation.asText());
  auth.requireOperation(a,PUBLISH);auth.requireOperation(a,BUILD);auth.requireOperation(a,"dataset.create");
  UUID dataset=uuid(source.path("datasetId").asText()),version=uuid(source.path("versionId").asText());
  UUID index=source.path("indexVersionId").isNull()?null:uuid(source.path("indexVersionId").asText());
  lineage.requireSameOwnedSource(org,source);
  if(!auth.canUseDatasetBound(a,dataset,version,index,required(p),"VIEW_BUILD",binding(p)))
   fail(HttpStatus.NOT_FOUND,"CONTROLLED_VIEW_ORIGINAL_COMPLETE_SOURCE_AUTHORITY_REQUIRED");
  if(!exists("""
   SELECT EXISTS(SELECT 1 FROM mm_dataset d JOIN org o ON o.org_id=d.org_id
    JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
    JOIN artifact_commit c ON c.repo_id=d.raw_repo_id AND c.commit_id=v.raw_commit_id AND c.commit_status='PUBLISHED'
    WHERE d.dataset_id=? AND v.version_id=? AND d.org_id=? AND d.status='ACTIVE' AND o.org_status=1
     AND d.raw_repo_id=? AND v.raw_commit_id=? AND c.manifest_object_key=? AND c.manifest_sha256=? AND o.permission_version=?
     AND d.space_id=? AND EXISTS(SELECT 1 FROM mm_space sp WHERE sp.space_id=d.space_id AND sp.org_id=d.org_id AND sp.status='ACTIVE')
     AND (? OR NOT EXISTS(SELECT 1 FROM ds_controlled_view WHERE dataset_id=d.dataset_id)))
   """,dataset,version,org,uuid(source.path("rawRepoId").asText()),uuid(source.path("rawCommitId").asText()),
   source.path("manifestObjectKey").asText(),source.path("manifestSha256").asText(),source.path("ownerOrgVersion").asLong(),uuid(p.path("destination").path("spaceId").asText()),source.has("controlledParent")))
   fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_REVIEWED_SOURCE_SNAPSHOT_CHANGED");
  if(!exists("""
   SELECT EXISTS(SELECT 1 FROM org o JOIN ds_enterprise_identity e ON e.org_id=o.org_id AND e.state='CONFIRMED'
    JOIN ds_enterprise_catalog_revision c ON c.catalog_revision_id=e.catalog_revision_id
    JOIN ds_enterprise_catalog_entry x ON x.catalog_revision_id=c.catalog_revision_id AND x.entity_id=e.entity_id
    WHERE o.org_id=? AND o.org_status=1 AND o.permission_version=? AND e.entity_id=? AND e.version=? AND e.mapping_id=? AND e.catalog_revision_id=?
     AND c.source_dataset_id=? AND c.source_version_id=? AND e.source_dataset_id=c.source_dataset_id AND e.source_version_id=c.source_version_id AND e.source_sha256=c.source_sha256)
   """,uuid(target.path("orgId").asText()),target.path("orgVersion").asLong(),target.path("enterpriseId").asText(),target.path("mappingVersion").asLong(),
    uuid(target.path("mappingId").asText()),uuid(target.path("catalogRevisionId").asText()),lineage.rootDataset(source),lineage.rootVersion(source)))
   fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_REVIEWED_TARGET_MAPPING_CHANGED");
  JsonNode program=p.path("indexProgram");
  if(!image.equals(program.path("image").asText())||!imageSha.equals(program.path("imageSha256").asText())||
    !nativeScripts.scriptSha256().equals(program.path("commandSha256").asText())||
    !nativeScripts.moduleManifestSha256().equals(program.path("nativeModuleManifestSha256").asText())||!nativeScripts.capsuleSha256().equals(program.path("nativeCapsuleSha256").asText()))fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_REVIEWED_INDEX_PROGRAM_CHANGED");
  if(!exists("""
   SELECT EXISTS(SELECT 1 FROM ds_controlled_view_preview_attempt a JOIN ds_controlled_view_preview_state s ON s.attempt_id=a.attempt_id
    JOIN ds_controlled_view_preview_receipt r ON r.attempt_id=a.attempt_id JOIN ds_controlled_view_preview_origin b ON b.attempt_id=a.attempt_id
    JOIN job_run j ON j.run_id=b.run_id AND j.job_id=b.job_id
    JOIN artifact_commit c ON c.repo_id=a.output_repo_id AND c.commit_id=a.output_commit_id
    WHERE a.attempt_id=? AND a.actor_user_id=? AND a.parent_session_id=? AND a.actor_token_version=?
     AND s.state='AWAITING_REVIEW' AND j.run_status='SUCCEEDED' AND c.commit_status='DRAFT'
     AND s.verified_manifest_object_key=c.manifest_object_key AND s.verified_manifest_sha256=c.manifest_sha256
     AND c.manifest_sha256=? AND r.sha256=? AND r.candidate_receipt_sha256=? AND a.proposal_sha256=?)
   """,uuid(p.path("attemptId").asText()),user,sid,a.tokenVersion(),p.path("previewManifestSha256").asText(),
    p.path("privateReceiptSha256").asText(),p.path("candidateReceiptSha256").asText(),p.path("proposalSha256").asText()))
   fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_VERIFIED_PREVIEW_OR_PROOF_CHANGED");
  Instant end=p.path("validTo").isNull()?null:Instant.parse(p.path("validTo").asText());
  lineage.requirePeriod(source,Instant.parse(p.path("providerValidFrom").asText()),Instant.parse(p.path("validFrom").asText()),end);
  if(!exists("""
   SELECT EXISTS(SELECT 1 FROM ds_resource_grant WHERE grant_id=? AND version=? AND valid_from<=?
    AND valid_from<=? AND (valid_to IS NULL OR (?::timestamptz IS NOT NULL AND ?>=valid_from AND ?<=valid_to)))
   """,binding(p).grantId(),binding(p).version(),Timestamp.from(Instant.parse(p.path("providerValidFrom").asText())),
    Timestamp.from(Instant.parse(p.path("validFrom").asText())),end==null?null:Timestamp.from(end),end==null?null:Timestamp.from(end),end==null?null:Timestamp.from(end)))
   fail(HttpStatus.NOT_FOUND,"CONTROLLED_VIEW_SHARING_PERIOD_OUTSIDE_SOURCE_AUTHORITY");
  if(end!=null&&!end.isAfter(Instant.now()))fail(HttpStatus.NOT_FOUND,"CONTROLLED_VIEW_REVIEWED_SHARING_PERIOD_EXPIRED");
  return a;
 }
 public void attachParent(JsonNode plan){lineage.attach(plan,required(plan));}
 public Map<String,Object> current(UUID id){
  var r=origin(id);JsonNode p=plan(r);
  if(!Set.of("RAW_PUBLISHED","INDEXING","READY").contains(r.get("publication_state")))fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_PUBLICATION_ALREADY_TERMINAL");
  current(p);
  if(!exists("""
   SELECT EXISTS(SELECT 1 FROM mm_dataset d JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
    JOIN artifact_commit c ON c.repo_id=d.raw_repo_id AND c.commit_id=v.raw_commit_id
    WHERE d.dataset_id=? AND v.version_id=? AND d.org_id=? AND d.raw_repo_id=? AND v.raw_commit_id=? AND d.index_repo_id=?
     AND c.commit_status='PUBLISHED' AND c.manifest_object_key=? AND c.manifest_sha256=?)
   """,r.get("dataset_id"),r.get("version_id"),r.get("actor_org_id"),r.get("raw_repo_id"),r.get("raw_commit_id"),r.get("index_repo_id"),
    r.get("raw_manifest_object_key"),r.get("raw_manifest_sha256")))fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_DERIVED_SNAPSHOT_CHANGED");
  return r;
 }
 public Map<String,Object> readyResource(UUID dataset){
  var rows=db.queryForList("""
   SELECT p.version_id,b.index_version_id,r.actor_org_id FROM ds_controlled_view_publication p
   JOIN ds_controlled_view_publication_state s ON s.publication_id=p.publication_id AND s.state='READY'
   JOIN ds_controlled_view_publication_review r ON r.review_id=p.review_id
   JOIN ds_controlled_view_publication_index b ON b.publication_id=p.publication_id WHERE p.dataset_id=?
   """,dataset);return rows.size()==1?rows.get(0):null;
 }
 public boolean managedDataset(UUID dataset){return exists("SELECT EXISTS(SELECT 1 FROM ds_controlled_view_publication WHERE dataset_id=?)",dataset);}
 public boolean managedRepo(UUID repo){return exists("SELECT EXISTS(SELECT 1 FROM ds_controlled_view_publication WHERE raw_repo_id=? OR index_repo_id=?)",repo,repo);}
 public UUID forDataset(UUID dataset){
  var x=db.queryForList("SELECT publication_id FROM ds_controlled_view_publication WHERE dataset_id=?",UUID.class,dataset);
  if(x.size()!=1)fail(HttpStatus.NOT_FOUND,"CONTROLLED_VIEW_PUBLICATION_NOT_AVAILABLE");return x.get(0);
 }
 public final class BuildScope implements AutoCloseable{
  private final UUID id;private BuildScope(UUID id){this.id=id;}
  public void close(){if(!id.equals(building.get()))throw new IllegalStateException("CONTROLLED_VIEW_BUILD_SCOPE_ORDER");building.remove();}
 }
 public BuildScope enter(UUID id){
  if(building.get()!=null)fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_NESTED_BUILD_DENIED");var r=current(id);JsonNode p=plan(r);
  var actor=actor();Claims c=interactive();
  if(!actor.userId().equals(r.get("actor_user_id"))||!actor.orgId().equals(r.get("actor_org_id"))||
   !uuid(c.get("sid",String.class)).equals(r.get("parent_session_id"))||!"RAW_PUBLISHED".equals(r.get("publication_state")))
   fail(HttpStatus.NOT_FOUND,"CONTROLLED_VIEW_ORIGINAL_BUILD_SESSION_REQUIRED");
  building.set(id);return new BuildScope(id);
 }
 public boolean trustedDataset(UUID dataset,UUID version,UUID index){
  UUID id=building.get();if(id==null)return false;var r=current(id);
  if(!dataset.equals(r.get("dataset_id"))||version!=null&&!version.equals(r.get("version_id"))||index!=null)
   fail(HttpStatus.NOT_FOUND,"CONTROLLED_VIEW_TRUSTED_BUILD_OUTSIDE_SCOPE");return true;
 }
 public void requireWrite(UUID dataset){
  if(!trustedDataset(dataset,null,null))fail(HttpStatus.NOT_FOUND,"CONTROLLED_VIEW_MANAGED_PUBLICATION_REQUIRED");
 }
 public boolean trustedRepository(UUID repo,UUID commit,boolean write){
  UUID id=building.get();if(id==null)return false;var r=current(id);
  boolean raw=repo.equals(r.get("raw_repo_id"))&&(commit==null||Objects.equals(commit,r.get("raw_commit_id")));
  boolean index=repo.equals(r.get("index_repo_id"))&&(commit==null||exists("SELECT EXISTS(SELECT 1 FROM artifact_commit WHERE repo_id=? AND commit_id=? AND commit_status='DRAFT')",repo,commit));
  if(write?!index:!(raw||index))fail(HttpStatus.NOT_FOUND,"CONTROLLED_VIEW_TRUSTED_REPOSITORY_OUTSIDE_SCOPE");return true;
 }
 public void fixedProgram(UUID dataset,String actualImage,String actualCommand,Map<String,String> env){
  requireWrite(dataset);var r=current(forDataset(dataset));JsonNode program=plan(r).path("indexProgram");
  if(!program.path("image").asText().equals(actualImage)||!program.path("commandSha256").asText().equals(JobExecutionDefinitionService.sha(actualCommand)))
   fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_FIXED_INDEX_PROGRAM_REQUIRED");
  env.put("MDS_REQUIRED_IMAGE_SHA256",program.path("imageSha256").asText());
  env.put("MDS_NATIVE_INDEX_CALLBACK_KIND",program.path("callbackProgram").path("source").asText());
  env.put("MDS_NATIVE_INDEX_CALLBACK_SHA256",program.path("callbackProgram").path("sha256").asText());
 }
 public AuthorizationService.GrantBinding prepareIndex(IndexJobAuthority.Coordinates c,Map<String,String> scope,Claims parent){
  UUID id=forDataset(c.dataset());var r=current(id);JsonNode p=plan(r);
  if(!id.equals(building.get())||!trustedDataset(c.dataset(),c.version(),null))fail(HttpStatus.FORBIDDEN,"CONTROLLED_VIEW_FIXED_LAUNCHER_REQUIRED");
  if(!parent.get("sid",String.class).equals(r.get("parent_session_id").toString())||!scope.get("inputRepoId").equals(r.get("raw_repo_id").toString())||
   !scope.get("inputCommitId").equals(r.get("raw_commit_id").toString())||!scope.get("outputRepoId").equals(r.get("index_repo_id").toString())||
   scope.containsKey("previousIndexCommitId"))fail(HttpStatus.FORBIDDEN,"CONTROLLED_VIEW_INDEX_COORDINATES_CHANGED");
  UUID job=uuid(scope.get("jobId")),run=uuid(scope.get("runId")),commit=uuid(scope.get("outputCommitId"));verifyProgram(p,job,c);
  db.update("INSERT INTO ds_controlled_view_publication_index(publication_id,index_version_id,index_commit_id,job_id,run_id) VALUES(?,?,?,?,?)",id,c.index(),commit,job,run);
  db.update("UPDATE ds_controlled_view SET index_version_id=? WHERE view_id=? AND state='BUILDING'",c.index(),r.get("view_id"));
  if(db.update("UPDATE ds_controlled_view_publication_state SET state='INDEXING',version=version+1,reason_code='APPROVED_NATIVE_INDEX_LAUNCHED',updated_at=clock_timestamp() WHERE publication_id=? AND state='RAW_PUBLISHED'",id)!=1)
   fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_INDEX_ALREADY_STARTED");
  db.update("INSERT INTO ds_controlled_view_publication_event(event_id,publication_id,review_id,actor_user_id,event_kind,metadata) VALUES(?,?,?,?,'INDEXING',jsonb_build_object('indexVersionId',?::text,'jobId',?::text,'runId',?::text))",
   UUID.randomUUID(),id,r.get("review_id"),r.get("actor_user_id"),c.index(),job,run);
  return binding(p);
 }
 private void verifyProgram(JsonNode p,UUID job,IndexJobAuthority.Coordinates c){
  var rows=db.queryForList("SELECT image,cmd,params FROM job WHERE job_id=?",job);if(rows.size()!=1)fail(HttpStatus.FORBIDDEN,"CONTROLLED_VIEW_INDEX_JOB_NOT_AVAILABLE");
  var j=rows.get(0);JsonNode env=parse(j.get("params")).path("env"),program=p.path("indexProgram");
  if(!program.path("image").asText().equals(j.get("image"))||!program.path("commandSha256").asText().equals(JobExecutionDefinitionService.sha(j.get("cmd").toString()))||
   !program.path("imageSha256").asText().equals(env.path("MDS_REQUIRED_IMAGE_SHA256").asText())||
   !"CURRENT_JAR_CAPSULE_V1".equals(program.path("callbackProgram").path("source").asText())||
   !"/tmp/approved-native-index/index_callback.py".equals(program.path("callbackProgram").path("path").asText())||
   !program.path("callbackProgram").path("source").asText().equals(env.path("MDS_NATIVE_INDEX_CALLBACK_KIND").asText())||
   !program.path("callbackProgram").path("sha256").asText().matches("[a-f0-9]{64}")||
   !program.path("callbackProgram").path("sha256").asText().equals(env.path("MDS_NATIVE_INDEX_CALLBACK_SHA256").asText())||
   !c.dataset().toString().equals(env.path("datasetId").asText())||!c.version().toString().equals(env.path("versionId").asText())||
   !c.index().toString().equals(env.path("indexVersionId").asText())||!program.path("indexType").asText().equals(env.path("indexType").asText())||
   !env.path("modelName").asText().equals(program.path("modelName").asText()+"-"+com.fwdrobo.sirius.util.IndexProcessingConfig.fingerprint(json.convertValue(program.path("indexConfig"),Map.class)).substring(0,12))||
   !parse(env.path("INDEX_PROCESSING_CONFIG_JSON").asText()).equals(program.path("indexConfig")))fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_FIXED_INDEX_JOB_CHANGED");
 }
 public AuthorizationService.GrantBinding revalidateIndex(UUID dataset,UUID version,UUID index,UUID job,UUID run,UUID user,UUID session,AuthorizationService.GrantBinding expected){
  var r=current(forDataset(dataset));JsonNode p=plan(r);
  if(!version.equals(r.get("version_id"))||!user.equals(r.get("actor_user_id"))||!session.equals(r.get("parent_session_id"))||!binding(p).equals(expected)||
   !exists("SELECT EXISTS(SELECT 1 FROM ds_controlled_view_publication_index WHERE publication_id=? AND index_version_id=? AND job_id=? AND run_id=?)",r.get("publication_id"),index,job,run))
   fail(HttpStatus.FORBIDDEN,"CONTROLLED_VIEW_INDEX_ORIGIN_CHANGED");
  verifyProgram(p,job,new IndexJobAuthority.Coordinates(dataset,version,index));return expected;
 }
 public boolean isManagedJob(UUID job){return exists("SELECT EXISTS(SELECT 1 FROM ds_controlled_view_publication_index WHERE job_id=?)",job);}
 public boolean canInspect(AuthorizationService.Actor actor,UUID job,UUID run,String operation){
  var ids=db.queryForList("SELECT publication_id FROM ds_controlled_view_publication_index WHERE job_id=? AND (?::uuid IS NULL OR run_id=?)",UUID.class,job,run,run);
  if(ids.size()!=1)return false;
  try{
   var r=origin(ids.get(0));JsonNode p=plan(r);if(!actor.orgId().equals(r.get("actor_org_id")))return false;
   if("READY".equals(r.get("publication_state")))return auth.canUseDataset(actor,uuid(r.get("dataset_id")),uuid(r.get("version_id")),
    db.queryForObject("SELECT index_version_id FROM ds_controlled_view_publication_index WHERE publication_id=?",UUID.class,ids.get(0)),operation,"GENERAL_READ");
   current(ids.get(0));if(!actor.userId().equals(r.get("actor_user_id")))return false;
   var ops=new TreeSet<>(required(p));ops.add(operation);auth.requireOperation(actor,operation);
   JsonNode s=p.path("source");return auth.canUseDatasetBound(actor,uuid(s.path("datasetId").asText()),uuid(s.path("versionId").asText()),
    s.path("indexVersionId").isNull()?null:uuid(s.path("indexVersionId").asText()),ops,"VIEW_BUILD",binding(p));
  }catch(ResponseStatusException denied){if(denied.getStatusCode().value()>=500)throw denied;return false;}
 }
}
