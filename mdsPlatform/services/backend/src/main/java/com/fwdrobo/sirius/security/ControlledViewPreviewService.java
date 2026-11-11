package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fwdrobo.sirius.container.ContainerService;
import com.fwdrobo.sirius.dto.artifact.*;
import com.fwdrobo.sirius.dto.job.JobRequest;
import com.fwdrobo.sirius.service.*;
import com.fwdrobo.sirius.entity.artifact.ArtifactVisibility;
import io.jsonwebtoken.Claims;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;

/** Materialize and verify a private candidate. No approval, publication, index or grant. */
@Service @Slf4j
public class ControlledViewPreviewService {
 private static final long MAX_ENVELOPE=50331648,MAX_CANDIDATE=33554432;
 private static final List<String> CATALOG=List.of("assets.json","field_dictionary.json","entities.json","relations.json","retrieval_manifest.json","sources_and_assumptions.json","view_scope.json");
 private final JdbcTemplate db;private final AuthorizationService auth;private final ControlledViewPreviewAuthority authority;
 private final ControlledViewProposalManagement proposals;private final ControlledViewWorkerScriptFactory scripts;
 private final ArtifactRepoService repositories;private final ArtifactCommitService commits;private final ManifestService manifests;
 private final JobService jobs;private final ContainerService containers;private final MinioService objects;private final ObjectMapper json;
 private final String backend;private final boolean reconcileEnabled;private final TransactionTemplate tx;
 private final Set<UUID> verifying=ConcurrentHashMap.newKeySet();
 private final ThreadPoolExecutor verifier=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),r->{
  Thread t=new Thread(r,"controlled-view-private-verifier");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
 @org.springframework.beans.factory.annotation.Autowired private JobExecutionDefinitionService definitions;
 public ControlledViewPreviewService(JdbcTemplate db,AuthorizationService auth,ControlledViewPreviewAuthority authority,
  ControlledViewProposalManagement proposals,ControlledViewWorkerScriptFactory scripts,ArtifactRepoService repositories,ArtifactCommitService commits,
  ManifestService manifests,JobService jobs,ContainerService containers,MinioService objects,ObjectMapper json,PlatformTransactionManager manager,
  @Value("${app.docker.host}") String backend,
  @Value("${mm.controlled-view.reconcile-enabled:true}") boolean reconcileEnabled){
  this.db=db;this.auth=auth;this.authority=authority;this.proposals=proposals;this.scripts=scripts;this.repositories=repositories;this.commits=commits;
  this.manifests=manifests;this.jobs=jobs;this.containers=containers;this.objects=objects;this.json=json;this.backend=backend;this.reconcileEnabled=reconcileEnabled;this.tx=new TransactionTemplate(manager);
 }
 public record CreateRequest(UUID proposalId,String expectedRuleSha256){}
 private static void fail(HttpStatus status,String code){throw new ResponseStatusException(status,code);}
 private static UUID uuid(Object v){return v==null?null:UUID.fromString(v.toString());}
 private static String sha(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
 private String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException("PREVIEW_METADATA_ENCODING_FAILED");}}
 private JsonNode parse(byte[] bytes){
  try{return json.readTree(bytes);}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"PREVIEW_RECEIPT_JSON_INVALID");}
 }
 private JsonNode parse(Object v){try{return json.readTree(v.toString());}catch(Exception e){throw new IllegalStateException("PREVIEW_METADATA_INVALID");}}
 private void lock(){auth.lockIdentityMutation();db.execute("SELECT pg_advisory_xact_lock(805014630400001::bigint)");}
 private void event(UUID attempt,String kind,Object metadata){db.update("INSERT INTO ds_controlled_view_preview_event(event_id,attempt_id,event_kind,metadata) VALUES(?,?,?,?::jsonb)",UUID.randomUUID(),attempt,kind,encode(metadata));}
 @Transactional(rollbackFor=Exception.class) public Map<String,Object> create(CreateRequest req,String accessToken)throws Exception{
  var actor=authority.interactiveActor();Claims parent=authority.interactive();authority.configured();
  if(req==null||req.proposalId()==null||req.expectedRuleSha256()==null||!req.expectedRuleSha256().matches("[a-f0-9]{64}"))fail(HttpStatus.BAD_REQUEST,"PREVIEW_EXACT_PROPOSAL_AND_RULE_HASH_REQUIRED");
  UUID session=uuid(parent.get("sid",String.class));var original=proposals.execution(req.proposalId(),actor,session);JsonNode plan=original.proposal();
  if(!original.prepared().ruleSha256().equals(req.expectedRuleSha256()))fail(HttpStatus.CONFLICT,"PREVIEW_RULE_PROPOSAL_CHANGED");
  UUID attempt=UUID.randomUUID();var prepared=scripts.preparePreview(uuid(plan.path("source").path("rawRepoId").asText()),plan.path("inventory"),plan.path("rules"),attempt);
  var repoRequest=new ArtifactRepoReq();repoRequest.setRepoName("controlled-view-preview-"+attempt);repoRequest.setRepoType("CONTROLLED_VIEW_PREVIEW");
  repoRequest.setVisibility(ArtifactVisibility.PRIVATE);repoRequest.setDescription("Private materialization candidate awaiting explicit review");
  UUID outputRepo=repositories.create(repoRequest).getRepoId();var commitRequest=new CreateCommitReq();
  commitRequest.setCommitSource("CONTROLLED_VIEW_PREVIEW");commitRequest.setMeta(json.createObjectNode().put("managedControlledViewPreview",true).put("attemptId",attempt.toString()));
  UUID outputCommit;
  try(var fixed=auth.enterOperation(ControlledViewPreviewAuthority.SHARE,"VIEW_BUILD")){outputCommit=commits.createCommit(outputRepo,commitRequest).getCommitId();}
  UUID inputRepo=uuid(plan.path("source").path("rawRepoId").asText()),inputCommit=uuid(plan.path("source").path("rawCommitId").asText());
  var env=new TreeMap<String,Object>();env.put("MDS_INPUT_REPO_ID",inputRepo.toString());env.put("MDS_INPUT_COMMIT_ID",inputCommit.toString());
  env.put("MDS_OUTPUT_REPO_ID",outputRepo.toString());env.put("MDS_OUTPUT_COMMIT_ID",outputCommit.toString());env.put("BACKEND_BASE_URL",backend);env.put("BACKEND_BEARER_TOKEN","");
  env.put("MDS_REQUIRED_IMAGE_SHA256",authority.imageSha());
  Map<String,Object> parameters=Map.of("env",env,"resources",Map.of("cpus","4","memoryMb","6144","memorySwapMb","6144","pidsLimit","512"));
  // The typed immutable origin supplies the exact paths at definition freeze.
  // Generic user jobs retain their existing path limits and preflight.
  var request=new JobRequest("controlled-view-preview-"+attempt,"TRANSFORM",List.of(new JobRequest.Input(inputRepo,List.of())),List.of(outputRepo),authority.image(),prepared.script(),false,parameters);
  UUID job=jobs.createJob(request,null);
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
   @Override public void afterCompletion(int status){
    if(status==STATUS_ROLLED_BACK)try{
     if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM job WHERE job_id=?)",Boolean.class,job)))objects.removeObject("job/"+job+"/command.sh");
    }catch(Exception pending){log.warn("Unreferenced private preview script cleanup pending: job={}",job);}
   }
  });
  // No identity mutex spans the preceding object writes.
  lock();actor=authority.interactiveActor();var current=proposals.execution(req.proposalId(),actor,session);
  if(!ControlledViewPreviewAuthority.hash(plan).equals(ControlledViewPreviewAuthority.hash(current.proposal()))||
   !prepared.scriptSha256().equals(scripts.preparePreview(inputRepo,current.proposal().path("inventory"),current.proposal().path("rules"),attempt).scriptSha256()))
   fail(HttpStatus.CONFLICT,"PREVIEW_AUTHORITY_CHANGED_DURING_PREPARATION");
  db.update("""
   INSERT INTO ds_controlled_view_preview_attempt(attempt_id,proposal_id,owner_org_id,actor_user_id,parent_session_id,actor_token_version,job_id,
    input_repo_id,input_commit_id,output_repo_id,output_commit_id,proposal_sha256,script_sha256,parameters_sha256,image,image_sha256)
   VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
   """,attempt,req.proposalId(),actor.orgId(),actor.userId(),session,actor.tokenVersion(),job,inputRepo,inputCommit,outputRepo,outputCommit,
   ControlledViewPreviewAuthority.hash(plan),prepared.scriptSha256(),ControlledViewPreviewAuthority.hash(json.valueToTree(parameters)),authority.image(),authority.imageSha());
  db.update("INSERT INTO ds_controlled_view_preview_state(attempt_id) VALUES(?)",attempt);
  event(attempt,"REQUESTED",Map.of("proposalId",req.proposalId(),"scriptSha256",prepared.scriptSha256(),"registeredImageSha256",authority.imageSha()));
  var run=containers.runControlledViewPreviewJob(job,accessToken,attempt);
  return Map.of("attemptId",attempt,"proposalId",req.proposalId(),"jobId",job,"runId",run.getRunId(),"state","BUILDING","published",false,"authorizationGranted",false);
 }
 private static void keys(JsonNode n,Set<String> expected){
  var actual=new HashSet<String>();if(n==null||!n.isObject())fail(HttpStatus.BAD_REQUEST,"PREVIEW_RECEIPT_FIELDS_INVALID");n.fieldNames().forEachRemaining(actual::add);
  if(!actual.equals(expected))fail(HttpStatus.BAD_REQUEST,"PREVIEW_RECEIPT_FIELDS_INVALID");
 }
 private static String hashText(JsonNode n){
  if(n==null||!n.isTextual()||!n.asText().matches("[a-f0-9]{64}"))fail(HttpStatus.BAD_REQUEST,"PREVIEW_RECEIPT_SHA256_REQUIRED");return n.asText();
 }
 private static long size(JsonNode n){
  if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.asLong()<0||n.asLong()>671088640L)fail(HttpStatus.BAD_REQUEST,"PREVIEW_RECEIPT_SIZE_INVALID");return n.asLong();
 }
 private static String path(JsonNode n){
  if(n==null||!n.isTextual())fail(HttpStatus.BAD_REQUEST,"PREVIEW_RECEIPT_PATH_INVALID");String p=n.asText();
  if(p.isEmpty()||p.length()>1024||!Normalizer.isNormalized(p,Normalizer.Form.NFC)||p.startsWith("/")||p.contains("\\")||p.contains(":")||p.codePoints().anyMatch(c->c<32)||
   (!p.startsWith("corpus/")&&!p.startsWith("catalog/")))fail(HttpStatus.BAD_REQUEST,"PREVIEW_RECEIPT_PATH_INVALID");
  for(String part:p.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))fail(HttpStatus.BAD_REQUEST,"PREVIEW_RECEIPT_PATH_INVALID");return p;
 }
 private byte[] decoded(JsonNode root,String field,int maximum){
  if(!root.path(field).isTextual()||root.path(field).asText().length()>(long)maximum*4/3+16)fail(HttpStatus.PAYLOAD_TOO_LARGE,"PREVIEW_PRIVATE_RECEIPT_SIZE_LIMIT");
  try{byte[] b=Base64.getDecoder().decode(root.path(field).asText());if(b.length<1||b.length>maximum)fail(HttpStatus.PAYLOAD_TOO_LARGE,"PREVIEW_PRIVATE_RECEIPT_SIZE_LIMIT");return b;}
  catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"PREVIEW_PRIVATE_RECEIPT_ENCODING_INVALID");}
 }
 private record Receipt(JsonNode candidate,JsonNode execution,JsonNode state,JsonNode publicManifest,String candidateSha,boolean copies){}
 private Receipt validate(ControlledViewPreviewAuthority.Attempt a,byte[] envelope){
  if(envelope.length<1||envelope.length>MAX_ENVELOPE)fail(HttpStatus.PAYLOAD_TOO_LARGE,"PREVIEW_PRIVATE_RECEIPT_SIZE_LIMIT");
  JsonNode root=parse(envelope);keys(root,Set.of("schema","attemptId","candidateReceiptBase64","executionReceiptBase64","candidateStateBase64","verifiedImageSha256"));
  if(!"CONTROLLED_VIEW_PREVIEW_RECEIPT_V1".equals(root.path("schema").asText())||!a.id().toString().equals(root.path("attemptId").asText())||
   !a.row().get("image_sha256").toString().trim().equals(root.path("verifiedImageSha256").asText()))fail(HttpStatus.CONFLICT,"PREVIEW_PRIVATE_RECEIPT_ORIGIN_CHANGED");
  byte[] raw=decoded(root,"candidateReceiptBase64",(int)MAX_CANDIDATE);JsonNode candidate=parse(raw),execution=parse(decoded(root,"executionReceiptBase64",1048576)),state=parse(decoded(root,"candidateStateBase64",1048576));
  JsonNode plan=a.proposal(),rules=plan.path("rules");String candidateSha=sha(raw);
  if(!Set.of("COLLABORATION_VIEW_RULES_V1",ControlledViewResponsibilityRules.SCHEMA).contains(rules.path("schema").asText())||!rules.path("schema").equals(candidate.path("schema"))||
   !"CANDIDATE_AWAITING_REVIEW".equals(candidate.path("state").asText())||
   !a.proposalId().toString().equals(candidate.path("viewId").asText())||
   !rules.path("targetOrgId").equals(candidate.path("targetOrgId"))||!rules.path("targetEnterpriseId").equals(candidate.path("targetEnterpriseId"))||
   !ControlledViewPreviewAuthority.hash(rules.path("source")).equals(ControlledViewPreviewAuthority.hash(candidate.path("source")))||
   !a.prepared().ruleSha256().equals(candidate.path("ruleSha256").asText())||
   !ControlledViewPreviewAuthority.hash(json.valueToTree(a.prepared().moduleSha256())).equals(ControlledViewPreviewAuthority.hash(candidate.path("moduleSha256"))))
   fail(HttpStatus.CONFLICT,"PREVIEW_CANDIDATE_RECEIPT_BINDING_CHANGED");
  if(!"COLLABORATION_JOB_CAPSULE_V1".equals(execution.path("schema").asText())||!"CANDIDATE_AWAITING_REVIEW".equals(execution.path("state").asText())||
   !candidateSha.equals(execution.path("candidateReceiptSha256").asText())||!a.prepared().inventorySha256().equals(execution.path("inputInventorySha256").asText())||
   !a.prepared().ruleSha256().equals(execution.path("ruleSha256").asText())||!candidate.path("moduleSha256").equals(execution.path("moduleSha256"))||
   !execution.path("authorizationGranted").isBoolean()||execution.path("authorizationGranted").booleanValue()||
   !execution.path("published").isBoolean()||execution.path("published").booleanValue())
   fail(HttpStatus.CONFLICT,"PREVIEW_EXECUTION_RECEIPT_BINDING_CHANGED");
  hashText(execution.get("bootstrapInventorySha256"));
  if(!"CANDIDATE_AWAITING_REVIEW".equals(state.path("state").asText())||!a.proposalId().toString().equals(state.path("viewId").asText())||
   !candidateSha.equals(state.path("candidateReceiptSha256").asText())||!a.prepared().ruleSha256().equals(state.path("ruleSha256").asText())||
   !state.path("authorizationGranted").isBoolean()||state.path("authorizationGranted").booleanValue()||
   !state.path("published").isBoolean()||state.path("published").booleanValue())fail(HttpStatus.CONFLICT,"PREVIEW_CANDIDATE_STATE_CHANGED");
  var expected=new TreeMap<String,JsonNode>();
  if(ControlledViewResponsibilityRules.is(rules))expected.putAll(ControlledViewResponsibilityRules.expected(plan,candidate));
  else for(JsonNode rule:rules.path("files"))expected.put(rule.path("outputPath").asText(),rule);
  var input=new TreeMap<String,JsonNode>();for(JsonNode item:plan.path("inventory").path("files"))input.put(item.path("logical_path").asText().replaceFirst("^/",""),item);
  var paths=new HashSet<>(expected.keySet());for(String name:CATALOG)paths.add("catalog/"+name);
  JsonNode files=candidate.path("files");if(!files.isArray()||files.size()!=paths.size())fail(HttpStatus.CONFLICT,"PREVIEW_PUBLIC_INVENTORY_INCOMPLETE");
  var observed=new HashSet<String>();long total=0;for(JsonNode f:files){
   keys(f,Set.of("path","sha256","bytes"));String name=path(f.get("path"));if(!paths.contains(name)||!observed.add(name))fail(HttpStatus.CONFLICT,"PREVIEW_PUBLIC_INVENTORY_CHANGED");
   String digest=hashText(f.get("sha256"));long bytes=size(f.get("bytes"));total+=bytes;
   if(total>671088640L)fail(HttpStatus.PAYLOAD_TOO_LARGE,"PREVIEW_PUBLIC_BYTE_LIMIT");
   JsonNode rule=expected.get(name);
   if(rule!=null&&"COMPLETE_FILE".equals(rule.path("kind").asText())){
    JsonNode original=input.get(rule.path("sourcePath").asText());
    if(original==null||!digest.equals(original.path("sha256").asText())||bytes!=original.path("size_bytes").asLong())fail(HttpStatus.CONFLICT,"PREVIEW_COMPLETE_FILE_BYTE_IDENTITY_CHANGED");
   }
  }
  if(execution.path("publicFileCount").asInt(-1)!=files.size()||state.path("fileCount").asInt(-1)!=expected.size())fail(HttpStatus.CONFLICT,"PREVIEW_RECEIPT_COUNTS_CHANGED");
  JsonNode provenance=candidate.path("provenance");if(!provenance.isArray()||provenance.size()!=expected.size())fail(HttpStatus.CONFLICT,"PREVIEW_PRIVATE_PROVENANCE_INCOMPLETE");
  var located=new HashSet<String>();for(JsonNode f:provenance){
   String out=f.path("path").asText();JsonNode rule=expected.get(out);
   if(rule==null||!located.add(out)||!rule.path("sourcePath").equals(f.path("sourcePath"))||!rule.path("sourceSha256").equals(f.path("sourceSha256"))||!rule.path("kind").equals(f.path("kind"))||
    !input.get(rule.path("sourcePath").asText()).path("file_id").asText().equals(f.path("sourceFileId").asText())||!f.path("locations").isArray())
    fail(HttpStatus.CONFLICT,"PREVIEW_PRIVATE_PROVENANCE_CHANGED");
  }
  boolean copies=false;for(JsonNode rule:rules.path("files"))copies|="COMPLETE_FILE".equals(rule.path("kind").asText());
  if(!candidate.path("hasOriginalByteCopies").isBoolean()||candidate.path("hasOriginalByteCopies").booleanValue()!=copies||
   !state.path("hasOriginalByteCopies").isBoolean()||state.path("hasOriginalByteCopies").booleanValue()!=copies)fail(HttpStatus.CONFLICT,"PREVIEW_ORIGINAL_COPY_FLAG_CHANGED");
  return new Receipt(candidate,execution,state,files.deepCopy(),candidateSha,copies);
 }
 public void receipt(UUID attempt,byte[] envelope)throws Exception{
  var a=authority.worker(attempt);Receipt value=validate(a,envelope);String hash=sha(envelope);
  var prior=db.queryForList("SELECT sha256,run_id FROM ds_controlled_view_preview_receipt WHERE attempt_id=?",attempt);
  if(!prior.isEmpty()){
   if(!hash.equals(prior.get(0).get("sha256").toString().trim())||!authority.run(attempt).equals(prior.get(0).get("run_id")))fail(HttpStatus.CONFLICT,"PREVIEW_RECEIPT_ALREADY_FROZEN");return;
  }
  UUID receipt=UUID.randomUUID();String key="controlled-view-private/"+attempt+"/receipts/"+receipt+".json";
  objects.putObject(key,new ByteArrayInputStream(envelope),envelope.length,"application/json");
  try(var input=objects.getObject(key)){if(!Arrays.equals(envelope,input.readNBytes(envelope.length+1)))fail(HttpStatus.SERVICE_UNAVAILABLE,"PREVIEW_PRIVATE_RECEIPT_OBJECT_CHANGED");}
  // Object writes precede the short identity/run fence. No receipt is public.
  tx.executeWithoutResult(status->{
   lock();var now=authority.worker(attempt);if(!now.prepared().scriptSha256().equals(a.prepared().scriptSha256()))fail(HttpStatus.CONFLICT,"PREVIEW_CALLBACK_AUTHORITY_CHANGED");
   var raced=db.queryForList("SELECT sha256 FROM ds_controlled_view_preview_receipt WHERE attempt_id=?",attempt);
   if(!raced.isEmpty()){if(!hash.equals(raced.get(0).get("sha256").toString().trim()))fail(HttpStatus.CONFLICT,"PREVIEW_RECEIPT_ALREADY_FROZEN");return;}
   db.update("INSERT INTO ds_controlled_view_preview_receipt(receipt_id,attempt_id,run_id,object_key,sha256,size_bytes,candidate_receipt_sha256,public_manifest,has_original_byte_copies) VALUES(?,?,?,?,?,?,?,?::jsonb,?)",
    receipt,attempt,authority.run(attempt),key,hash,envelope.length,value.candidateSha(),encode(value.publicManifest()),value.copies());
   event(attempt,"RECEIPT",Map.of("receiptSha256",hash,"candidateReceiptSha256",value.candidateSha(),"publicFileCount",value.publicManifest().size(),"hasOriginalByteCopies",value.copies()));
  });
 }
 public java.util.List<Map<String,Object>> list(UUID proposal,Integer maximum){
  var actor=authority.interactiveActor();Claims c=authority.interactive();int limit=maximum==null?50:maximum;
  if(limit<1||limit>100)fail(HttpStatus.BAD_REQUEST,"PREVIEW_LIST_LIMIT_INVALID");
  var visible=new java.util.ArrayList<Map<String,Object>>();
  for(UUID id:db.queryForList("""
   SELECT a.attempt_id FROM ds_controlled_view_preview_attempt a
   WHERE a.owner_org_id=? AND a.actor_user_id=? AND a.parent_session_id=? AND a.actor_token_version=?
    AND (?::uuid IS NULL OR a.proposal_id=?) ORDER BY a.created_at DESC,a.attempt_id LIMIT 1000
   """,UUID.class,actor.orgId(),actor.userId(),uuid(c.get("sid",String.class)),actor.tokenVersion(),proposal,proposal)){
   try{visible.add(get(id));if(visible.size()>=limit)break;}
   catch(ResponseStatusException denied){if(denied.getStatusCode().value()>=500)throw denied;}
  }
  return visible;
 }
 public Map<String,Object> get(UUID attempt){
  var a=authority.forHuman(attempt);var state=db.queryForMap("SELECT * FROM ds_controlled_view_preview_state WHERE attempt_id=?",attempt);
  var out=new LinkedHashMap<String,Object>();out.put("attemptId",attempt);out.put("proposalId",a.proposalId());out.put("jobId",a.job());out.put("runId",authority.run(attempt));
  out.put("state",state.get("state"));out.put("version",state.get("version"));out.put("reasonCode",state.get("reason_code"));
  out.put("jobStatus",db.queryForObject("SELECT run_status::text FROM job_run WHERE run_id=?",String.class,authority.run(attempt)));
  out.put("published",false);out.put("authorizationGranted",false);out.put("approved",false);
  var publication=db.queryForList("SELECT p.publication_id,s.state FROM ds_controlled_view_publication p JOIN ds_controlled_view_publication_state s ON s.publication_id=p.publication_id WHERE p.attempt_id=?",attempt);
  if(!publication.isEmpty()){out.put("publicationId",publication.get(0).get("publication_id"));out.put("publicationState",publication.get(0).get("state"));}

  if("AWAITING_REVIEW".equals(state.get("state"))){
   authority.requireVerifiedRepository(a,a.outputRepo(),a.outputCommit());
   JsonNode summary=parse(state.get("verification_summary"));
   for(JsonNode file:summary.path("publicFiles")){
    String operation=authority.readOperation(a,file.path("path").asText());
    if(operation==null)((ObjectNode)file).putNull("readOperation");else ((ObjectNode)file).put("readOperation",operation);
    ((ObjectNode)file).put("originalByteCopy",authority.originalByteCopy(a,file.path("path").asText()));
   }
   out.put("outputRepoId",a.outputRepo());out.put("outputCommitId",a.outputCommit());out.put("preview",summary);
  }
  return out;
 }
 public Map<String,Object> reconcile(UUID attempt)throws Exception{
  authority.forHuman(attempt);try{verify(attempt);}catch(ResponseStatusException rejected){
   if(rejected.getStatusCode().value()<500)settleFailure(attempt,"FAILED","PREVIEW_VERIFICATION_OR_AUTHORITY_REJECTED");throw rejected;
  }return get(attempt);
 }
 private byte[] objectBytes(String key,long maximum)throws Exception{
  try(var stream=objects.getObject(key)){byte[] b=stream.readNBytes((int)maximum+1);if(b.length>maximum)fail(HttpStatus.PAYLOAD_TOO_LARGE,"PREVIEW_PRIVATE_RECEIPT_SIZE_LIMIT");return b;}
  catch(io.minio.errors.ErrorResponseException e){missingObject(e);throw e;}
 }
 private static void missingObject(io.minio.errors.ErrorResponseException e){
  if(e.errorResponse()!=null&&Set.of("NoSuchKey","NoSuchObject","NotFound").contains(e.errorResponse().code()))fail(HttpStatus.CONFLICT,"PREVIEW_STORED_OBJECT_MISSING");
 }
 private long objectSize(String key)throws Exception{
  try{return objects.statObject(key).size();}catch(io.minio.errors.ErrorResponseException e){missingObject(e);throw e;}
 }
 private static String normalize(String p){return p.startsWith("/")?p.substring(1):p;}
 private String objectSha(String key,ControlledViewPreviewAuthority.Attempt a)throws Exception{
  MessageDigest hash=MessageDigest.getInstance("SHA-256");long count=0;
  try(var stream=objects.getObject(key)){
   byte[] buffer=new byte[4*1024*1024];int n;while((n=stream.read(buffer))!=-1){
    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Preview verifier stopping");
    hash.update(buffer,0,n);count+=n;if(count>671088640L)fail(HttpStatus.PAYLOAD_TOO_LARGE,"PREVIEW_OUTPUT_OBJECT_SIZE_LIMIT");
    authority.forRecorded(a.id(),definitions);
   }
  }catch(io.minio.errors.ErrorResponseException e){missingObject(e);throw e;}
  return HexFormat.of().formatHex(hash.digest());
 }
 private void verify(UUID attempt)throws Exception{
  var state=db.queryForMap("SELECT state FROM ds_controlled_view_preview_state WHERE attempt_id=?",attempt);if(!"BUILDING".equals(state.get("state")))return;
  UUID run=authority.run(attempt);String status=db.queryForObject("SELECT run_status::text FROM job_run WHERE run_id=?",String.class,run);
  if(Set.of("FAILED","CANCELED").contains(status)){settleFailure(attempt,status.equals("CANCELED")?"CANCELED":"FAILED","PREVIEW_TASK_"+status);return;}
  if(!"SUCCEEDED".equals(status))return;
  var a=authority.forRecorded(attempt,definitions);
  var receipts=db.queryForList("SELECT * FROM ds_controlled_view_preview_receipt WHERE attempt_id=? AND run_id=?",attempt,run);
  if(receipts.size()!=1)fail(HttpStatus.CONFLICT,"PREVIEW_SUCCESS_WITHOUT_PRIVATE_RECEIPT");var receipt=receipts.get(0);
  byte[] envelope=objectBytes(receipt.get("object_key").toString(),MAX_ENVELOPE);
  if(envelope.length!=((Number)receipt.get("size_bytes")).longValue()||!sha(envelope).equals(receipt.get("sha256").toString().trim()))fail(HttpStatus.CONFLICT,"PREVIEW_PRIVATE_RECEIPT_OBJECT_CHANGED");
  Receipt verified=validate(a,envelope);
  if(!ControlledViewPreviewAuthority.hash(verified.publicManifest()).equals(ControlledViewPreviewAuthority.hash(parse(receipt.get("public_manifest")))))fail(HttpStatus.CONFLICT,"PREVIEW_PUBLIC_MANIFEST_RECEIPT_CHANGED");
  var manifest=manifests.getManifest(a.outputRepo(),a.outputCommit());var actual=new TreeMap<String,UUID>();
  for(var f:manifest.getFiles())if(actual.put(normalize(f.getPath()),f.getFileId())!=null)fail(HttpStatus.CONFLICT,"PREVIEW_OUTPUT_PATH_COLLISION");
  if(actual.size()!=verified.publicManifest().size())fail(HttpStatus.CONFLICT,"PREVIEW_ACTUAL_OUTPUT_SET_CHANGED");
  for(JsonNode file:verified.publicManifest()){
   String path=file.path("path").asText();UUID id=actual.remove(path);if(id==null)fail(HttpStatus.CONFLICT,"PREVIEW_ACTUAL_OUTPUT_MISSING");
   var rows=db.queryForList("SELECT size_bytes,btrim(sha256) AS sha,file_key,upload_state FROM artifact_file WHERE file_id=?",id);
   if(rows.size()!=1)fail(HttpStatus.CONFLICT,"PREVIEW_OUTPUT_METADATA_NOT_AVAILABLE");var f=rows.get(0);
   if(!"READY".equals(f.get("upload_state"))||!(f.get("size_bytes") instanceof Number)||((Number)f.get("size_bytes")).longValue()!=file.path("bytes").asLong()||
    !file.path("sha256").asText().equals(f.get("sha"))||objectSize(f.get("file_key").toString())!=file.path("bytes").asLong()||
    !file.path("sha256").asText().equals(objectSha(f.get("file_key").toString(),a)))fail(HttpStatus.CONFLICT,"PREVIEW_ACTUAL_OUTPUT_BYTES_CHANGED");
  }
  if(!actual.isEmpty())fail(HttpStatus.CONFLICT,"PREVIEW_UNDECLARED_OUTPUT_OBJECT");
  var pointer=db.queryForMap("SELECT manifest_object_key,btrim(manifest_sha256) AS sha FROM artifact_commit WHERE repo_id=? AND commit_id=? AND commit_status='DRAFT'",a.outputRepo(),a.outputCommit());
  var summary=new LinkedHashMap<String,Object>();summary.put("ruleSha256",a.prepared().ruleSha256());summary.put("inputInventorySha256",a.prepared().inventorySha256());
  summary.put("scriptSha256",a.prepared().scriptSha256());summary.put("privateReceiptSha256",receipt.get("sha256").toString().trim());summary.put("candidateReceiptSha256",verified.candidateSha());
  summary.put("hasOriginalByteCopies",verified.copies());summary.put("publicFiles",verified.publicManifest());summary.put("actualStoredBytesVerified",true);
  summary.put("registeredImageSha256",a.row().get("image_sha256").toString().trim());summary.put("published",false);summary.put("authorizationGranted",false);summary.put("approved",false);
  var selections=json.createArrayNode();for(JsonNode provenance:verified.candidate().path("provenance")){
   var item=selections.addObject();for(String field:List.of("path","sourcePath","kind","sourceRecordCount","selectedRecordCount"))item.set(field,provenance.path(field));
  }
  summary.put("actualSelections",selections);
  tx.executeWithoutResult(transaction->{
   lock();authority.forRecorded(attempt,definitions);
   if(!"SUCCEEDED".equals(db.queryForObject("SELECT run_status::text FROM job_run WHERE run_id=?",String.class,run)))fail(HttpStatus.CONFLICT,"PREVIEW_TASK_SETTLEMENT_CHANGED");
   var now=db.queryForMap("SELECT manifest_object_key,btrim(manifest_sha256) AS sha FROM artifact_commit WHERE repo_id=? AND commit_id=? AND commit_status='DRAFT'",a.outputRepo(),a.outputCommit());
   if(!now.equals(pointer))fail(HttpStatus.CONFLICT,"PREVIEW_MANIFEST_CHANGED_DURING_VERIFICATION");
   int changed=db.update("UPDATE ds_controlled_view_preview_state SET state='AWAITING_REVIEW',version=version+1,reason_code='PREVIEW_ACTUAL_OUTPUT_VERIFIED',verified_manifest_object_key=?,verified_manifest_sha256=?,verification_summary=?::jsonb,updated_at=clock_timestamp() WHERE attempt_id=? AND state='BUILDING'",
    pointer.get("manifest_object_key"),pointer.get("sha"),encode(summary),attempt);
   if(changed==1)event(attempt,"VERIFIED",Map.of("manifestSha256",pointer.get("sha"),"privateReceiptSha256",receipt.get("sha256").toString().trim(),"publicFileCount",verified.publicManifest().size(),"actualBytesVerified",true));
  });
 }
 private void settleFailure(UUID attempt,String state,String reason){
  tx.executeWithoutResult(transaction->{
   lock();int changed=db.update("UPDATE ds_controlled_view_preview_state SET state=?,version=version+1,reason_code=?,updated_at=clock_timestamp() WHERE attempt_id=? AND state='BUILDING'",state,reason,attempt);
   if(changed==1)event(attempt,state,Map.of("reasonCode",reason,"published",false,"authorizationGranted",false));
  });
 }
 @Scheduled(fixedDelay=1000) public void reconcilePending(){
  if(!reconcileEnabled)return;
  for(UUID attempt:db.queryForList("""
   SELECT a.attempt_id FROM ds_controlled_view_preview_attempt a JOIN ds_controlled_view_preview_state s ON s.attempt_id=a.attempt_id AND s.state='BUILDING'
   JOIN ds_controlled_view_preview_origin o ON o.attempt_id=a.attempt_id JOIN job_run r ON r.run_id=o.run_id
   WHERE r.run_status IN('SUCCEEDED','FAILED','CANCELED') ORDER BY a.created_at,a.attempt_id LIMIT 8
   """,UUID.class)){
   if(!verifying.add(attempt))continue;
   try{verifier.execute(()->{
    try{verify(attempt);}
    catch(ResponseStatusException denied){
     if(denied.getStatusCode().value()<500)settleFailure(attempt,"FAILED","PREVIEW_VERIFICATION_OR_AUTHORITY_REJECTED");
     else log.warn("Private preview verification dependency unavailable: attempt={}",attempt);
    }catch(Exception unavailable){log.warn("Private preview verification deferred: attempt={} type={}",attempt,unavailable.getClass().getSimpleName());}
    finally{verifying.remove(attempt);}
   });}catch(RejectedExecutionException busy){verifying.remove(attempt);}
  }
 }
 @PreDestroy public void stopVerifier(){verifier.shutdownNow();}
}
