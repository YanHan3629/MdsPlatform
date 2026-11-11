package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.databind.*;
import com.fwdrobo.sirius.dto.file.CommitManifest;
import com.fwdrobo.sirius.service.ManifestService;
import com.fwdrobo.sirius.util.PathUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Read-only reference projection. It neither authorizes reads nor changes stored provenance. */
@Service
public class IndexFileImpactProjection {
 @FunctionalInterface interface Reader { CommitManifest read(UUID repo,UUID commit)throws Exception; }
 public record Snapshot(String edgesJson,String statesJson){}
 private record Scope(UUID repo,UUID commit){}
 private record Resolved(Set<UUID> parents,String hash,boolean incomplete){}
 private final JdbcTemplate db;private final ObjectMapper json;private final Reader reader;
 private final ExecutorService reads=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),work->{var thread=new Thread(work,"file-impact-manifest");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
 @Autowired public IndexFileImpactProjection(JdbcTemplate db,ObjectMapper json,ManifestService manifests){this(db,json,manifests::getManifest);}
 IndexFileImpactProjection(JdbcTemplate db,ObjectMapper json,Reader reader){this.db=db;this.json=json;this.reader=reader;}
 private static UUID id(Object value){return value==null?null:value instanceof UUID u?u:UUID.fromString(value.toString());}
 private static String str(Object value){return Objects.toString(value,"").trim();}
 private JsonNode parse(Object value){try{return value==null?json.createArrayNode():json.readTree(value.toString());}catch(Exception bad){throw new IllegalStateException("FILE_IMPACT_METADATA_INVALID",bad);}}
 private JsonNode sorted(JsonNode n){
  if(n.isObject()){var out=json.createObjectNode();var names=new TreeSet<String>();n.fieldNames().forEachRemaining(names::add);for(String name:names)out.set(name,sorted(n.get(name)));return out;}
  if(n.isArray()){var out=json.createArrayNode();n.forEach(value->out.add(sorted(value)));return out;}return n;
 }
 private String encode(Object value){try{return json.writeValueAsString(sorted(json.valueToTree(value)));}catch(Exception bad){throw new IllegalStateException("FILE_IMPACT_ENCODING_FAILED",bad);}}
 private String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception bad){throw new IllegalStateException("FILE_IMPACT_HASH_FAILED",bad);}}
 private static String key(UUID repo,UUID commit){return repo+"|"+commit;}
 private static void edge(Set<String> edges,UUID parent,UUID child){if(parent!=null&&child!=null&&!parent.equals(child))edges.add(parent+"|"+child);}
 private static Set<UUID> ordered(){return new TreeSet<>(Comparator.comparing(UUID::toString));}
 private final class State {
  final UUID run;final String kind;final UUID actor;final UUID grant;final Long grantVersion;final boolean legacy;
  final Set<String> proofs=new TreeSet<>(),unresolved=new TreeSet<>();
  State(UUID run,String kind,UUID actor,UUID grant,Long grantVersion,boolean legacy){this.run=run;this.kind=kind;this.actor=actor;this.grant=grant;this.grantVersion=grantVersion;this.legacy=legacy;}
  void add(Object proof,boolean incomplete){String value=hash(proof);proofs.add(value);if(incomplete)unresolved.add(value);}
  Map<String,Object> output(){
   var result=new LinkedHashMap<String,Object>();result.put("run_id",run);result.put("source_kind",kind);result.put("actor_user_id",actor);result.put("grant_id",grant);result.put("grant_version",grantVersion);
   result.put("projection_sha256",hash(proofs));result.put("unresolved_count",unresolved.size());result.put("legacy_origin_missing",legacy);return result;
  }
 }
 private final class Scan {
  final long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
  final Map<String,Map<String,Object>> origins=new HashMap<>();
  final Map<UUID,Set<UUID>> producers=new HashMap<>(),uncaptured=new HashMap<>();
  final Map<Scope,Resolved> cache=new HashMap<>();
  Set<UUID> parents(UUID repo){var result=ordered();result.addAll(producers.getOrDefault(repo,Set.of()));result.addAll(uncaptured.getOrDefault(repo,Set.of()));return result;}
  Resolved fallback(Scope scope,Object header,String code){var parents=parents(scope.repo());return new Resolved(parents,hash(List.of(scope,header,code,parents)),true);}
  Resolved read(UUID repo,UUID commit){return cache.computeIfAbsent(new Scope(repo,commit),this::load);}
  Resolved load(Scope scope){
   var headers=db.queryForList("SELECT repo_id,commit_id,commit_status::text,manifest_sha256 FROM artifact_commit WHERE repo_id=? AND commit_id=?",scope.repo(),scope.commit());
   if(headers.size()!=1)return fallback(scope,headers,"SOURCE_SNAPSHOT_NOT_AVAILABLE");
   var header=headers.get(0);
   if(!"PUBLISHED".equals(str(header.get("commit_status"))))return fallback(scope,header,"SOURCE_SNAPSHOT_NOT_PUBLISHED");
   if(parents(scope.repo()).isEmpty())return new Resolved(Set.of(),hash(List.of(scope,header,"NO_RECORDED_GENERIC_OUTPUTS")),false);
   CommitManifest manifest;Future<CommitManifest> pending=null;
   try{
    long remaining=deadline-System.nanoTime();if(remaining<=0)return fallback(scope,header,"SOURCE_MANIFEST_LOOKUP_BUDGET_EXHAUSTED");
    pending=reads.submit(()->reader.read(scope.repo(),scope.commit()));
    manifest=pending.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(750)),TimeUnit.NANOSECONDS);
   }catch(Exception unavailable){if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();return fallback(scope,header,"SOURCE_MANIFEST_UNAVAILABLE");}
   finally{if(pending!=null&&!pending.isDone())pending.cancel(true);}
   if(manifest==null||!scope.repo().equals(manifest.getArtifactId())||!scope.commit().equals(manifest.getCommitId())||manifest.getFiles()==null)return fallback(scope,header,"SOURCE_MANIFEST_IDENTITY_INVALID");
   var ids=new LinkedHashSet<UUID>();var paths=new HashSet<String>();
   for(var entry:manifest.getFiles()){
    if(entry.getFileId()==null||entry.getPath()==null||!ids.add(entry.getFileId())||!paths.add(PathUtils.normalizePath(entry.getPath())))return fallback(scope,header,"SOURCE_MANIFEST_FILES_INVALID");
   }
   var metadata=new HashMap<UUID,Map<String,Object>>();var list=new ArrayList<>(ids);
   for(int offset=0;offset<list.size();offset+=500){
    var part=list.subList(offset,Math.min(offset+500,list.size()));String marks=String.join(",",Collections.nCopies(part.size(),"?"));
    for(var row:db.queryForList("SELECT f.file_id,f.logical_path,f.file_key,btrim(f.sha256) AS file_sha256,c.repo_id FROM artifact_file f JOIN artifact_commit c ON c.commit_id=f.commit_id WHERE f.file_id IN ("+marks+")",part.toArray()))metadata.put(id(row.get("file_id")),row);
   }
   if(metadata.size()!=ids.size())return fallback(scope,header,"SOURCE_FILE_METADATA_MISSING");
   var parents=ordered();var proofs=new TreeMap<String,Object>();boolean uncertain=false;
   for(var entry:manifest.getFiles()){
    var file=metadata.get(entry.getFileId());String path=PathUtils.normalizePath(entry.getPath()),sha=str(file.get("file_sha256")),object=str(file.get("file_key"));
    // An inherited file keeps its original commit ID. Match its ID and repository, never its commit to the new snapshot.
    if(!scope.repo().equals(id(file.get("repo_id")))||!path.equals(PathUtils.normalizePath(str(file.get("logical_path"))))||object.isBlank()||!sha.matches("[a-fA-F0-9]{64}"))return fallback(scope,header,"SOURCE_FILE_IDENTITY_INVALID");
    var origin=origins.get(object);UUID producer=null;
    if(origin!=null){
     if(!scope.repo().equals(id(origin.get("repo_id")))||!sha.equalsIgnoreCase(str(origin.get("file_sha256"))))return fallback(scope,header,"SOURCE_FILE_ORIGIN_IDENTITY_INVALID");
     producer=id(origin.get("run_id"));parents.add(producer);
    }else if(!uncaptured.getOrDefault(scope.repo(),Set.of()).isEmpty())uncertain=true;
    var proof=new ArrayList<Object>();Collections.addAll(proof,entry.getFileId(),object,sha,producer);proofs.put(path,proof);
   }
   if(uncertain)parents.addAll(uncaptured.get(scope.repo()));
   // Old unsealed manifests cannot support an exact forecast. Keep a conservative reference set instead.
   if(!str(header.get("manifest_sha256")).matches("[a-fA-F0-9]{64}"))return fallback(scope,header,"SOURCE_MANIFEST_NOT_SEALED");
   return new Resolved(parents,hash(List.of(scope,header,proofs,parents,uncertain)),uncertain);
  }
 }
 public Snapshot capture(Set<UUID> affectedUsers){
  var users=Set.copyOf(affectedUsers);if(users.isEmpty())return new Snapshot("[]","[]");
  var scan=new Scan();
  for(var row:db.queryForList("SELECT object_key,repo_id,file_sha256,run_id FROM ds_generic_file_origin")){
   scan.origins.put(str(row.get("object_key")),row);scan.producers.computeIfAbsent(id(row.get("repo_id")),x->ordered()).add(id(row.get("run_id")));
  }
  var generics=db.queryForList("""
   SELECT d.run_id,d.actor_user_id,COALESCE(o.snapshot->'bindings',d.definition->'bindings')::text AS bindings,
    d.definition->'bindings' AS root_bindings,o.snapshot->'files' AS source_files,
    d.definition->'declaredInputs' AS inputs,d.definition->'declaredOutputs' AS outputs,(o.run_id IS NULL) AS uncaptured
   FROM ds_job_execution_definition d LEFT JOIN ds_generic_execution_origin o ON o.run_id=d.run_id
   WHERE jsonb_exists(d.definition,'ioManifest') ORDER BY d.run_id
   """);
  for(var row:generics)if(Boolean.TRUE.equals(row.get("uncaptured"))){var outputs=parse(row.get("outputs"));if(outputs.isArray())for(var output:outputs)try{
   UUID repo=id(output.path("repoId").asText());scan.uncaptured.computeIfAbsent(repo,x->ordered()).add(id(row.get("run_id")));
  }catch(IllegalArgumentException malformed){/* The existing generic snapshot warning still applies. */}}
  var nativeRows=db.queryForList("""
   SELECT n.run_id,n.actor_user_id,n.grant_id,n.grant_version,n.dataset_id,n.version_id,n.index_version_id,
    n.input_repo_id,n.input_commit_id,n.output_repo_id,n.output_commit_id,n.previous_index_commit_id,false AS legacy_origin
   FROM ds_index_execution_origin n
   UNION ALL
   SELECT i.build_run_id,j.created_by,NULL::uuid,NULL::bigint,i.dataset_id,i.dataset_version_id,i.index_version_id,
    d.raw_repo_id,v.raw_commit_id,i.index_repo_id,i.index_commit_id,NULL::uuid,true
   FROM mm_index_version i JOIN job_run j ON j.run_id=i.build_run_id
   JOIN mm_dataset_version v ON v.version_id=i.dataset_version_id AND v.dataset_id=i.dataset_id
   JOIN mm_dataset d ON d.dataset_id=i.dataset_id
   WHERE NOT EXISTS(SELECT 1 FROM ds_index_execution_origin n WHERE n.run_id=i.build_run_id)
   ORDER BY run_id
   """);
  var indexes=db.queryForList("""
   SELECT i.index_version_id,i.dataset_id,i.dataset_version_id,i.index_repo_id,i.index_commit_id,i.build_run_id,
    d.raw_repo_id,v.raw_commit_id FROM mm_index_version i
   JOIN mm_dataset_version v ON v.version_id=i.dataset_version_id AND v.dataset_id=i.dataset_id
   JOIN mm_dataset d ON d.dataset_id=i.dataset_id ORDER BY i.index_version_id
   """);
  var nativeByRun=new HashMap<UUID,Map<String,Object>>();var nativeByOutput=new HashMap<String,List<Map<String,Object>>>();
  for(var row:nativeRows){nativeByRun.put(id(row.get("run_id")),row);nativeByOutput.computeIfAbsent(key(id(row.get("output_repo_id")),id(row.get("output_commit_id"))),x->new ArrayList<>()).add(row);}
  var datasetRepos=new HashMap<UUID,UUID>();for(var row:db.queryForList("SELECT dataset_id,raw_repo_id FROM mm_dataset"))datasetRepos.put(id(row.get("dataset_id")),id(row.get("raw_repo_id")));
  var userList=new ArrayList<>(users);String marks=String.join(",",Collections.nCopies(userList.size(),"?"));
  String grantClosure="""
   WITH RECURSIVE affected(grant_id,grant_version,path) AS (
    SELECT g.grant_id,g.version,ARRAY[g.grant_id] FROM ds_resource_grant g JOIN ds_human_grant_origin o ON o.grant_id=g.grant_id
    WHERE o.confirmed_by IN (%s) AND o.source_authority->>'kind'='ORIGINAL_PROVIDER_GRANT'
    UNION ALL SELECT g.grant_id,g.version,array_append(p.path,g.grant_id) FROM affected p
    JOIN ds_human_grant_origin o ON o.source_authority->>'kind'='ORIGINAL_PROVIDER_GRANT'
     AND o.source_authority->>'grantId'=p.grant_id::text AND o.source_authority->>'version'=p.grant_version::text
    JOIN ds_resource_grant g ON g.grant_id=o.grant_id WHERE cardinality(p.path)<33 AND NOT g.grant_id=ANY(p.path)
   ) SELECT DISTINCT grant_id,grant_version FROM affected
   """.formatted(marks);
  var grants=new HashSet<String>();for(var row:db.queryForList(grantClosure,userList.toArray()))grants.add(str(row.get("grant_id"))+"|"+str(row.get("grant_version")));
  // Find a repository-level superset first. Only related frozen manifests may consume the bounded I/O budget.
  var coarse=new TreeSet<String>();var roots=ordered();
  for(var row:generics){UUID run=id(row.get("run_id"));if(users.contains(id(row.get("actor_user_id"))))roots.add(run);
   var bindings=parse(row.get("root_bindings"));if(bindings.isArray())for(var b:bindings)if(grants.contains(b.path("grantId").asText()+"|"+b.path("grantVersion").asText()))roots.add(run);
   var refs=parse(row.get("source_files"));if(refs.isArray())for(var ref:refs){var parent=scan.origins.get(ref.path("objectKey").asText());if(parent!=null)edge(coarse,id(parent.get("run_id")),run);}
   if(Boolean.TRUE.equals(row.get("uncaptured"))){var inputs=parse(row.get("inputs"));if(inputs.isArray())for(var input:inputs)try{for(UUID parent:scan.parents(id(input.path("repoId").asText())))edge(coarse,parent,run);}catch(IllegalArgumentException malformed){}}
  }
  for(var row:nativeRows){UUID run=id(row.get("run_id"));if(users.contains(id(row.get("actor_user_id")))||grants.contains(str(row.get("grant_id"))+"|"+str(row.get("grant_version"))))roots.add(run);
   for(UUID parent:scan.parents(id(row.get("input_repo_id"))))edge(coarse,parent,run);
   if(row.get("previous_index_commit_id")!=null)for(var parent:nativeByOutput.getOrDefault(key(id(row.get("output_repo_id")),id(row.get("previous_index_commit_id"))),List.of()))edge(coarse,id(parent.get("run_id")),run);
  }
  for(var row:generics){var bindings=parse(row.get("bindings"));if(!bindings.isArray())continue;
   for(var b:bindings)if(b.path("readIndexBlob").asBoolean()&&!b.path("output").asBoolean())try{
    UUID run=id(row.get("run_id")),repo=id(b.path("repoId").asText()),commit=id(b.path("commitId").asText());
    for(var parent:nativeByOutput.getOrDefault(key(repo,commit),List.of()))edge(coarse,id(parent.get("run_id")),run);
    for(var index:indexes)if(repo.equals(id(index.get("index_repo_id")))&&commit.equals(id(index.get("index_commit_id"))))for(UUID parent:scan.parents(id(index.get("raw_repo_id"))))edge(coarse,parent,run);
    if(b.hasNonNull("datasetId")){UUID raw=datasetRepos.get(id(b.path("datasetId").asText()));if(raw!=null)for(UUID parent:scan.parents(raw))edge(coarse,parent,run);}
   }catch(IllegalArgumentException malformed){}
  }
  var children=new HashMap<UUID,Set<UUID>>();for(String value:coarse){String[] pair=value.split("\\|",2);children.computeIfAbsent(id(pair[0]),x->ordered()).add(id(pair[1]));}
  var relevant=ordered();var queue=new ArrayDeque<UUID>(roots);while(!queue.isEmpty()){UUID next=queue.removeFirst();if(relevant.add(next))queue.addAll(children.getOrDefault(next,Set.of()));}
  var states=new TreeMap<String,State>();var edges=new TreeSet<String>();
  for(var row:generics)if(relevant.contains(id(row.get("run_id")))&&Boolean.TRUE.equals(row.get("uncaptured"))){
   UUID run=id(row.get("run_id"));var b=parse(row.get("bindings"));boolean blob=false;if(b.isArray())for(var item:b)blob|=item.path("readIndexBlob").asBoolean()&&!item.path("output").asBoolean();
   var state=new State(run,blob?"INDEX_BLOB":"GENERIC",id(row.get("actor_user_id")),null,null,false);states.put(run.toString(),state);
   state.add(List.of("UNCAPTURED_GENERIC_INPUTS",str(row.get("inputs"))),true);
   var inputs=parse(row.get("inputs"));if(inputs.isArray())for(var input:inputs)try{for(UUID parent:scan.parents(id(input.path("repoId").asText())))edge(edges,parent,run);}catch(IllegalArgumentException malformed){}
  }
  for(var row:nativeRows){
   UUID run=id(row.get("run_id"));if(!relevant.contains(run))continue;
   Number version=(Number)row.get("grant_version");var state=new State(run,"NATIVE_INDEX",id(row.get("actor_user_id")),id(row.get("grant_id")),version==null?null:version.longValue(),Boolean.TRUE.equals(row.get("legacy_origin")));states.put(run.toString(),state);
   var scope=scan.read(id(row.get("input_repo_id")),id(row.get("input_commit_id")));for(UUID parent:scope.parents())edge(edges,parent,run);state.add(List.of(row,scope.hash()),scope.incomplete());
  }
  for(var row:nativeRows)if(row.get("previous_index_commit_id")!=null){
   UUID run=id(row.get("run_id"));if(!relevant.contains(run))continue;var candidates=nativeByOutput.getOrDefault(key(id(row.get("output_repo_id")),id(row.get("previous_index_commit_id"))),List.of());
   var recorded=new LinkedHashSet<UUID>();
   for(var index:indexes)if(Objects.equals(index.get("index_repo_id"),row.get("output_repo_id"))&&Objects.equals(index.get("index_commit_id"),row.get("previous_index_commit_id"))&&Objects.equals(index.get("dataset_id"),row.get("dataset_id"))&&index.get("build_run_id")!=null)recorded.add(id(index.get("build_run_id")));
   var previous=recorded.isEmpty()?candidates:candidates.stream().filter(parent->recorded.contains(id(parent.get("run_id")))).toList();
   for(var parent:previous)edge(edges,id(parent.get("run_id")),run);
   states.get(run.toString()).add(List.of("PREVIOUS_INDEX",str(row.get("previous_index_commit_id")),previous.stream().map(x->str(x.get("run_id"))).sorted().toList()),previous.isEmpty()||recorded.size()!=1);
   if(previous.isEmpty())for(UUID parent:scan.parents(id(row.get("input_repo_id"))))edge(edges,parent,run);
  }
  for(var row:generics){
   if(!relevant.contains(id(row.get("run_id"))))continue;
   var bindings=parse(row.get("bindings"));if(!bindings.isArray())continue;
   for(var binding:bindings)if(binding.path("readIndexBlob").asBoolean()&&!binding.path("output").asBoolean()){
    UUID run=id(row.get("run_id"));var state=states.computeIfAbsent(run.toString(),x->new State(run,"INDEX_BLOB",null,null,null,false));
    try{
     UUID repo=id(binding.path("repoId").asText()),commit=id(binding.path("commitId").asText());
     var physical=indexes.stream().filter(x->repo.equals(id(x.get("index_repo_id")))&&commit.equals(id(x.get("index_commit_id")))).toList();
     var matching=physical.stream().filter(x->matches(binding,"datasetId",x.get("dataset_id"))&&matches(binding,"versionId",x.get("dataset_version_id"))&&matches(binding,"indexVersionId",x.get("index_version_id"))).toList();
     boolean declared="DATASET".equals(binding.path("kind").asText())&&binding.hasNonNull("datasetId")&&(binding.hasNonNull("versionId")||binding.hasNonNull("indexVersionId"));
     boolean incomplete=!declared||matching.size()!=1;var chosen=matching.size()==1?matching:physical;
     for(var index:chosen){
      var scope=scan.read(id(index.get("raw_repo_id")),id(index.get("raw_commit_id")));for(UUID parent:scope.parents())edge(edges,parent,run);
      UUID producer=id(index.get("build_run_id"));boolean recorded=producer!=null&&nativeByRun.containsKey(producer);
      if(recorded)edge(edges,producer,run);else for(var parent:nativeByOutput.getOrDefault(key(repo,commit),List.of()))edge(edges,id(parent.get("run_id")),run);
      incomplete|=scope.incomplete()||!recorded;state.add(List.of(binding,index,scope.hash()),scope.incomplete()||!recorded);
     }
     if(chosen.isEmpty()){
      for(var parent:nativeByOutput.getOrDefault(key(repo,commit),List.of())){edge(edges,id(parent.get("run_id")),run);for(UUID source:scan.parents(id(parent.get("input_repo_id"))))edge(edges,source,run);}
      UUID raw=datasetRepos.get(id(binding.path("datasetId").asText()));if(raw!=null)for(UUID parent:scan.parents(raw))edge(edges,parent,run);
     }
     state.add(List.of(binding,"INDEX_BLOB_BINDING",chosen.size()),incomplete);
    }catch(IllegalArgumentException malformed){state.add(List.of(binding,"INDEX_BLOB_SCOPE_INVALID"),true);}
   }
  }
  var projectedEdges=new ArrayList<Map<String,String>>();for(String value:edges){String[] parts=value.split("\\|",2);projectedEdges.add(Map.of("parent_run_id",parts[0],"child_run_id",parts[1]));}
  return new Snapshot(encode(projectedEdges),encode(states.values().stream().map(State::output).toList()));
 }
 private boolean matches(JsonNode binding,String key,Object value){return !binding.hasNonNull(key)||str(value).equals(binding.path(key).asText());}
 @PreDestroy public void close(){reads.shutdownNow();}
}
