package com.fwdrobo.sirius.service;
import com.fwdrobo.sirius.security.AuthorizationService;
import com.fwdrobo.sirius.security.AuthorizationService.Actor;
import com.fwdrobo.sirius.util.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
/** Every business capability and its complete live resource scope are independent. */
@Service
public class DatasetAccessService {
 @org.springframework.beans.factory.annotation.Autowired
 private com.fwdrobo.sirius.security.ServiceScopeService serviceScopes;
 private com.fwdrobo.sirius.security.ServiceScopeService.Scope verifiedServiceScope(){
  if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs&&
     attrs.getRequest().getAttribute(com.fwdrobo.sirius.security.ServiceScopeService.ATTRIBUTE) instanceof com.fwdrobo.sirius.security.ServiceScopeService.Scope)
   return serviceScopes.current();
  return null;
 }
 @org.springframework.beans.factory.annotation.Autowired
 private com.fwdrobo.sirius.security.JobExecutionDefinitionService executionDefinitions;
 @org.springframework.beans.factory.annotation.Autowired
 private com.fwdrobo.sirius.security.IndexJobAuthority indexJobs;
 @org.springframework.beans.factory.annotation.Autowired private com.fwdrobo.sirius.security.ControlledViewPreviewAuthority previews;
 @org.springframework.beans.factory.annotation.Autowired @org.springframework.context.annotation.Lazy
 private com.fwdrobo.sirius.security.ControlledViewPublicationAuthority publications;
 private final JdbcTemplate jdbc;private final AuthorizationService auth;
 public DatasetAccessService(JdbcTemplate jdbc,AuthorizationService auth){this.jdbc=jdbc;this.auth=auth;}
 private static void fail(HttpStatus status,String code){throw new ResponseStatusException(status,code);}
 private boolean exists(String sql,Object... args){return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args));}
 public UUID actorOrg(){return auth.actor().orgId();}
 private String operation(){return auth.currentOperation("data.catalog.read");}
 private Set<String> actions(UUID dataset,String op){
  var result=new HashSet<String>();result.add(op);
  if(Set.of("delivery.package.download","delivery.execute").contains(op)){
   boolean view=exists("SELECT EXISTS(SELECT 1 FROM ds_controlled_view WHERE dataset_id=?)",dataset);
   result.add(view?"data.view.export":"data.raw.download");
   if(view&&exists("SELECT EXISTS(SELECT 1 FROM ds_controlled_view WHERE dataset_id=? AND COALESCE((public_scope_summary->>'hasOriginalByteCopies')::boolean,false))",dataset))result.add("data.raw.download");
  }
  return result;
 }
 public boolean ownsDataset(UUID dataset){
  Actor a=auth.actor();
  return exists("SELECT EXISTS(SELECT 1 FROM mm_dataset WHERE dataset_id=? AND org_id=?)",dataset,a.orgId())&&canReadVersion(dataset,null,null);
 }
 public void requireDatasetOwner(UUID dataset){requireDatasetWrite(dataset);}
 public void requireDatasetWrite(UUID dataset){
  Actor a=auth.actor();String op=auth.currentOperation("dataset.draft.manage");auth.requireOperation(a,op);
  if(publications.managedDataset(dataset)){
   if(publications.trustedDataset(dataset,null,null))return;
   var ready=publications.readyResource(dataset);
   if(ready==null||!a.orgId().equals(ready.get("actor_org_id"))||Set.of("dataset.create","dataset.upload","dataset.draft.manage","dataset.publish","dataset.index.build").contains(op))
    fail(HttpStatus.NOT_FOUND,"CONTROLLED_VIEW_IMMUTABLE_SNAPSHOT_OR_CURRENT_GRANT_REQUIRED");
   auth.requireDataset(a,dataset,(UUID)ready.get("version_id"),(UUID)ready.get("index_version_id"),actions(dataset,op),auth.currentPurpose("GENERAL_READ"));return;
  }
  if(!exists("SELECT EXISTS(SELECT 1 FROM mm_dataset WHERE dataset_id=? AND org_id=? AND status='ACTIVE')",dataset,a.orgId()))fail(HttpStatus.NOT_FOUND,"RESOURCE_SCOPE_NOT_AVAILABLE");
  auth.requireDataset(a,dataset,null,null,actions(dataset,op),auth.currentPurpose("GENERAL_READ"));
 }
 public AuthorizationService.OperationScope fixedOperation(String operation,String purpose){return auth.enterOperation(operation,purpose);}
 /** A full dataset controller may inspect its queued/candidate work; frozen grants inspect only their exact READY index. */
 public boolean canInspectIndexTask(UUID dataset,UUID version,UUID index){
  Actor actor=auth.actor();
  if(auth.canUseDataset(actor,dataset,null,null,"dataset.index.build","VIEW_BUILD"))return true;
  return version!=null&&index!=null&&auth.canUseDataset(actor,dataset,version,index,"dataset.index.build","VIEW_BUILD");
 }
 public boolean canDatasetOperation(UUID dataset,String operation,String purpose){
  if(publications.trustedDataset(dataset,null,null))return "dataset.index.build".equals(operation)&&"VIEW_BUILD".equals(purpose);
  return auth.canUseDataset(auth.actor(),dataset,null,null,operation,purpose);
 }
 /** A newly created dataset gets one explicit immutable-user custody grant, never an org-wide bypass. */
 public void establishCreatorCustody(UUID dataset){
  Actor a=auth.requireOperation("dataset.create");
  var owned=jdbc.queryForList("SELECT dataset_id FROM mm_dataset WHERE dataset_id=? AND owner_user_id=? AND org_id=? AND status='ACTIVE' FOR UPDATE",dataset,a.userId(),a.orgId());
  if(owned.size()!=1)fail(HttpStatus.NOT_FOUND,"CREATOR_CUSTODY_SCOPE_NOT_AVAILABLE");
  if(exists("SELECT EXISTS(SELECT 1 FROM ds_resource_grant WHERE dataset_id=?)",dataset))fail(HttpStatus.CONFLICT,"NEW_DATASET_ALREADY_HAS_RESOURCE_POLICY");
  UUID grant=UUID.randomUUID();
  jdbc.update("""
   INSERT INTO ds_resource_grant(grant_id,subject_kind,subject_id,grantee_org_id,owner_org_id,scope_kind,dataset_id,operation_codes,purpose_codes,created_by)
   SELECT ?,'USER',?,?,?,'DATASET_CUSTODY',?,array_agg(operation_code ORDER BY operation_code),
     ARRAY['GENERAL_READ','VIEW_BUILD','CONTROLLED_DELIVERY']::varchar[],?
   FROM ds_operation WHERE group_code NOT IN('identity','platform')
   """,grant,a.userId(),a.orgId(),a.orgId(),dataset,a.userId());
  jdbc.update("""
   INSERT INTO ds_authorization_audit(actor_user_id,actor_org_id,operation_code,target_kind,target_id,outcome,request_id,scope,reason_code)
   VALUES(?,?,'dataset.create','RESOURCE_GRANT',?,'SUCCESS',?,
     jsonb_build_object('datasetId',?::text,'subjectKind','USER','subjectId',?::text,'scopeKind','DATASET_CUSTODY'),'EXPLICIT_CREATOR_CUSTODY')
   """,a.userId(),a.orgId(),grant.toString(),UUID.randomUUID().toString(),dataset.toString(),a.userId().toString());
 }
 /** Publishing always checks its own operation, including calls nested inside upload handlers. */
 public AuthorizationService.OperationScope beginCommitPublication(UUID repo,UUID commit){
  boolean index=exists("SELECT EXISTS(SELECT 1 FROM mm_dataset WHERE index_repo_id=?)",repo);
  if(index&&verifiedJobIo()==null)fail(HttpStatus.FORBIDDEN,"SCOPED_INDEX_PUBLICATION_REQUIRED");
  var scope=auth.enterOperation(index?"dataset.index.build":"dataset.publish",index?"VIEW_BUILD":"GENERAL_READ");
  try{requireArtifact(repo,commit,true);return scope;}catch(RuntimeException denied){scope.close();throw denied;}
 }
 @org.springframework.beans.factory.annotation.Autowired
 private com.fwdrobo.sirius.security.IndexCacheScopeService cacheScopes;
 private com.fwdrobo.sirius.security.IndexCacheScopeService.Scope verifiedCacheScope(){
  if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs&&attrs.getRequest().getAttribute(
      com.fwdrobo.sirius.security.IndexCacheScopeService.ATTRIBUTE) instanceof com.fwdrobo.sirius.security.IndexCacheScopeService.Scope scope)return scope;
  return null;
 }
 public boolean canReadVersion(UUID dataset,UUID version,UUID index){
  var cache=verifiedCacheScope();
  if(cache!=null)return com.fwdrobo.sirius.security.IndexCacheScopeService.matches(cache,dataset,version,index);
  var service=verifiedServiceScope();
  if(service!=null)return com.fwdrobo.sirius.security.ServiceScopeService.matches(service,dataset,version,index);
  if(indexJobs.canReadCallbackMetadata(dataset,version,index))return true;
  if(publications.trustedDataset(dataset,version,index))return true;
  Actor a=auth.actor();String op=operation();Set<String> requested=actions(dataset,op);String purpose=auth.currentPurpose("GENERAL_READ");
  if(version!=null)return auth.canUseDataset(a,dataset,version,index,requested,purpose);
  if(index!=null)return false;
  if(auth.canUseDataset(a,dataset,null,null,requested,purpose))return true;
  for(UUID candidate:jdbc.queryForList("SELECT version_id FROM mm_dataset_version WHERE dataset_id=? ORDER BY created_at",UUID.class,dataset))
   if(auth.canUseDataset(a,dataset,candidate,null,requested,purpose))return true;
  return false;
 }
 public void requireDatasetRead(UUID dataset){
  auth.requireOperation(operation());
  if(!canReadVersion(dataset,null,null))fail(HttpStatus.NOT_FOUND,"RESOURCE_SCOPE_NOT_AVAILABLE");
 }
 public void requireVersionRead(UUID dataset,UUID version,UUID index){
  auth.requireOperation(operation());
  if(version==null||!canReadVersion(dataset,version,index))fail(HttpStatus.NOT_FOUND,"RESOURCE_SCOPE_NOT_AVAILABLE");
 }
 public List<UUID> readableVersionIds(UUID dataset){
  requireDatasetRead(dataset);
  return jdbc.queryForList("SELECT version_id FROM mm_dataset_version WHERE dataset_id=? ORDER BY created_at",UUID.class,dataset).stream().filter(v->canReadVersion(dataset,v,null)).toList();
 }
 private io.jsonwebtoken.Claims verifiedJobIo(){
  if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs&&attrs.getRequest().getAttribute("ds.jobIoClaims") instanceof io.jsonwebtoken.Claims claims)return claims;
  return null;
 }
 private void requireJobResource(UUID repo,UUID commit,boolean write,Actor actor){
  var claims=verifiedJobIo();Map<?,?> scope=claims.get("job_io_scope",Map.class);
  UUID inputRepo=UUID.fromString((String)scope.get("inputRepoId")),inputCommit=UUID.fromString((String)scope.get("inputCommitId"));
  UUID outputRepo=UUID.fromString((String)scope.get("outputRepoId")),outputCommit=UUID.fromString((String)scope.get("outputCommitId"));
  UUID run=UUID.fromString((String)scope.get("runId")),job=UUID.fromString((String)scope.get("jobId"));
  boolean within=executionDefinitions.frozenRepository(claims,repo,commit,write);
  if(!write&&scope.get("previousIndexCommitId") instanceof String previous) within|=repo.equals(outputRepo)&&Objects.equals(commit,UUID.fromString(previous));
  if(!within||!exists("SELECT EXISTS(SELECT 1 FROM job_run WHERE run_id=? AND job_id=? AND created_by=? AND org_id=? AND run_status IN('QUEUED','RUNNING'))",run,job,actor.userId(),actor.orgId()))fail(HttpStatus.FORBIDDEN,"JOB_IO_SCOPE_DENIED");
  if(!executionDefinitions.current(claims))fail(HttpStatus.FORBIDDEN,"EXECUTION_DEFINITION_NOT_AVAILABLE");
  if("INDEX_BUILD".equals(scope.get("jobKind"))){
   // Original run, session, grant generation and I/O tuple are all authoritative.
   indexJobs.revalidateRecordedOrigin(claims);return;
  }
  if(com.fwdrobo.sirius.security.ControlledViewPreviewAuthority.KIND.equals(scope.get("jobKind"))){previews.revalidateRecordedOrigin(claims);return;}
  var definition=executionDefinitions.read(run);
  for(var binding:definition.body().path("bindings")){
   if(!repo.toString().equals(binding.path("repoId").asText())||binding.path("output").asBoolean()!=write||
     !Objects.equals(commit==null?null:commit.toString(),binding.hasNonNull("commitId")?binding.path("commitId").asText():null))continue;
   // current() has rechecked the one original complete grant or private ACL
   // generation. Runtime-management capability alone never reads business bytes.
   auth.requireOperation(actor,binding.path("operation").asText());return;
  }
  fail(HttpStatus.FORBIDDEN,"EXECUTION_RESOURCE_BINDING_REQUIRED");
 }
 @org.springframework.beans.factory.annotation.Autowired private com.fwdrobo.sirius.security.QualityJobAuthority qualityJobs;
 @org.springframework.beans.factory.annotation.Autowired
 private org.springframework.beans.factory.ObjectProvider<com.fwdrobo.sirius.dataspace.ControlledDeliveryReadScope> deliveryReads;
 public void requireArtifact(UUID repo,UUID commit,boolean write){
  if(deliveryReads.getObject().active()){deliveryReads.getObject().requireArtifact(repo,commit,write);return;}
  if(qualityJobs.active()){qualityJobs.requireArtifact(repo,commit,write);return;}
  if(verifiedCacheScope()!=null){cacheScopes.requireArtifact(repo,commit,write);return;}
  if(verifiedServiceScope()!=null){serviceScopes.requireArtifact(repo,commit,write);return;}
  Actor a=auth.actor();
  if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs&&
      attrs.getRequest().getAttribute(com.fwdrobo.sirius.security.WorkerEvidenceScopeService.ATTRIBUTE) instanceof com.fwdrobo.sirius.security.WorkerEvidenceScopeService.Scope scoped){
   if(write||commit==null||!((repo.equals(scoped.rawRepo())&&commit.equals(scoped.rawCommit()))||
       (repo.equals(scoped.indexRepo())&&commit.equals(scoped.indexCommit()))))fail(HttpStatus.FORBIDDEN,"EVIDENCE_ARTIFACT_SCOPE_DENIED");
   auth.revalidate(scoped.lease());return;
  }
  if(verifiedJobIo()!=null){requireJobResource(repo,commit,write,a);return;}
  if(previews.managed(repo)){previews.requireHumanRepository(repo,commit,write);return;}
  if(publications.managedRepo(repo)){
   if(publications.trustedRepository(repo,commit,write))return;
   if(write||!exists("SELECT EXISTS(SELECT 1 FROM ds_controlled_view_publication p JOIN ds_controlled_view_publication_state s ON s.publication_id=p.publication_id WHERE (p.raw_repo_id=? OR p.index_repo_id=?) AND s.state='READY')",repo,repo))
    fail(HttpStatus.NOT_FOUND,"CONTROLLED_VIEW_READY_RESOURCE_GRANT_REQUIRED");
  }
  String op=auth.currentOperation(write?"dataset.draft.manage":"data.catalog.read");auth.requireOperation(a,op);
  var repos=jdbc.queryForList("SELECT org_id,owner_user_id,visibility FROM artifact_repo WHERE repo_id=?",repo);
  if(repos.size()!=1)fail(HttpStatus.NOT_FOUND,"RESOURCE_SCOPE_NOT_AVAILABLE");
  if(commit!=null&&!exists("SELECT EXISTS(SELECT 1 FROM artifact_commit WHERE repo_id=? AND commit_id=?)",repo,commit))fail(HttpStatus.NOT_FOUND,"RESOURCE_SCOPE_NOT_AVAILABLE");
  var linked=jdbc.queryForList("SELECT dataset_id,raw_repo_id,index_repo_id,org_id FROM mm_dataset WHERE raw_repo_id=? OR index_repo_id=?",repo,repo);
  if(!linked.isEmpty()){
   if(linked.size()!=1)fail(HttpStatus.NOT_FOUND,"AMBIGUOUS_RESOURCE_SCOPE");
   var dataset=linked.get(0);UUID d=(UUID)dataset.get("dataset_id");
   if(write){requireDatasetWrite(d);return;}
   if(repo.equals(dataset.get("index_repo_id"))){
    // Index blobs contain private vocabularies and rows. Ordinary file reads do
    // not inherit access merely because the user can preview or ask questions.
    auth.requireOperation(a,"dataset.index.build");
    if(!a.orgId().equals(dataset.get("org_id")))fail(HttpStatus.NOT_FOUND,"RESOURCE_SCOPE_NOT_AVAILABLE");
    var indexOperations=new HashSet<String>();indexOperations.add(op);indexOperations.add("dataset.index.build");
    UUID version=null,index=null;
    if(commit!=null){var indexed=jdbc.queryForList("SELECT dataset_version_id,index_version_id FROM mm_index_version WHERE dataset_id=? AND index_repo_id=? AND index_commit_id=? AND index_status='READY'",d,repo,commit);if(indexed.size()>1)fail(HttpStatus.NOT_FOUND,"AMBIGUOUS_RESOURCE_SCOPE");if(indexed.size()==1){version=(UUID)indexed.get(0).get("dataset_version_id");index=(UUID)indexed.get(0).get("index_version_id");}}
    if(!auth.canUseDataset(a,d,version,index,indexOperations,"VIEW_BUILD"))fail(HttpStatus.NOT_FOUND,"RESOURCE_SCOPE_NOT_AVAILABLE");
    return;
   }
   if(commit==null){requireDatasetRead(d);return;}
   var versions=jdbc.queryForList("SELECT version_id FROM mm_dataset_version WHERE dataset_id=? AND raw_commit_id=?",UUID.class,d,commit);
   if(versions.size()!=1)fail(HttpStatus.NOT_FOUND,"RESOURCE_SCOPE_NOT_AVAILABLE");
   requireVersionRead(d,versions.get(0),null);return;
  }
  var row=repos.get(0);
  boolean personallyOwned=a.userId().equals(row.get("owner_user_id"))&&a.orgId().equals(row.get("org_id"));
  if(personallyOwned)return;
  if(!write&&"PUBLIC".equals(row.get("visibility"))&&(commit==null||exists("SELECT EXISTS(SELECT 1 FROM artifact_commit WHERE repo_id=? AND commit_id=? AND commit_status='PUBLISHED')",repo,commit)))return;
  fail(HttpStatus.NOT_FOUND,"RESOURCE_SCOPE_NOT_AVAILABLE");
 }
 public boolean canReadArtifact(UUID repo, UUID commit) {
  try {
   requireArtifact(repo, commit, false);
   return true;
  } catch (ResponseStatusException denied) {
   if (denied.getStatusCode().equals(HttpStatus.FORBIDDEN) || denied.getStatusCode().equals(HttpStatus.NOT_FOUND)) {
    return false;
   }
   throw denied;
  }
 }

 public boolean isForeignArtifact(UUID repo) {
        return !exists("SELECT EXISTS(SELECT 1 FROM artifact_repo WHERE repo_id=? AND org_id=?)",repo,actorOrg());
    }
    public void requireBusinessFile(UUID repo,String path){
        if(previews.managed(repo))previews.requireOutputPath(repo,path);
        if(com.fwdrobo.sirius.util.DataSpaceBusinessFilePolicy.isDescription(path)&&exists("SELECT EXISTS(SELECT 1 FROM mm_dataset WHERE raw_repo_id=?)",repo))
            fail(HttpStatus.BAD_REQUEST,"说明文件不作为业务资产入库，请在数据空间外保留README/数据集说明.md");
    }
    /** Discover only scopes usable by this exact operation, including profiles without catalog rights. */
    public List<Map<String,Object>> scopesForOperation(String operation) {
        if(!Set.of("data.catalog.read","data.preview","data.search","data.qa","data.raw.download").contains(operation))
            fail(HttpStatus.BAD_REQUEST,"UNSUPPORTED_SCOPE_DISCOVERY_OPERATION");
        try(var fixed=auth.enterOperation(operation,"GENERAL_READ")){return readableReadyScopes(false);}
    }
    public List<Map<String,Object>> readableReadyScopes() {
        return readableReadyScopes(false);
    }
    public List<Map<String,Object>> readableReadyScopes(boolean includeOwnerCandidates) {
        UUID org=actorOrg();
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT d.dataset_id AS "datasetId",d.dataset_name AS "datasetName",d.modality_type AS "modalityType",
                   v.version_id AS "versionId",v.version_name AS "versionName",v.sample_count AS "sampleCount",
                   i.index_version_id AS "indexVersionId",i.index_status AS "indexStatus",d.org_id AS "ownerOrgId",
                   COALESCE(i.index_version_id=v.active_index_version_id,false) AS "isActiveIndex",
                   EXISTS(SELECT 1 FROM ds_product_version pv JOIN ds_product p ON p.product_id=pv.product_id
                       WHERE pv.dataset_id=d.dataset_id AND pv.version_id=v.version_id AND pv.index_version_id=i.index_version_id
                       AND p.provider_org_id=d.org_id) AS "hasProductScope"
            FROM mm_dataset d JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
            JOIN artifact_commit raw ON raw.commit_id=v.raw_commit_id AND raw.repo_id=d.raw_repo_id AND raw.commit_status='PUBLISHED'
            JOIN mm_index_version i ON i.dataset_id=d.dataset_id AND i.dataset_version_id=v.version_id AND i.index_status='READY'
            JOIN artifact_commit idx ON idx.commit_id=i.index_commit_id AND idx.repo_id=i.index_repo_id AND idx.commit_status='PUBLISHED'
            WHERE d.status='ACTIVE'
            ORDER BY d.dataset_name,v.created_at DESC,i.created_at DESC
            """);
        return rows.stream().filter(row->canReadVersion((UUID)row.get("datasetId"),(UUID)row.get("versionId"),(UUID)row.get("indexVersionId")))
            .peek(row->{row.put("isOwnerCandidate",org.equals(row.get("ownerOrgId")) && !Boolean.TRUE.equals(row.get("isActiveIndex"))
                && !Boolean.TRUE.equals(row.get("hasProductScope")));row.remove("hasProductScope");}).toList();
    }
}
