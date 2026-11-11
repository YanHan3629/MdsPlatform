package com.fwdrobo.sirius.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Metadata, logs and cancellation require the task's entire current resource scope. */
@Service
public class JobResourceAuthority {
 @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy private ControlledViewPublicationAuthority publications;
 private final JdbcTemplate jdbc;private final AuthorizationService auth;
 @org.springframework.beans.factory.annotation.Autowired private JobExecutionDefinitionService definitions;
 @org.springframework.beans.factory.annotation.Autowired private ControlledViewPreviewAuthority previews;
 public JobResourceAuthority(JdbcTemplate jdbc,AuthorizationService auth){this.jdbc=jdbc;this.auth=auth;}
 private static void deny(){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"JOB_SCOPE_NOT_AVAILABLE");}
 private boolean exists(String sql,Object... args){return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args));}
 public void require(UUID job,UUID run,String operation){
  var actor=auth.requireOperation(operation);
  if(run!=null&&!exists("SELECT EXISTS(SELECT 1 FROM job_run WHERE job_id=? AND run_id=? AND org_id=?)",job,run,actor.orgId()))deny();
  if(!(run==null?canInspect(actor,job,operation):canInspectRun(actor,job,run,operation)))deny();
 }
 public boolean canInspectRun(UUID job,UUID run,String operation){
  return canInspectRun(auth.requireOperation(operation),job,run,operation);
 }
 public boolean canInspectRun(AuthorizationService.Actor actor,UUID job,UUID run,String operation){
  auth.requireOperation(actor,operation);
  if(!exists("SELECT EXISTS(SELECT 1 FROM job_run WHERE job_id=? AND run_id=? AND org_id=?)",job,run,actor.orgId()))return false;
  if(publications.isManagedJob(job))return publications.canInspect(actor,job,run,operation);
  if(previews.isManagedJob(job))return previews.canInspect(actor,job,run,operation);
  if(!exists("SELECT EXISTS(SELECT 1 FROM ds_job_execution_definition WHERE run_id=?)",run))return canInspect(actor,job,operation);
  var body=definitions.read(run).body();
  if(!job.toString().equals(body.path("ioScope").path("jobId").asText()))return false;
  if("INDEX_BUILD".equals(body.path("ioScope").path("jobKind").asText()))return canInspect(actor,job,operation);
  if(body.path("declaredInputs").isEmpty()||body.path("declaredOutputs").isEmpty())return false;
  for(var input:body.path("declaredInputs"))
   if(!canUseRepo(actor,UUID.fromString(input.path("repoId").asText()),UUID.fromString(input.path("commitId").asText()),operation,false))return false;
  for(var output:body.path("declaredOutputs"))
   if(!canUseRepo(actor,UUID.fromString(output.path("repoId").asText()),output.hasNonNull("commitId")?UUID.fromString(output.path("commitId").asText()):null,operation,true))return false;
  return true;
 }
 public boolean canInspect(AuthorizationService.Actor actor,UUID job,String operation){
  auth.requireOperation(actor,operation);
  if(publications.isManagedJob(job))return publications.canInspect(actor,job,null,operation);
  if(previews.isManagedJob(job))return previews.canInspect(actor,job,null,operation);
  var rows=jdbc.queryForList("SELECT job_id,params FROM job WHERE job_id=? AND org_id=?",job,actor.orgId());
  if(rows.size()!=1)return false;
  var inputs=jdbc.queryForList("SELECT input_repo_id FROM job_input WHERE job_id=? ORDER BY input_repo_id",UUID.class,job);
  var outputs=jdbc.queryForList("SELECT output_repo_id FROM job_output WHERE job_id=? ORDER BY output_repo_id",UUID.class,job);
  if(inputs.isEmpty()||outputs.isEmpty())return false;
  var indexed=jdbc.queryForList("""
   SELECT i.dataset_id,i.dataset_version_id,i.index_version_id,i.index_status,i.index_repo_id,
          d.raw_repo_id,v.raw_commit_id
   FROM mm_index_version i JOIN mm_dataset d ON d.dataset_id=i.dataset_id
   JOIN mm_dataset_version v ON v.dataset_id=i.dataset_id AND v.version_id=i.dataset_version_id
   WHERE i.build_job_id=?
   """,job);
  if(!indexed.isEmpty()){
   if(indexed.size()!=1)return false;
   var i=indexed.get(0);
   if(inputs.size()!=1||outputs.size()!=1||!inputs.get(0).equals(i.get("raw_repo_id"))||!outputs.get(0).equals(i.get("index_repo_id")))return false;
   UUID d=(UUID)i.get("dataset_id");
   // Current custody can see its build queue. A frozen grant sees only its
   // exact READY index, never another candidate for the same raw version.
   if(auth.canUseDataset(actor,d,null,null,operation,"VIEW_BUILD"))return true;
   return "READY".equals(i.get("index_status"))&&auth.canUseDataset(actor,d,(UUID)i.get("dataset_version_id"),
      (UUID)i.get("index_version_id"),operation,"GENERAL_READ");
  }
  String pinnedRepo=jdbc.queryForObject("SELECT params#>>'{env,MDS_INPUT_REPO_ID}' FROM job WHERE job_id=?",String.class,job);
  String pinnedCommit=jdbc.queryForObject("SELECT params#>>'{env,MDS_INPUT_COMMIT_ID}' FROM job WHERE job_id=?",String.class,job);
  UUID pinRepo=null,pinCommit=null;
  if(pinnedRepo!=null||pinnedCommit!=null){
   try{pinRepo=UUID.fromString(pinnedRepo);pinCommit=UUID.fromString(pinnedCommit);}
   catch(RuntimeException invalid){return false;}
   if(inputs.size()!=1||!inputs.contains(pinRepo))return false;
  }
  for(UUID repo:inputs){UUID declared=definitions.declaredInputPin(job,repo);if(declared!=null&&Objects.equals(repo,pinRepo)&&!declared.equals(pinCommit))return false;if(!canUseRepo(actor,repo,declared!=null?declared:Objects.equals(repo,pinRepo)?pinCommit:null,operation,false))return false;}
  for(UUID repo:outputs)if(!canUseRepo(actor,repo,null,operation,true))return false;
  return true;
 }
 private boolean canUseRepo(AuthorizationService.Actor actor,UUID repo,UUID commit,String operation,boolean output){
  var datasets=jdbc.queryForList("SELECT dataset_id,raw_repo_id,index_repo_id,org_id FROM mm_dataset WHERE raw_repo_id=? OR index_repo_id=?",repo,repo);
  if(datasets.isEmpty()){
   // Existing private artifact ACL; neither tenant membership nor job creation
   // is sufficient to read another person's unlinked private repository.
   return exists("SELECT EXISTS(SELECT 1 FROM artifact_repo WHERE repo_id=? AND org_id=? AND owner_user_id=?)",repo,actor.orgId(),actor.userId());
  }
  if(datasets.size()!=1)return false;
  var d=datasets.get(0);UUID dataset=(UUID)d.get("dataset_id");
  if(repo.equals(d.get("index_repo_id"))){
   if(!actor.operations().contains("dataset.index.build")||!actor.orgId().equals(d.get("org_id")))return false;
   var required=new TreeSet<String>();required.add(operation);required.add("dataset.index.build");
   if(output||commit==null)return auth.canUseDataset(actor,dataset,null,null,required,"VIEW_BUILD");
   var indexes=jdbc.queryForList("SELECT dataset_version_id,index_version_id FROM mm_index_version WHERE dataset_id=? AND index_commit_id=? AND index_status='READY'",dataset,commit);
   return indexes.size()==1&&auth.canUseDataset(actor,dataset,(UUID)indexes.get(0).get("dataset_version_id"),(UUID)indexes.get(0).get("index_version_id"),required,"VIEW_BUILD");
  }
  if(output)return auth.canUseDataset(actor,dataset,null,null,operation,"GENERAL_READ");
  UUID version=null;
  if(commit!=null){
   var versions=jdbc.queryForList("SELECT version_id FROM mm_dataset_version WHERE dataset_id=? AND raw_commit_id=?",UUID.class,dataset,commit);
   if(versions.size()!=1)return false;version=versions.get(0);
  }
  return auth.canUseDataset(actor,dataset,version,null,operation,"GENERAL_READ");
 }
 public List<UUID> visibleRunIds(UUID job,String dockerStatus,String containerStatus,String containerId,String containerName){
  var actor=auth.requireOperation("job.read");
  var rows=jdbc.queryForList("""
   SELECT run_id FROM job_run WHERE job_id=? AND org_id=?
    AND (?::text IS NULL OR docker_status=?)
    AND (?::text IS NULL OR container_status::text=?)
    AND (?::text IS NULL OR container_id=?)
    AND (?::text IS NULL OR container_name=?)
   ORDER BY created_at DESC,run_id
   """,UUID.class,job,actor.orgId(),blank(dockerStatus),blank(dockerStatus),blank(containerStatus),blank(containerStatus),
    blank(containerId),blank(containerId),blank(containerName),blank(containerName));
  return rows.stream().filter(run->canInspectRun(actor,job,run,"job.read")).toList();
 }
 public List<UUID> visibleIds(String name,String category,String image,String operation){
  var actor=auth.requireOperation(operation);
  var ids=jdbc.queryForList("""
    SELECT job_id FROM job WHERE org_id=?
      AND (?::text IS NULL OR job_name=?)
      AND (?::text IS NULL OR job_category::text=?)
      AND (?::text IS NULL OR image=?)
    ORDER BY created_at DESC,job_id
    """,UUID.class,actor.orgId(),blank(name),blank(name),blank(category),blank(category),blank(image),blank(image));
  var visible=new ArrayList<UUID>();
  for(UUID id:ids)if(canInspect(actor,id,operation))visible.add(id);
  return List.copyOf(visible);
 }
 private static String blank(String value){return value==null||value.isBlank()?null:value;}
}
