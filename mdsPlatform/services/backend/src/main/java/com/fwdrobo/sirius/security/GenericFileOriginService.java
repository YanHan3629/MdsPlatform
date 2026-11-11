package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fwdrobo.sirius.dto.file.CommitManifest;
import com.fwdrobo.sirius.service.ManifestService;
import com.fwdrobo.sirius.util.PathUtils;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Additional source constraints; callers still need their own complete resource authority. */
@Service
public class GenericFileOriginService {
 private final JdbcTemplate db;private final AuthorizationService auth;private final ManifestService manifests;
 private static final ObjectMapper JSON=new ObjectMapper();
 public GenericFileOriginService(JdbcTemplate db,AuthorizationService auth,ManifestService manifests){this.db=db;this.auth=auth;this.manifests=manifests;}
 private static void deny(){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"FILE_SOURCE_NOT_CURRENT");}
 private static void unresolved(){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"FILE_SOURCE_NOT_VERIFIABLE");}
 private static UUID id(JsonNode n,String key){return UUID.fromString(n.path(key).asText());}
 private static JsonNode parse(Object v){try{return JSON.readTree(v.toString());}catch(Exception bad){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"FILE_ORIGIN_METADATA_INVALID");}}
 private static JsonNode sorted(JsonNode n){if(n.isObject()){var o=JSON.createObjectNode();var keys=new TreeSet<String>();n.fieldNames().forEachRemaining(keys::add);for(String k:keys)o.set(k,sorted(n.get(k)));return o;}if(n.isArray()){var a=JSON.createArrayNode();n.forEach(v->a.add(sorted(v)));return a;}return n;}
 private static String encoded(JsonNode n){try{return JSON.writeValueAsString(sorted(n));}catch(Exception bad){throw new IllegalStateException("FILE_ORIGIN_ENCODING_FAILED");}}
 private static String digest(JsonNode n){return JobExecutionDefinitionService.sha(encoded(n));}
 private Map<String,Object> definition(UUID run){var rows=db.queryForList("SELECT actor_user_id,actor_org_id,definition,definition_sha256,entrypoint_sha256,bootstrap_sha256 FROM ds_job_execution_definition WHERE run_id=?",run);if(rows.size()!=1){unresolved();}return rows.get(0);}
 private Map<String,Object> file(UUID id){var rows=db.queryForList("SELECT f.file_id,f.file_key,btrim(f.sha256) AS sha256,f.size_bytes,f.logical_path,f.commit_id,c.repo_id FROM artifact_file f JOIN artifact_commit c ON c.commit_id=f.commit_id WHERE f.file_id=?",id);if(rows.size()!=1){unresolved();}return rows.get(0);}
 private Map<UUID,Map<String,Object>> fileRows(Collection<UUID> ids){
  var unique=new ArrayList<>(new LinkedHashSet<>(ids));var result=new HashMap<UUID,Map<String,Object>>();
  for(int offset=0;offset<unique.size();offset+=500){var part=unique.subList(offset,Math.min(offset+500,unique.size()));String marks=String.join(",",Collections.nCopies(part.size(),"?"));
   for(var row:db.queryForList("SELECT f.file_id,f.file_key,btrim(f.sha256) AS sha256,f.size_bytes,f.logical_path,f.commit_id,c.repo_id FROM artifact_file f JOIN artifact_commit c ON c.commit_id=f.commit_id WHERE f.file_id IN ("+marks+")",part.toArray()))result.put((UUID)row.get("file_id"),row);
  }
  if(result.size()!=unique.size()){unresolved();}return result;
 }
 private java.util.function.Predicate<String> selection(JsonNode paths,Collection<String> available){
  if(paths.isEmpty())return p->true;
  var names=new HashSet<String>();available.forEach(p->names.add(PathUtils.normalizePath(p)));var exact=new HashSet<String>();var prefixes=new ArrayList<String>();
  for(JsonNode value:paths){String p=PathUtils.normalizePath(value.asText()),prefix="/".equals(p)?"/":p+"/";boolean isFile=names.contains(p),isDirectory=names.stream().anyMatch(v->v.startsWith(prefix));
   if(isFile&&isDirectory)throw new ResponseStatusException(HttpStatus.CONFLICT,"AMBIGUOUS_SOURCE_INPUT_PATH");
   if(isFile)exact.add(p);else if(isDirectory)prefixes.add(prefix);else unresolved();
  }
  return p->{String value=PathUtils.normalizePath(p);return exact.contains(value)||prefixes.stream().anyMatch(value::startsWith);};
 }
 private JsonNode snapshot(UUID run,String kind){
  var prior=db.queryForList("SELECT snapshot,snapshot_sha256 FROM ds_generic_execution_origin WHERE run_id=?",run);
  if(!prior.isEmpty()){var n=parse(prior.get(0).get("snapshot"));if(!digest(n).equals(prior.get(0).get("snapshot_sha256").toString().trim()))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"FILE_ORIGIN_IDENTITY_MISMATCH");return n;}
  var row=definition(run);var checked=JobExecutionDefinitionService.checkedDefinition(row);var body=checked.body();if(!body.has("ioManifest")){unresolved();}
  var snap=JSON.createObjectNode();snap.put("actorUserId",row.get("actor_user_id").toString());snap.put("actorOrgId",row.get("actor_org_id").toString());snap.put("definitionSha256",checked.hash());var bindings=JSON.createArrayNode();
  for(JsonNode binding:body.path("bindings"))if(!binding.path("output").asBoolean())bindings.add(binding.deepCopy());if(bindings.isEmpty()){unresolved();}snap.set("bindings",bindings);
  var refs=JSON.createArrayNode();
  try{
   for(JsonNode input:body.path("declaredInputs")){
    UUID repo=id(input,"repoId"),commit=id(input,"commitId");var manifest=manifests.getManifest(repo,commit);var allowed=selection(input.path("paths"),manifest.getFiles().stream().map(CommitManifest.File::getPath).toList());
    var selected=manifest.getFiles().stream().filter(v->allowed.test(v.getPath())).toList();var metadata=fileRows(selected.stream().map(CommitManifest.File::getFileId).toList());
    for(var entry:selected){
     var f=metadata.get(entry.getFileId());if(!repo.equals(f.get("repo_id"))||!entry.getPath().equals(f.get("logical_path"))||!Objects.toString(f.get("sha256"),"").matches("[a-fA-F0-9]{64}")){unresolved();}
     var ref=JSON.createObjectNode();ref.put("fileId",entry.getFileId().toString());ref.put("objectKey",f.get("file_key").toString());ref.put("sha256",f.get("sha256").toString());ref.put("repoId",repo.toString());ref.put("commitId",commit.toString());ref.put("path",entry.getPath());refs.add(ref);
    }
   }
  }catch(ResponseStatusException denied){throw denied;}catch(Exception unavailable){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"SOURCE_MANIFEST_UNAVAILABLE",unavailable);}
  snap.set("files",refs);String hash=digest(snap);
  db.update("INSERT INTO ds_generic_execution_origin(run_id,definition_sha256,actor_user_id,actor_org_id,snapshot,snapshot_sha256,capture_kind) VALUES(?,?,?,?,?::jsonb,?,?) ON CONFLICT(run_id) DO NOTHING",run,checked.hash(),row.get("actor_user_id"),row.get("actor_org_id"),encoded(snap),hash,kind);
  return snapshot(run,kind);
 }
 public void captureExecution(UUID run){snapshot(run,"NEW_FROZEN_EXECUTION");}
 private UUID origin(Map<String,Object> f,Scan scan){
  String key=f.get("file_key").toString();var p=scan.originRecords.get(key);
  if(p!=null){if(!f.get("file_id").equals(p.get("file_id"))||!f.get("sha256").toString().equals(p.get("file_sha256").toString().trim())){unresolved();}return (UUID)p.get("run_id");}
  UUID commit=(UUID)f.get("commit_id");var candidates=scan.definitions.computeIfAbsent(commit,k->db.queryForList("""
   SELECT d.run_id,d.actor_user_id,d.actor_org_id FROM ds_job_execution_definition d
   WHERE jsonb_exists(d.definition,'ioManifest') AND EXISTS(
    SELECT 1 FROM jsonb_array_elements(d.definition->'declaredOutputs') o WHERE o->>'repoId'=? AND o->>'commitId'=?)
   ORDER BY d.run_id
   """,f.get("repo_id").toString(),commit.toString()));
  var attempt=scan.uploads.get(key);
  if(candidates.isEmpty()){
   if(attempt!=null&&"VIEW_BUILD".equals(attempt.get("purpose_code"))&&"dataset.upload".equals(attempt.get("operation_code"))){unresolved();}
   return null;
  }
  if(attempt==null){unresolved();}
  if("GENERAL_READ".equals(attempt.get("purpose_code")))return null;
  var matching=candidates.stream().filter(v->v.get("actor_user_id").equals(attempt.get("actor_user_id"))&&v.get("actor_org_id").equals(attempt.get("actor_org_id"))).toList();if(matching.size()!=1){unresolved();}
  UUID run=(UUID)matching.get(0).get("run_id");var snap=snapshot(run,"UNIQUE_COMMITTED_UPLOAD_RECONSTRUCTION");
  insertOrigin(f,run,snap.path("definitionSha256").asText(),"UNIQUE_COMMITTED_UPLOAD_RECONSTRUCTION");return run;
 }
 private void insertOrigin(Map<String,Object> f,UUID run,String hash,String kind){
  db.update("INSERT INTO ds_generic_file_origin(object_key,file_id,repo_id,commit_id,file_sha256,run_id,definition_sha256,capture_kind) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(object_key) DO NOTHING",f.get("file_key"),f.get("file_id"),f.get("repo_id"),f.get("commit_id"),f.get("sha256"),run,hash,kind);
 }
 public void captureUploadedFile(UUID repo,UUID commit,UUID fileId){
  if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)||!(attrs.getRequest().getAttribute("ds.jobIoClaims") instanceof Claims claims))return;
  var scope=claims.get("job_io_scope",Map.class);UUID run=UUID.fromString(scope.get("runId").toString());var row=definition(run);var checked=JobExecutionDefinitionService.checkedDefinition(row);if(!checked.body().has("ioManifest"))return;
  var actor=auth.actor();if(!actor.userId().equals(row.get("actor_user_id"))||!actor.orgId().equals(row.get("actor_org_id"))||!checked.hash().equals(Objects.toString(scope.get("executionDefinitionSha256"),"")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"FROZEN_UPLOAD_ORIGIN_REQUIRED");
  boolean output=false;for(JsonNode declared:checked.body().path("declaredOutputs"))if(repo.toString().equals(declared.path("repoId").asText())&&commit.toString().equals(declared.path("commitId").asText()))output=true;
  if(!output)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"FROZEN_OUTPUT_ORIGIN_REQUIRED");
  captureExecution(run);if(!currentUse().execution(run,actor.userId(),actor.orgId(),false,new HashSet<>(),0)){deny();}
  insertOrigin(file(fileId),run,checked.hash(),"VERIFIED_WORKER_UPLOAD");
 }
 private final class Scan {
  final Map<UUID,List<Map<String,Object>>> definitions=new HashMap<>();
  Set<String> consumptionOperations=Set.of();
  Set<String> consumptionPurposes=Set.of();
  final Map<UUID,Boolean> indexRepositories=new HashMap<>();
  boolean indexRepository(UUID repo){return indexRepositories.computeIfAbsent(repo,key->Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM mm_dataset WHERE index_repo_id=?)",Boolean.class,key)));}
  final Map<UUID,Map<String,Object>> metadata=new HashMap<>();final Map<String,Boolean> current=new HashMap<>();
  final Map<String,Map<String,Object>> originRecords=new HashMap<>();
  final Map<String,Map<String,Object>> uploads=new HashMap<>();
  Map<String,Object> fileRow(UUID id){if(!metadata.containsKey(id))load(List.of(id));return metadata.get(id);}
  void load(Collection<UUID> ids){var missing=new ArrayList<>(new LinkedHashSet<>(ids.stream().filter(id->!metadata.containsKey(id)).toList()));metadata.putAll(fileRows(missing));
   for(int offset=0;offset<missing.size();offset+=500){var part=missing.subList(offset,Math.min(offset+500,missing.size()));String marks=String.join(",",Collections.nCopies(part.size(),"?"));
    for(var row:db.queryForList("SELECT object_key,file_id,file_sha256,run_id FROM ds_generic_file_origin WHERE file_id IN ("+marks+")",part.toArray()))originRecords.put(row.get("object_key").toString(),row);
    for(var row:db.queryForList("SELECT object_key,actor_user_id,actor_org_id,purpose_code,operation_code FROM artifact_upload_attempt WHERE final_file_id IN ("+marks+") AND state='COMMITTED'",part.toArray()))if(uploads.putIfAbsent(row.get("object_key").toString(),row)!=null){unresolved();}
   }
  }

  boolean rawSnapshot(UUID dataset,UUID version,UUID index,UUID reader,UUID readerOrg,boolean sharing,Set<UUID> seen,int depth){
  if(depth>64)return false;
  var repos=db.queryForList("SELECT raw_repo_id FROM mm_dataset WHERE dataset_id=?",UUID.class,dataset);if(repos.size()!=1)return false;UUID repo=repos.get(0);
  boolean generic=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM ds_job_execution_definition d WHERE jsonb_exists(d.definition,'ioManifest') AND EXISTS(SELECT 1 FROM jsonb_array_elements(d.definition->'declaredOutputs') o WHERE o->>'repoId'=?)) OR EXISTS(SELECT 1 FROM ds_generic_file_origin WHERE repo_id=?)",Boolean.class,repo.toString(),repo));
  if(!generic)return true;
  if(version==null&&index!=null){var versions=db.queryForList("SELECT dataset_version_id FROM mm_index_version WHERE dataset_id=? AND index_version_id=?",UUID.class,dataset,index);if(versions.size()!=1)return false;version=versions.get(0);}
  if(version==null)return false;
  var commits=db.queryForList("SELECT raw_commit_id FROM mm_dataset_version WHERE dataset_id=? AND version_id=?",UUID.class,dataset,version);if(commits.size()!=1)return false;
  try{var entries=manifests.getManifest(repo,commits.get(0)).getFiles();load(entries.stream().map(CommitManifest.File::getFileId).toList());
   for(var entry:entries){UUID parent=origin(fileRow(entry.getFileId()),this);if(parent!=null&&!execution(parent,reader,readerOrg,sharing,seen,depth+1))return false;}return true;
  }catch(ResponseStatusException denied){if(denied.getStatusCode().value()==404)return false;throw denied;}catch(Exception unavailable){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"SOURCE_SNAPSHOT_AUTHORITY_UNAVAILABLE",unavailable);}
  }
  boolean execution(UUID run,UUID reader,UUID readerOrg,boolean sharing,Set<UUID> seen,int depth){
   return execution(run,reader,readerOrg,sharing,seen,depth,consumptionOperations,consumptionPurposes);
  }
  boolean execution(UUID run,UUID reader,UUID readerOrg,boolean sharing,Set<UUID> seen,int depth,Set<String> activeOperations,Set<String> activePurposes){
   if(depth>64||!seen.add(run))return false;
   String cache=run+":"+reader+":"+readerOrg+":"+sharing+":"+new TreeSet<>(activeOperations)+":"+new TreeSet<>(activePurposes);
   try{
    if(current.containsKey(cache))return current.get(cache);
    var n=snapshot(run,"UNIQUE_COMMITTED_UPLOAD_RECONSTRUCTION");UUID actor=id(n,"actorUserId"),org=id(n,"actorOrgId");boolean onward=sharing||!actor.equals(reader)||!org.equals(readerOrg);
    for(JsonNode binding:n.path("bindings")){
     var required=new TreeSet<String>(activeOperations);for(JsonNode v:binding.path("requiredOperations"))required.add(v.asText());required.add(binding.path("operation").asText());if(onward)required.add("dataset.share.manage");
     var purposes=new TreeSet<String>(activePurposes);purposes.add(binding.path("purpose").asText());
     if("DATASET".equals(binding.path("kind").asText())){
      var original=new AuthorizationService.GrantBinding(id(binding,"grantId"),binding.path("grantVersion").asLong());
      // Every use must be covered by this same frozen complete authority.
      for(String purpose:purposes)if(!auth.persistedSourceGrantCurrent(actor,org,id(binding,"datasetId"),binding.hasNonNull("versionId")?id(binding,"versionId"):null,binding.hasNonNull("indexVersionId")?id(binding,"indexVersionId"):null,required,purpose,original))return false;
      if(binding.path("readIndexBlob").asBoolean()){
       var nested=new Scan();nested.consumptionOperations=Set.copyOf(required);nested.consumptionPurposes=Set.copyOf(purposes);
       if(!nested.rawSnapshot(id(binding,"datasetId"),binding.hasNonNull("versionId")?id(binding,"versionId"):null,binding.hasNonNull("indexVersionId")?id(binding,"indexVersionId"):null,reader,readerOrg,onward,seen,depth+1))return false;
      }
     }else if("LEGACY_PRIVATE_ARTIFACT_ACL".equals(binding.path("kind").asText())){
      if(purposes.stream().anyMatch(purpose->!purpose.equals(binding.path("purpose").asText())))return false;
      AuthorizationService.Actor a;try{a=auth.actorFor(actor);}catch(ResponseStatusException denied){return false;}
      if(!a.orgId().equals(org)||!a.operations().containsAll(required)||!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM artifact_repo r JOIN ds_artifact_acl_generation g ON g.repo_id=r.repo_id AND NOT g.deleted WHERE r.repo_id=? AND r.org_id=? AND r.owner_user_id=? AND g.version=?)",Boolean.class,id(binding,"repoId"),org,actor,binding.path("aclGeneration").asLong())))return false;
     }else return false;
    }
    var ids=new ArrayList<UUID>();for(JsonNode ref:n.path("files"))ids.add(id(ref,"fileId"));load(ids);
    for(JsonNode ref:n.path("files")){
     var f=fileRow(id(ref,"fileId"));if(!Objects.equals(f.get("file_key"),ref.path("objectKey").asText())||!Objects.equals(f.get("sha256"),ref.path("sha256").asText()))return false;
     UUID parent=origin(f,this);if(parent!=null){
      JsonNode selected=null;for(JsonNode binding:n.path("bindings"))if(binding.path("repoId").asText().equals(ref.path("repoId").asText())&&binding.path("commitId").asText().equals(ref.path("commitId").asText())){if(selected!=null)return false;selected=binding;}
      if(selected==null)return false;
      var operations=new TreeSet<String>(activeOperations);for(JsonNode v:selected.path("requiredOperations"))operations.add(v.asText());operations.add(selected.path("operation").asText());
      var purposes=new TreeSet<String>(activePurposes);purposes.add(selected.path("purpose").asText());
      if(!execution(parent,reader,readerOrg,onward,seen,depth+1,operations,purposes))return false;
     }
    }
    current.put(cache,true);return true;
   }finally{seen.remove(run);}
  }

 }
 public boolean executionInputsCurrent(UUID run,AuthorizationService.Actor actor){try{return new Scan().execution(run,actor.userId(),actor.orgId(),false,new HashSet<>(),0);}catch(ResponseStatusException denied){if(denied.getStatusCode().value()==404)return false;throw denied;}}
 private Scan currentUse(){
  var scan=new Scan();String operation=auth.currentOperation("data.raw.download");
  if(Set.of("data.preview","data.search","data.qa","data.raw.download","data.view.export","dataset.index.build","dataset.quality.run","dataset.quality.read").contains(operation))scan.consumptionOperations=Set.of(operation);
  scan.consumptionPurposes=Set.of(auth.currentPurpose("GENERAL_READ"));return scan;
 }
 private boolean visible(UUID fileId,AuthorizationService.Actor reader,boolean register,Scan scan){
  var f=scan.fileRow(fileId);UUID run=origin(f,scan);if(run==null)return true;
  var requestedOperations=new TreeSet<String>(scan.consumptionOperations);var requestedPurposes=new TreeSet<String>(scan.consumptionPurposes);
  if(scan.indexRepository((UUID)f.get("repo_id"))){requestedOperations.add("dataset.index.build");requestedPurposes.clear();requestedPurposes.add("VIEW_BUILD");}
  Set<String> operations=Set.copyOf(requestedOperations),purposes=Set.copyOf(requestedPurposes);
  if(!scan.execution(run,reader.userId(),reader.orgId(),false,new HashSet<>(),0,operations,purposes))return false;
  if(register)auth.rememberSourceConstraint("generic:"+run+":"+reader.userId()+":"+reader.orgId()+":"+new TreeSet<>(operations)+":"+new TreeSet<>(purposes),()->{
   var current=auth.actor();if(!current.userId().equals(reader.userId())||!current.orgId().equals(reader.orgId())||!new Scan().execution(run,current.userId(),current.orgId(),false,new HashSet<>(),0,operations,purposes)){deny();}
  });return true;
 }
 public void requireSelectedInputs(UUID repo,UUID commit,JsonNode paths){requireSelectedInputs(repo,commit,paths,currentUse());}
 public void requireSelectedInputs(UUID repo,UUID commit,JsonNode paths,Set<String> operations,String purpose){
  var scan=new Scan();scan.consumptionOperations=Set.copyOf(operations);scan.consumptionPurposes=Set.of(purpose);requireSelectedInputs(repo,commit,paths,scan);
 }
 private void requireSelectedInputs(UUID repo,UUID commit,JsonNode paths,Scan scan){
  if(paths==null||!paths.isArray())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INPUT_PATH_ARRAY_REQUIRED");
  try{
   var entries=manifests.getManifest(repo,commit).getFiles();var selected=selection(paths,entries.stream().map(CommitManifest.File::getPath).toList());
   var files=entries.stream().filter(v->selected.test(v.getPath())).toList();var reader=auth.actor();scan.load(files.stream().map(CommitManifest.File::getFileId).toList());
   for(var entry:files)if(!visible(entry.getFileId(),reader,true,scan)){deny();}
  }catch(ResponseStatusException denied){throw denied;}catch(Exception unavailable){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"SOURCE_INPUT_PREFLIGHT_UNAVAILABLE",unavailable);}
 }
 @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<QualityJobAuthority> qualityJobs;
 @org.springframework.beans.factory.annotation.Autowired
 private org.springframework.beans.factory.ObjectProvider<com.fwdrobo.sirius.dataspace.ControlledDeliveryReadScope> deliveryReads;
 public void requireFile(UUID fileId){if(deliveryReads.getObject().active()){deliveryReads.getObject().requireFile(fileId);return;}if(qualityJobs.getObject().active()){qualityJobs.getObject().requireFile(fileId);return;}if(!visible(fileId,auth.actor(),true,currentUse())){deny();}}

 private boolean completeInputRequired(AuthorizationService.Actor actor){
  if(actor.serviceId()!=null)return true;
  if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs))return false;
  var request=attrs.getRequest();
  return request.getAttribute("ds.jobIoClaims") instanceof Claims
   ||request.getAttribute(IndexCacheScopeService.ATTRIBUTE) instanceof IndexCacheScopeService.Scope
   ||request.getAttribute(WorkerEvidenceScopeService.ATTRIBUTE) instanceof WorkerEvidenceScopeService.Scope
   ||request.getAttribute(ServiceScopeService.ATTRIBUTE) instanceof ServiceScopeService.Scope;
 }
 public List<CommitManifest.File> visibleFiles(List<CommitManifest.File> entries){
  if(deliveryReads.getObject().active()){for(var entry:entries)deliveryReads.getObject().requireFile(entry.getFileId());return List.copyOf(entries);}
  if(qualityJobs.getObject().active()){for(var entry:entries)qualityJobs.getObject().requireFile(entry.getFileId());return List.copyOf(entries);}
  var scan=currentUse();var actor=auth.actor();boolean mandatory=completeInputRequired(actor)||Set.of("data.search","data.qa","data.view.export","dataset.index.build").contains(auth.currentOperation("data.catalog.read"));var out=new ArrayList<CommitManifest.File>();
  scan.load(entries.stream().map(CommitManifest.File::getFileId).toList());
  for(var entry:entries)try{
   if(visible(entry.getFileId(),actor,true,scan))out.add(entry);
   else if(mandatory){deny();}
  }catch(ResponseStatusException denied){if(mandatory||denied.getStatusCode().value()!=404)throw denied;}
  return out;
 }

 public boolean datasetInputsCurrent(UUID dataset,UUID version,UUID index,Set<String> operations,String purpose,AuthorizationService.Actor reader){
  // An output-write custody check has no read snapshot; its native input leg
  // carries the actual frozen version. Consumption must resolve that version.
  if(version==null&&index==null&&operations.equals(Set.of("dataset.index.build")))return true;
  var scan=new Scan();var required=new TreeSet<String>(operations);var uses=new TreeSet<String>();uses.add(purpose);
  if(index!=null){required.add("dataset.index.build");uses.add("VIEW_BUILD");}
  scan.consumptionOperations=Set.copyOf(required);scan.consumptionPurposes=Set.copyOf(uses);
  return scan.rawSnapshot(dataset,version,index,reader.userId(),reader.orgId(),false,new HashSet<>(),0);
 }
 public boolean legacyDeliveryInputsCurrent(UUID dataset,UUID version,UUID recipientOrg){
  if(recipientOrg==null)return false;
  var scan=new Scan();
  scan.consumptionOperations=Set.of("data.raw.download","data.view.export");
  scan.consumptionPurposes=Set.of("CONTROLLED_DELIVERY");
  // The recipient is the enterprise named by the leased historical contract.
  // No individual caller can supply an identity or bypass onward-sharing checks.
  return scan.rawSnapshot(dataset,version,null,null,recipientOrg,false,new HashSet<>(),0);
 }
 public void requirePublishable(UUID repo,UUID commit){try{var scan=currentUse();var actor=auth.actor();var entries=manifests.getManifest(repo,commit).getFiles();scan.load(entries.stream().map(CommitManifest.File::getFileId).toList());for(var entry:entries)if(!visible(entry.getFileId(),actor,true,scan)){deny();}}catch(ResponseStatusException denied){throw denied;}catch(Exception unavailable){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"FILE_SOURCE_AUTHORITY_UNAVAILABLE",unavailable);}}
}
