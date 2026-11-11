package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.fwdrobo.sirius.dto.job.JobRequest;
import com.fwdrobo.sirius.util.PathUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Immutable human execution identity; worker reads never select mutable per-job objects. */
@Service
public class JobExecutionDefinitionService {
 private static final ObjectMapper JSON=new ObjectMapper();
 private static final Set<String> HASH_KEYS=Set.of("executionDefinitionSha256","entrypointSha256","bootstrapSha256","definitionExpiresAt","ioManifestSha256");
 private final JdbcTemplate jdbc;private final IdentitySessionService sessions;private final AuthorizationService auth;
 @org.springframework.beans.factory.annotation.Autowired private ControlledViewPreviewAuthority previews;
 @org.springframework.beans.factory.annotation.Autowired private GenericFileOriginService origins;
 private final String templatePath;
 public JobExecutionDefinitionService(JdbcTemplate jdbc,IdentitySessionService sessions,AuthorizationService auth,
   @Value("${app.job-script-path:/app/script/command.sh}") String templatePath){
  this.jdbc=jdbc;this.sessions=sessions;this.auth=auth;this.templatePath=templatePath;
 }
 public record Definition(JsonNode body,String hash,String scriptSha,String bootstrapSha){
  public String script(){return body.path("script").asText();}
  public String bootstrap(){return body.path("bootstrap").asText();}
  public String ioManifestSha(){return body.path("ioManifestSha256").asText("");}
  public String image(){return body.path("image").asText();}
 }
 public record WorkerOperation(String code,String purpose){}
 private static void fail(HttpStatus status,String code){throw new ResponseStatusException(status,code);}
 private boolean exists(String sql,Object... args){return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args));}
 private static UUID id(Map<?,?> scope,String key){return UUID.fromString((String)scope.get(key));}
 private static JsonNode tree(Object value){return JSON.valueToTree(value);}
 private static JsonNode parse(Object text){
  try{return JSON.readTree(text.toString());}catch(Exception invalid){throw new IllegalStateException("EXECUTION_DEFINITION_NOT_READABLE");}
 }
 private static JsonNode sorted(JsonNode value){
  if(value.isObject()){var result=JSON.createObjectNode();var keys=new TreeSet<String>();value.fieldNames().forEachRemaining(keys::add);for(String key:keys)result.set(key,sorted(value.get(key)));return result;}
  if(value.isArray()){var result=JSON.createArrayNode();value.forEach(v->result.add(sorted(v)));return result;}
  return value;
 }
 public static String sha(String value){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
  catch(Exception unavailable){throw new IllegalStateException("SHA256_UNAVAILABLE");}
 }
 private static String encode(JsonNode value){try{return JSON.writeValueAsString(sorted(value));}catch(Exception invalid){throw new IllegalStateException("EXECUTION_DEFINITION_NOT_SERIALIZABLE");}}
 private static Map<String,String> base(Map<?,?> scope){
  var result=new TreeMap<String,String>();
  for(var entry:scope.entrySet())if(!HASH_KEYS.contains(String.valueOf(entry.getKey())))result.put(String.valueOf(entry.getKey()),String.valueOf(entry.getValue()));
  return result;
 }
 public void lockJob(UUID job){
  auth.lockIdentityMutation();jdbc.execute("SELECT pg_advisory_xact_lock(805014630400001::bigint)");
  if(jdbc.queryForList("SELECT job_id FROM job WHERE job_id=? FOR UPDATE",job).isEmpty())fail(HttpStatus.NOT_FOUND,"JOB_NOT_AVAILABLE");
 }
 public String currentBootstrap(){
  try{
   Path path=Path.of(templatePath);
   if(Files.isRegularFile(path))return Files.readString(path,StandardCharsets.UTF_8);
   if(!"/app/script/command.sh".equals(templatePath))fail(HttpStatus.SERVICE_UNAVAILABLE,"JOB_BOOTSTRAP_NOT_AVAILABLE");
   try(var input=JobExecutionDefinitionService.class.getResourceAsStream("/script/command.sh")){
    if(input==null)fail(HttpStatus.SERVICE_UNAVAILABLE,"JOB_BOOTSTRAP_NOT_AVAILABLE");
    return new String(input.readAllBytes(),StandardCharsets.UTF_8);
   }
  }catch(ResponseStatusException denied){throw denied;}
  catch(Exception unavailable){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"JOB_BOOTSTRAP_NOT_AVAILABLE");}
 }
 public static boolean entrypoint(HttpServletRequest request){
  String[] values=request.getParameterValues("filePath");
  return values!=null&&values.length==1&&entrypoint(values[0]);
 }
 public static boolean entrypoint(String path){return "command.sh".equals(path)||"/command.sh".equals(path);}
 private List<Map<String,Object>> declarations(UUID job,Map<?,?> scope){
  var inputs=new ArrayList<Map<String,Object>>();
  for(UUID repo:jdbc.queryForList("SELECT input_repo_id FROM job_input WHERE job_id=? ORDER BY input_repo_id",UUID.class,job)){
   UUID pin=declaredInputPin(job,repo);UUID commit=repo.equals(id(scope,"inputRepoId"))?id(scope,"inputCommitId"):pin!=null?pin:head(repo);
   if(pin!=null&&!pin.equals(commit))fail(HttpStatus.CONFLICT,"JOB_INPUT_PIN_MISMATCH");published(repo,commit);
   var paths=jdbc.queryForList("SELECT logical_path FROM job_input_file WHERE job_id=? AND repo_id=? ORDER BY logical_path",String.class,job,repo);
   if(paths.contains("*ALL*"))paths=List.of();
   inputs.add(Map.of("repoId",repo.toString(),"commitId",commit.toString(),"paths",paths));
  }
  return inputs;
 }
 private UUID head(UUID repo){
  var rows=jdbc.queryForList("SELECT commit_id FROM artifact_commit WHERE repo_id=? AND commit_status='PUBLISHED' ORDER BY published_at DESC NULLS LAST,created_at DESC,commit_id LIMIT 1",UUID.class,repo);
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"JOB_RESOURCE_NOT_AVAILABLE");return rows.get(0);
 }
 private UUID draft(UUID repo){
  var rows=jdbc.queryForList("SELECT commit_id FROM artifact_commit WHERE repo_id=? AND commit_status='DRAFT' ORDER BY created_at DESC LIMIT 1",UUID.class,repo);
  return rows.isEmpty()?null:rows.get(0);
 }
 private List<Map<String,Object>> outputs(UUID job,Map<?,?> scope){
  var result=new ArrayList<Map<String,Object>>();
  for(UUID repo:jdbc.queryForList("SELECT output_repo_id FROM job_output WHERE job_id=? ORDER BY output_repo_id",UUID.class,job)){
   UUID commit=repo.equals(id(scope,"outputRepoId"))&&scope.get("outputCommitId")!=null?id(scope,"outputCommitId"):draft(repo);
   var item=new TreeMap<String,Object>();item.put("repoId",repo.toString());if(commit!=null)item.put("commitId",commit.toString());result.add(item);
  }
  return result;
 }
 private JsonNode binding(AuthorizationService.Actor actor,UUID repo,UUID commit,boolean output){
  if(previews.managed(repo))fail(HttpStatus.CONFLICT,"MANAGED_VIEW_PREVIEW_REQUIRES_DEDICATED_FLOW");
  actor=auth.actorFor(actor.userId());
  String operation=output?"dataset.upload":"data.raw.download",purpose="GENERAL_READ";
  var datasets=jdbc.queryForList("SELECT dataset_id,raw_repo_id,index_repo_id,org_id FROM mm_dataset WHERE raw_repo_id=? OR index_repo_id=?",repo,repo);
  UUID dataset=null,version=null,index=null;
  if(datasets.size()>1)fail(HttpStatus.NOT_FOUND,"JOB_RESOURCE_NOT_AVAILABLE");
  if(!datasets.isEmpty()){
   var data=datasets.get(0);dataset=(UUID)data.get("dataset_id");
   if(output&&repo.equals(data.get("index_repo_id"))){operation="dataset.index.build";purpose="VIEW_BUILD";}
   if(!output&&commit!=null){
    if(repo.equals(data.get("raw_repo_id"))){
     var versions=jdbc.queryForList("SELECT version_id FROM mm_dataset_version WHERE dataset_id=? AND raw_commit_id=?",UUID.class,dataset,commit);
     if(versions.size()==1)version=versions.get(0);
    }else{
     var versions=jdbc.queryForList("SELECT dataset_version_id,index_version_id FROM mm_index_version WHERE dataset_id=? AND index_commit_id=? AND index_status='READY'",dataset,commit);
     if(versions.size()==1){version=(UUID)versions.get(0).get("dataset_version_id");index=(UUID)versions.get(0).get("index_version_id");}
    }
   }
  }
  var required=new TreeSet<String>();required.add(operation);boolean indexRead=!output&&!datasets.isEmpty()&&repo.equals(datasets.get(0).get("index_repo_id"));
  if(indexRead){required.add("dataset.index.build");purpose="VIEW_BUILD";if(!actor.orgId().equals(datasets.get(0).get("org_id")))fail(HttpStatus.NOT_FOUND,"JOB_RESOURCE_NOT_AVAILABLE");}
  for(String code:required)auth.requireOperation(actor,code);
  var value=JSON.createObjectNode();value.put("repoId",repo.toString());if(commit!=null)value.put("commitId",commit.toString());
  value.put("operation",operation);value.put("purpose",purpose);value.put("output",output);value.set("requiredOperations",tree(required));value.put("readIndexBlob",indexRead);
  if(dataset!=null){
   if(!output&&version==null&&!auth.canUseDataset(actor,dataset,null,null,required,purpose))fail(HttpStatus.NOT_FOUND,"JOB_RESOURCE_NOT_AVAILABLE");
   for(var grant:jdbc.queryForList("SELECT grant_id,version FROM ds_resource_grant WHERE dataset_id=? AND state='ACTIVE' ORDER BY grant_id",dataset)){
    var candidate=new AuthorizationService.GrantBinding((UUID)grant.get("grant_id"),((Number)grant.get("version")).longValue());
    if(auth.canUseDatasetBound(actor,dataset,output?null:version,output?null:index,required,purpose,candidate)){
     value.put("kind","DATASET");value.put("datasetId",dataset.toString());if(!output&&version!=null)value.put("versionId",version.toString());
     if(!output&&index!=null)value.put("indexVersionId",index.toString());
     value.put("grantId",candidate.grantId().toString());value.put("grantVersion",candidate.version());return value;
    }
   }
   fail(HttpStatus.NOT_FOUND,"JOB_COMPLETE_RESOURCE_GRANT_REQUIRED");
  }
  // Preserve the existing private artifact ACL, with an anti-replay generation.
  // Explicit standalone-artifact grant administration remains a separate migration.
  var owners=jdbc.queryForList("SELECT a.repo_id,g.version FROM artifact_repo a JOIN ds_artifact_acl_generation g ON g.repo_id=a.repo_id AND NOT g.deleted WHERE a.repo_id=? AND a.org_id=? AND a.owner_user_id=?",repo,actor.orgId(),actor.userId());
  if(owners.size()!=1)fail(HttpStatus.NOT_FOUND,"JOB_RESOURCE_NOT_AVAILABLE");
  value.put("kind","LEGACY_PRIVATE_ARTIFACT_ACL");value.put("aclGeneration",((Number)owners.get(0).get("version")).longValue());return value;
 }
 private boolean bindingsCurrent(JsonNode body,AuthorizationService.Actor actor){
  for(JsonNode value:body.path("bindings")){
   String operation=value.path("operation").asText();if(!actor.operations().contains(operation))return false;
   UUID repo=UUID.fromString(value.path("repoId").asText());
   if("DATASET".equals(value.path("kind").asText())){
    UUID dataset=UUID.fromString(value.path("datasetId").asText());
    var coordinates=jdbc.queryForList("SELECT index_repo_id,org_id FROM mm_dataset WHERE dataset_id=? AND (raw_repo_id=? OR index_repo_id=?)",dataset,repo,repo);if(coordinates.size()!=1)return false;
    var required=new TreeSet<String>();required.add(operation);String purpose=value.path("purpose").asText();
    // Recheck legacy records too. A raw capability cannot read private index blobs.
    if(!value.path("output").asBoolean()&&repo.equals(coordinates.get(0).get("index_repo_id"))){required.add("dataset.index.build");purpose="VIEW_BUILD";if(!actor.orgId().equals(coordinates.get(0).get("org_id")))return false;}
    if(!actor.operations().containsAll(required))return false;
    UUID version=value.hasNonNull("versionId")?UUID.fromString(value.path("versionId").asText()):null;
    var bound=new AuthorizationService.GrantBinding(UUID.fromString(value.path("grantId").asText()),value.path("grantVersion").asLong());
    UUID index=value.hasNonNull("indexVersionId")?UUID.fromString(value.path("indexVersionId").asText()):null;
    if(!auth.canUseDatasetBound(actor,dataset,version,index,required,purpose,bound))return false;
   }else if(!exists("SELECT EXISTS(SELECT 1 FROM artifact_repo a JOIN ds_artifact_acl_generation g ON g.repo_id=a.repo_id AND NOT g.deleted WHERE a.repo_id=? AND a.org_id=? AND a.owner_user_id=? AND g.version=?)",repo,actor.orgId(),actor.userId(),value.path("aclGeneration").asLong()))return false;
  }
  if(body.has("ioManifest")&&!origins.executionInputsCurrent(UUID.fromString(body.path("ioScope").path("runId").asText()),actor))return false;
  return true;
 }
 private void genericBindings(AuthorizationService.Actor actor,List<Map<String,Object>> inputs,List<Map<String,Object>> outputs,ArrayNode result){
  auth.requireOperation(actor,"platform.runtime.manage");
  for(var input:inputs){UUID repo=UUID.fromString((String)input.get("repoId")),commit=UUID.fromString((String)input.get("commitId"));published(repo,commit);var bound=binding(actor,repo,commit,false);result.add(bound);
   var operations=new TreeSet<String>();for(JsonNode value:bound.path("requiredOperations"))operations.add(value.asText());operations.add(bound.path("operation").asText());
   origins.requireSelectedInputs(repo,commit,tree(input.get("paths")),operations,bound.path("purpose").asText());}
  for(var output:outputs){
   UUID repo=UUID.fromString((String)output.get("repoId")),commit=output.containsKey("commitId")?UUID.fromString((String)output.get("commitId")):null;
   if(commit!=null&&exists("SELECT EXISTS(SELECT 1 FROM artifact_commit WHERE repo_id=? AND commit_id=? AND meta->>'managedIndexOutput'='true')",repo,commit))
    fail(HttpStatus.CONFLICT,"该索引输出由构建任务管理，请使用重建索引流程");
   result.add(binding(actor,repo,commit,true));
  }
 }
 public void preflightGeneric(UUID job,Map<?,?> scope){
  var inputs=declarations(job,scope);var outs=outputs(job,scope);
  genericBindings(auth.actor(),inputs,outs,JSON.createArrayNode());
 }
 private JobRequest declarationRequest(UUID job){
  var rows=jdbc.queryForList("SELECT params FROM job WHERE job_id=?",job);if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"JOB_RESOURCE_NOT_AVAILABLE");
  var inputs=new ArrayList<JobRequest.Input>();
  for(UUID repo:jdbc.queryForList("SELECT input_repo_id FROM job_input WHERE job_id=? ORDER BY input_repo_id",UUID.class,job)){
   var paths=jdbc.queryForList("SELECT logical_path FROM job_input_file WHERE job_id=? AND repo_id=? ORDER BY logical_path",String.class,job,repo);
   if(paths.contains("*ALL*"))paths=List.of();inputs.add(new JobRequest.Input(repo,declaredInputPin(job,repo),paths));
  }
  @SuppressWarnings("unchecked") Map<String,Object> parameters=JSON.convertValue(rows.get(0).get("params")==null?JSON.createObjectNode():parse(rows.get(0).get("params")),Map.class);
  return new JobRequest("execution","TRANSFORM",inputs,jdbc.queryForList("SELECT output_repo_id FROM job_output WHERE job_id=? ORDER BY output_repo_id",UUID.class,job),"snapshot","snapshot",false,parameters);
 }
 public void preflightRequest(JobRequest request){
  auth.lockIdentityMutation();jdbc.execute("SELECT pg_advisory_xact_lock(805014630400001::bigint)");
  var actor=auth.actorFor(auth.actor().userId());auth.requireOperation(actor,"platform.runtime.manage");
  JsonNode env=tree(request.params()==null?Map.of():request.params()).path("env");
  String pinRepo=env.path("MDS_INPUT_REPO_ID").asText(null),pinCommit=env.path("MDS_INPUT_COMMIT_ID").asText(null);
  if((pinRepo==null)!=(pinCommit==null))fail(HttpStatus.BAD_REQUEST,"JOB_INPUT_PIN_REQUIRED");
  var inputs=new ArrayList<Map<String,Object>>();var seen=new HashSet<UUID>();var requestedPins=new HashMap<UUID,UUID>();
  var selectedPaths=new LinkedHashMap<UUID,Set<String>>();var wholeRepos=new HashSet<UUID>();
  for(var input:request.inputs()){
   if(requestedPins.containsKey(input.repoId())&&!Objects.equals(requestedPins.get(input.repoId()),input.commitId()))fail(HttpStatus.BAD_REQUEST,"JOB_INPUT_PIN_CONFLICT");
   requestedPins.put(input.repoId(),input.commitId());seen.add(input.repoId());var paths=selectedPaths.computeIfAbsent(input.repoId(),key->new TreeSet<>());
   if(input.paths().isEmpty())wholeRepos.add(input.repoId());else for(String path:input.paths())paths.add(PathUtils.normalizePath(path));
  }
  for(var entry:selectedPaths.entrySet()){
   UUID repo=entry.getKey(),pin=requestedPins.get(repo);UUID legacy=pinRepo!=null&&repo.equals(UUID.fromString(pinRepo))?UUID.fromString(pinCommit):null;
   if(legacy!=null&&pin!=null&&!legacy.equals(pin))fail(HttpStatus.BAD_REQUEST,"JOB_INPUT_PIN_CONFLICT");
   UUID commit=pin!=null?pin:legacy!=null?legacy:head(repo);published(repo,commit);
   inputs.add(Map.of("repoId",repo.toString(),"commitId",commit.toString(),"paths",wholeRepos.contains(repo)?List.of():new ArrayList<>(entry.getValue())));
  }
  if(pinRepo!=null&&(inputs.size()!=1||!seen.contains(UUID.fromString(pinRepo))))fail(HttpStatus.BAD_REQUEST,"JOB_INPUT_PIN_MISMATCH");
  var outs=new ArrayList<Map<String,Object>>();for(UUID repo:new TreeSet<>(request.outputRepoIds())){
   var value=new TreeMap<String,Object>();value.put("repoId",repo.toString());UUID commit=draft(repo);if(commit!=null)value.put("commitId",commit.toString());outs.add(value);
  }
  genericBindings(actor,inputs,outs,JSON.createArrayNode());
 }
 public void preflightCurrentGeneric(UUID job){
  if(previews.isManagedJob(job))fail(HttpStatus.CONFLICT,"MANAGED_VIEW_PREVIEW_REQUIRES_DEDICATED_FLOW");
  preflightRequest(declarationRequest(job));
  if(exists("SELECT EXISTS(SELECT 1 FROM mm_index_version WHERE build_job_id=?) OR EXISTS(SELECT 1 FROM ds_index_execution_origin WHERE job_id=?)",job,job))
   fail(HttpStatus.CONFLICT,"索引任务请通过重建索引修改配置");
 }
 public void validateUpdate(UUID job,JobRequest request){
  lockJob(job);if(previews.isManagedJob(job))fail(HttpStatus.CONFLICT,"MANAGED_VIEW_PREVIEW_DEFINITION_IS_IMMUTABLE");preflightRequest(declarationRequest(job));
  if(exists("SELECT EXISTS(SELECT 1 FROM mm_index_version WHERE build_job_id=?) OR EXISTS(SELECT 1 FROM ds_index_execution_origin WHERE job_id=?)",job,job))
   fail(HttpStatus.CONFLICT,"索引任务请通过重建索引修改配置");
  // A legacy run has no trustworthy old declaration to reconstruct.
  if(exists("SELECT EXISTS(SELECT 1 FROM job_run r WHERE r.job_id=? AND NOT EXISTS(SELECT 1 FROM ds_job_execution_definition d WHERE d.run_id=r.run_id))",job)){
   var oldInputs=new TreeMap<String,Set<String>>();for(UUID repo:jdbc.queryForList("SELECT input_repo_id FROM job_input WHERE job_id=?",UUID.class,job)){
    var paths=new TreeSet<>(jdbc.queryForList("SELECT logical_path FROM job_input_file WHERE job_id=? AND repo_id=?",String.class,job,repo));paths.remove("*ALL*");oldInputs.put(repo.toString(),paths);
   }
   var next=new TreeMap<String,Set<String>>();for(var input:request.inputs()){var paths=next.computeIfAbsent(input.repoId().toString(),k->new TreeSet<>());input.paths().forEach(p->paths.add(PathUtils.normalizePath(p)));}
   var oldOutputs=new TreeSet<>(jdbc.queryForList("SELECT output_repo_id::text FROM job_output WHERE job_id=?",String.class,job));
   var newOutputs=new TreeSet<String>();request.outputRepoIds().forEach(r->newOutputs.add(r.toString()));
   var oldPins=new HashMap<UUID,UUID>();for(var row:jdbc.queryForList("SELECT input_repo_id,input_commit_id FROM job_input WHERE job_id=?",job))oldPins.put((UUID)row.get("input_repo_id"),(UUID)row.get("input_commit_id"));
   var nextPins=new HashMap<UUID,UUID>();request.inputs().forEach(input->nextPins.put(input.repoId(),input.commitId()));
   if(!oldPins.equals(nextPins)||!oldInputs.equals(next)||!oldOutputs.equals(newOutputs))fail(HttpStatus.CONFLICT,"该任务含未冻结的历史执行；修改输入输出请创建新任务");
  }
 }
 public Definition freeze(Map<String,String> scope,Claims parent,int runtimeSeconds,JsonNode parameters){
  if(runtimeSeconds<=0||!"ACCESS".equals(parent.get("token_type",String.class)))fail(HttpStatus.FORBIDDEN,"EXECUTION_PARENT_ACCESS_REQUIRED");
  var actor=sessions.authenticateAccess(parent);UUID job=id(scope,"jobId"),run=id(scope,"runId");lockJob(job);actor=sessions.authenticateAccess(parent);
  if(!actor.userId().equals(id(scope,"userId"))||!actor.orgId().equals(id(scope,"orgId"))||!id(scope,"parentSessionId").toString().equals(parent.get("sid",String.class)))
   fail(HttpStatus.FORBIDDEN,"EXECUTION_PARENT_MISMATCH");
  var rows=jdbc.queryForList("SELECT j.job_name,j.image,j.cmd FROM job j JOIN job_run r ON r.job_id=j.job_id WHERE j.job_id=? AND r.run_id=? AND r.created_by=? AND r.org_id=? AND r.run_status IN('QUEUED','RUNNING')",job,run,actor.userId(),actor.orgId());
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"EXECUTION_RUN_NOT_AVAILABLE");
  boolean typed="INDEX_BUILD".equals(scope.get("jobKind")),preview=ControlledViewPreviewAuthority.KIND.equals(scope.get("jobKind"));
  if(scope.containsKey("jobKind")&&!typed&&!preview)fail(HttpStatus.FORBIDDEN,"EXECUTION_KIND_NOT_IMPLEMENTED");
  String operation=typed?"dataset.index.build":preview?ControlledViewPreviewAuthority.SHARE:"platform.runtime.manage";auth.requireOperation(actor,operation);scope.put("executionOperation",operation);
  String image=(String)rows.get(0).get("image"),script=(String)rows.get(0).get("cmd");
  if(image==null||image.isBlank()||image.chars().anyMatch(Character::isWhitespace)||script==null||script.isBlank())fail(HttpStatus.BAD_REQUEST,"JOB_IMAGE_AND_ENTRYPOINT_REQUIRED");
  var inputs=preview?previews.inputs(scope,script,image,actor):declarations(job,scope);var outs=outputs(job,scope);
  if(inputs.stream().noneMatch(i->scope.get("inputRepoId").equals(i.get("repoId")))||outs.stream().noneMatch(o->scope.get("outputRepoId").equals(o.get("repoId"))))
   fail(HttpStatus.FORBIDDEN,"EXECUTION_DECLARATION_MISMATCH");
  if(!exists("SELECT EXISTS(SELECT 1 FROM artifact_commit WHERE repo_id=? AND commit_id=? AND commit_status='PUBLISHED')",id(scope,"inputRepoId"),id(scope,"inputCommitId"))||
    !exists("SELECT EXISTS(SELECT 1 FROM artifact_commit WHERE repo_id=? AND commit_id=? AND commit_status='DRAFT')",id(scope,"outputRepoId"),id(scope,"outputCommitId")))
   fail(HttpStatus.CONFLICT,"EXECUTION_FROZEN_INPUT_OUTPUT_REQUIRED");
  var body=JSON.createObjectNode();var bindings=JSON.createArrayNode();
  if(typed){
   if(!exists("SELECT EXISTS(SELECT 1 FROM ds_index_execution_origin WHERE run_id=? AND job_id=? AND actor_user_id=? AND parent_session_id=? AND input_repo_id=? AND input_commit_id=? AND output_repo_id=? AND output_commit_id=?)",run,job,actor.userId(),id(scope,"parentSessionId"),id(scope,"inputRepoId"),id(scope,"inputCommitId"),id(scope,"outputRepoId"),id(scope,"outputCommitId")))
    fail(HttpStatus.FORBIDDEN,"EXECUTION_INDEX_ORIGIN_REQUIRED");
  }else if(!preview){
   genericBindings(actor,inputs,outs,bindings);
   for(var output:outs){if(!output.containsKey("commitId")||!exists("SELECT EXISTS(SELECT 1 FROM artifact_commit WHERE repo_id=? AND commit_id=? AND commit_status='DRAFT')",UUID.fromString((String)output.get("repoId")),UUID.fromString((String)output.get("commitId"))))fail(HttpStatus.CONFLICT,"JOB_ALL_OUTPUTS_REQUIRE_FROZEN_DRAFTS");
    if(exists("SELECT EXISTS(SELECT 1 FROM ds_job_execution_definition d JOIN job_run r ON r.run_id=d.run_id, jsonb_array_elements(d.definition->'declaredOutputs') o WHERE r.run_id<>? AND r.run_status IN('QUEUED','RUNNING') AND o->>'repoId'=? AND o->>'commitId'=?)",run,output.get("repoId"),output.get("commitId")))fail(HttpStatus.CONFLICT,"JOB_OUTPUT_DRAFT_HAS_ACTIVE_EXECUTION");
   }
  }
  if(preview&&scope.containsKey("previousIndexCommitId"))fail(HttpStatus.FORBIDDEN,"PREVIEW_HAS_NO_PREVIOUS_INDEX_SCOPE");
  if(scope.containsKey("previousIndexCommitId")&&!typed)bindings.add(binding(actor,id(scope,"outputRepoId"),id(scope,"previousIndexCommitId"),false));
  String bootstrap=currentBootstrap();long expiry=Instant.now().getEpochSecond()+runtimeSeconds+300L;
  body.put("schema","job-execution-definition-v1");body.put("jobName",String.valueOf(rows.get(0).get("job_name")));
  body.put("image",image);body.put("script",script);body.put("bootstrap",bootstrap);body.put("operation",operation);
  body.put("actorSubject",parent.getSubject());body.put("tokenVersion",actor.tokenVersion());body.put("expiresAt",expiry);
  body.set("ioScope",tree(base(scope)));body.set("parameters",parameters==null?JSON.createObjectNode():parameters.deepCopy());
  body.set("declaredInputs",tree(inputs));body.set("declaredOutputs",tree(outs));body.set("bindings",bindings);
  if(!typed&&!preview){var manifest=ioManifest(scope,inputs,outs);body.put("schema","job-execution-definition-v2");body.set("ioManifest",manifest);body.put("ioManifestSha256",sha(encode(manifest)));}
  String encoded=encode(body),hash=sha(encoded),scriptHash=sha(script),bootstrapHash=sha(bootstrap);
  jdbc.update("INSERT INTO ds_job_execution_definition(run_id,job_id,actor_user_id,actor_org_id,parent_session_id,definition_sha256,entrypoint_sha256,bootstrap_sha256,expires_at,definition) VALUES(?,?,?,?,?,?,?,?,?,?::jsonb)",
   run,job,actor.userId(),actor.orgId(),id(scope,"parentSessionId"),hash,scriptHash,bootstrapHash,java.sql.Timestamp.from(Instant.ofEpochSecond(expiry)),encoded);
  if(body.has("ioManifest")){origins.captureExecution(run);if(!origins.executionInputsCurrent(run,actor))fail(HttpStatus.NOT_FOUND,"FILE_SOURCE_NOT_CURRENT");}
  if(body.has("ioManifest"))scope.put("ioManifestSha256",body.path("ioManifestSha256").asText());
  scope.put("executionDefinitionSha256",hash);scope.put("entrypointSha256",scriptHash);scope.put("bootstrapSha256",bootstrapHash);scope.put("definitionExpiresAt",Long.toString(expiry));
  return new Definition(body,hash,scriptHash,bootstrapHash);
 }
 public Definition read(UUID run){
  var rows=jdbc.queryForList("SELECT definition,definition_sha256,entrypoint_sha256,bootstrap_sha256 FROM ds_job_execution_definition WHERE run_id=?",run);
  if(rows.size()!=1)fail(HttpStatus.FORBIDDEN,"EXECUTION_DEFINITION_REQUIRED");
  return checkedDefinition(rows.get(0));
 }
 static Definition checkedDefinition(Map<String,Object> row){
  var body=parse(row.get("definition"));String hash=row.get("definition_sha256").toString().trim(),script=row.get("entrypoint_sha256").toString().trim(),bootstrap=row.get("bootstrap_sha256").toString().trim();
  if(!sha(encode(body)).equals(hash)||!sha(body.path("script").asText()).equals(script)||!sha(body.path("bootstrap").asText()).equals(bootstrap))
   fail(HttpStatus.SERVICE_UNAVAILABLE,"EXECUTION_DEFINITION_IDENTITY_MISMATCH");
  if(body.has("ioManifest")&&!sha(encode(body.path("ioManifest"))).equals(body.path("ioManifestSha256").asText()))fail(HttpStatus.SERVICE_UNAVAILABLE,"EXECUTION_IO_MANIFEST_IDENTITY_MISMATCH");
  return new Definition(body,hash,script,bootstrap);
 }
 /** Server-side reconstruction from the append-only definition; never exposed as a bearer. */
 public Claims recordedClaims(UUID run){
  var record=read(run);var body=record.body();var scope=new TreeMap<String,String>();
  body.path("ioScope").fields().forEachRemaining(entry->scope.put(entry.getKey(),entry.getValue().asText()));
  scope.put("executionDefinitionSha256",record.hash());scope.put("entrypointSha256",record.scriptSha());
  if(body.has("ioManifest"))scope.put("ioManifestSha256",body.path("ioManifestSha256").asText());
  scope.put("bootstrapSha256",record.bootstrapSha());scope.put("definitionExpiresAt",Long.toString(body.path("expiresAt").asLong()));
  return io.jsonwebtoken.Jwts.claims().subject(body.path("actorSubject").asText())
   .expiration(java.util.Date.from(Instant.ofEpochSecond(body.path("expiresAt").asLong())))
   .add("token_type","JOB_IO").add("token_version",body.path("tokenVersion").asInt())
   .add("uid",scope.get("userId")).add("org_id",scope.get("orgId")).add("sid",scope.get("parentSessionId"))
   .add("job_io_scope",Map.copyOf(scope)).build();
 }
 public boolean current(Claims claims){
  try{
   if(!"JOB_IO".equals(claims.get("token_type",String.class)))return false;Map<?,?> scope=claims.get("job_io_scope",Map.class);if(scope==null)return false;
   var record=read(id(scope,"runId"));var body=record.body();
   if(!record.hash().equals(scope.get("executionDefinitionSha256"))||!record.scriptSha().equals(scope.get("entrypointSha256"))||!record.bootstrapSha().equals(scope.get("bootstrapSha256"))||
     !body.path("ioScope").equals(tree(base(scope)))||!body.path("actorSubject").asText().equals(claims.getSubject())||
     body.path("tokenVersion").asInt()!=claims.get("token_version",Integer.class)||claims.getExpiration()==null||
     claims.getExpiration().toInstant().getEpochSecond()!=body.path("expiresAt").asLong()||Instant.now().getEpochSecond()>=body.path("expiresAt").asLong())return false;
   if(body.has("ioManifest")&&!body.path("ioManifestSha256").asText().equals(scope.get("ioManifestSha256")))return false;
   var actor=sessions.authenticateAccess(claims);
   if(!exists("SELECT EXISTS(SELECT 1 FROM job_run WHERE run_id=? AND job_id=? AND created_by=? AND org_id=?)",id(scope,"runId"),id(scope,"jobId"),actor.userId(),actor.orgId()))return false;
   auth.requireOperation(actor,body.path("operation").asText());return bindingsCurrent(body,actor);
  }catch(ResponseStatusException denied){if(denied.getStatusCode().value()>=500)throw denied;return false;}
  catch(IllegalArgumentException|NullPointerException denied){return false;}
 }
 public java.util.function.Predicate<String> inputPathPredicate(UUID repo,UUID commit,Collection<String> available){
  var attrs=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
  if(!(attrs instanceof org.springframework.web.context.request.ServletRequestAttributes web)
    ||!(web.getRequest().getAttribute("ds.jobIoClaims") instanceof Claims claims))return path->true;
  if(!current(claims))fail(HttpStatus.FORBIDDEN,"EXECUTION_DEFINITION_NOT_AVAILABLE");
  var definition=read(id(((Claims)web.getRequest().getAttribute("ds.jobIoClaims")).get("job_io_scope",Map.class),"runId"));
  JsonNode selected=null;
  for(JsonNode input:definition.body().path("declaredInputs"))if(repo.toString().equals(input.path("repoId").asText())&&commit.toString().equals(input.path("commitId").asText())){selected=input;break;}
  // Previous-index cache reads are separately frozen in ioScope and authorized
  // by JobIoScope/IndexJobAuthority; they have no user input-path selector.
  if(selected==null||selected.path("paths").isEmpty())return path->true;
  var names=new HashSet<String>();available.forEach(path->names.add(PathUtils.normalizePath(path)));
  var exact=new HashSet<String>();var prefixes=new ArrayList<String>();
  for(JsonNode selector:selected.path("paths")){
   String path=PathUtils.normalizePath(selector.asText()),prefix="/".equals(path)?"/":path+"/";
   boolean file=names.contains(path),directory=names.stream().anyMatch(name->name.startsWith(prefix));
   if(file&&directory)fail(HttpStatus.CONFLICT,"任务输入路径同时表示文件和目录，请选择具体文件");
   if(file)exact.add(path);
   else if(directory)prefixes.add(prefix);
   else fail(HttpStatus.NOT_FOUND,"JOB_INPUT_SELECTION_NOT_AVAILABLE");
  }
  return path->{String value=PathUtils.normalizePath(path);return exact.contains(value)||prefixes.stream().anyMatch(value::startsWith);};
 }
 public Definition worker(HttpServletRequest request){
  if(!(request.getAttribute("ds.jobIoClaims") instanceof Claims claims)||!current(claims))fail(HttpStatus.FORBIDDEN,"EXECUTION_DEFINITION_NOT_AVAILABLE");
  return read(id(((Claims)request.getAttribute("ds.jobIoClaims")).get("job_io_scope",Map.class),"runId"));
 }
 public WorkerOperation workerOperation(HttpServletRequest request,Claims claims){
  if(!current(claims))fail(HttpStatus.FORBIDDEN,"EXECUTION_DEFINITION_NOT_AVAILABLE");
  var record=read(id(claims.get("job_io_scope",Map.class),"runId"));
  if(ControlledViewPreviewAuthority.KIND.equals(record.body().path("ioScope").path("jobKind").asText()))return new WorkerOperation(request.getRequestURI().contains("/api/artifacts/")&&"GET".equals(request.getMethod())?previews.workerReadOperation(claims):ControlledViewPreviewAuthority.SHARE,"VIEW_BUILD");
  if("dataset.index.build".equals(record.body().path("operation").asText()))return new WorkerOperation("dataset.index.build","VIEW_BUILD");
  if("PUT".equals(request.getMethod())){
   String uri=request.getRequestURI();String context=request.getContextPath();if(context!=null&&!context.isEmpty())uri=uri.substring(context.length());UUID repo=null;
   for(JsonNode output:record.body().path("declaredOutputs"))if(uri.equals("/api/artifacts/"+output.path("repoId").asText()+"/commits/"+output.path("commitId").asText()+"/files")){repo=UUID.fromString(output.path("repoId").asText());break;}
   if(repo==null)fail(HttpStatus.FORBIDDEN,"EXECUTION_OUTPUT_BINDING_REQUIRED");
   for(JsonNode value:record.body().path("bindings"))if(value.path("output").asBoolean()&&repo.toString().equals(value.path("repoId").asText()))return new WorkerOperation(value.path("operation").asText(),value.path("purpose").asText());
  }
  if(request.getRequestURI().contains("/api/artifacts/")){
   String uri=request.getRequestURI();String context=request.getContextPath();if(context!=null&&!context.isEmpty())uri=uri.substring(context.length());
   for(JsonNode value:record.body().path("bindings"))if(!value.path("output").asBoolean()){
    String files="/api/artifacts/"+value.path("repoId").asText()+"/commits/"+value.path("commitId").asText()+"/files";
    if(uri.equals(files)||uri.equals(files+"/content")){boolean indexRead=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM mm_dataset WHERE index_repo_id=?)",Boolean.class,UUID.fromString(value.path("repoId").asText()));return new WorkerOperation(value.path("operation").asText(),indexRead?"VIEW_BUILD":value.path("purpose").asText());}
   }fail(HttpStatus.FORBIDDEN,"EXECUTION_INPUT_BINDING_REQUIRED");
  }
  return new WorkerOperation("platform.runtime.manage","GENERAL_READ");
 }
 private JsonNode ioManifest(Map<String,String> scope,List<Map<String,Object>> inputs,List<Map<String,Object>> outputs){
  var manifest=JSON.createObjectNode();manifest.put("schema","job-io-manifest-v1");manifest.put("jobId",scope.get("jobId"));manifest.put("runId",scope.get("runId"));
  for(var entry:List.of(Map.entry("inputs",inputs),Map.entry("outputs",outputs))){var rows=JSON.createArrayNode();boolean input=entry.getKey().equals("inputs");String primary=scope.get(input?"inputRepoId":"outputRepoId");
   for(var declaration:entry.getValue()){var item=(ObjectNode)tree(declaration);boolean first=primary.equals(item.path("repoId").asText());item.put("primary",first);item.put("localPath",first?(input?"/tmp/in":"/tmp/out"):(input?"/tmp/inputs/":"/tmp/outputs/")+item.path("repoId").asText());rows.add(item);}manifest.set(entry.getKey(),rows);
  }
  if(scope.containsKey("previousIndexCommitId")){var cache=JSON.createObjectNode();cache.put("repoId",scope.get("outputRepoId"));cache.put("commitId",scope.get("previousIndexCommitId"));cache.put("localPath","/tmp/previous/professional/assets");cache.set("paths",JSON.createArrayNode());manifest.set("previousIndexCache",cache);}
  return manifest;
 }
 /** Only the verified definition may enlarge a new generic run beyond the legacy primary pair. */
 public boolean frozenRepository(Claims claims,UUID repo,UUID commit,boolean write){
  var scope=claims.get("job_io_scope",Map.class);var body=read(id(scope,"runId")).body();
  if(!scope.containsKey("jobKind")&&body.has("ioManifest")){
   for(JsonNode item:body.path(write?"declaredOutputs":"declaredInputs"))if(repo.toString().equals(item.path("repoId").asText())&&commit!=null&&commit.toString().equals(item.path("commitId").asText()))return true;
  }else if(repo.equals(id(scope,write?"outputRepoId":"inputRepoId"))&&Objects.equals(commit,id(scope,write?"outputCommitId":"inputCommitId")))return true;
  return !write&&scope.get("previousIndexCommitId") instanceof String previous&&repo.equals(id(scope,"outputRepoId"))&&Objects.equals(commit,UUID.fromString(previous));
 }
 public JsonNode workerManifest(UUID job,HttpServletRequest request){
  var record=worker(request);if(!job.toString().equals(record.body().path("ioScope").path("jobId").asText())||!record.body().has("ioManifest"))fail(HttpStatus.FORBIDDEN,"EXECUTION_IO_MANIFEST_NOT_AVAILABLE");return record.body().path("ioManifest").deepCopy();
 }
 public UUID declaredInputPin(UUID job,UUID repo){
  var rows=jdbc.queryForList("SELECT input_commit_id FROM job_input WHERE job_id=? AND input_repo_id=?",job,repo);if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"JOB_RESOURCE_NOT_AVAILABLE");return (UUID)rows.get(0).get("input_commit_id");
 }
 private void published(UUID repo,UUID commit){if(!exists("SELECT EXISTS(SELECT 1 FROM artifact_commit WHERE repo_id=? AND commit_id=? AND commit_status='PUBLISHED')",repo,commit))fail(HttpStatus.CONFLICT,"JOB_INPUT_PUBLISHED_SNAPSHOT_REQUIRED");}

}
