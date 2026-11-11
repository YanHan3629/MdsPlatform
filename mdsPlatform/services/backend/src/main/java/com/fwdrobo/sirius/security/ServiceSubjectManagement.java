package com.fwdrobo.sirius.security;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.time.*;
import java.sql.Timestamp;

/** Configuration authority grants no business content to its human operator. */
@Service
public class ServiceSubjectManagement {
 private final JdbcTemplate jdbc;private final AuthorizationService auth;private final com.fasterxml.jackson.databind.ObjectMapper json;
 public ServiceSubjectManagement(JdbcTemplate jdbc,AuthorizationService auth,com.fasterxml.jackson.databind.ObjectMapper json){this.jdbc=jdbc;this.auth=auth;this.json=json;}
 public record Create(UUID orgId,String name,String purpose,Instant validUntil){}
 public record Change(Long expectedVersion,String name,Boolean active,Instant validUntil,boolean close){}
 public record Bind(Long expectedVersion,UUID datasetId,Instant validUntil,UUID viewId){}
 public record Revoke(Long expectedVersion){}
 private static void fail(HttpStatus status,String code){throw new ResponseStatusException(status,code);}
 private AuthorizationService.Actor manager(){
  if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() instanceof org.springframework.web.context.request.ServletRequestAttributes attrs&&
      attrs.getRequest().getAttribute("ds.liveAuthentication") instanceof Runnable live)live.run();
  auth.requirePlatformManagement("identity.service.manage");return auth.actor();
 }
 private static Timestamp at(Instant value){return value==null?null:Timestamp.from(value);}
 private static String name(String value){if(value==null||value.isBlank()||value.length()>160)fail(HttpStatus.BAD_REQUEST,"INVALID_SERVICE_NAME");return value.trim();}
 private static void future(Instant value){if(value!=null&&!value.isAfter(Instant.now()))fail(HttpStatus.BAD_REQUEST,"SERVICE_VALIDITY_MUST_BE_FUTURE");}
 private Map<String,Object> row(UUID id,boolean lock){
  var rows=jdbc.queryForList("SELECT * FROM ds_service_subject WHERE service_subject_id=?"+(lock?" FOR UPDATE":""),id);
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"SERVICE_SUBJECT_NOT_AVAILABLE");return rows.get(0);
 }
 private void version(Map<String,Object> r,Long expected){
  if(expected==null)fail(HttpStatus.PRECONDITION_REQUIRED,"SERVICE_VERSION_REQUIRED");
  if(((Number)r.get("version")).longValue()!=expected)fail(HttpStatus.CONFLICT,"SERVICE_VERSION_CHANGED");
 }
 private Map<String,Object> view(Map<String,Object> r){
  var out=new LinkedHashMap<String,Object>();
  for(String k:List.of("service_subject_id","org_id","display_name","purpose","active","version","valid_until","closed_at","created_at","updated_at"))out.put(k,r.get(k) instanceof Timestamp t?t.toInstant().toString():r.get(k));
  return out;
 }
 private String encode(Object value){
  if(value==null)return null;
  try{return json.writeValueAsString(value);}catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("SERVICE_AUDIT_ENCODING_FAILED",error);}
 }
 private void audit(AuthorizationService.Actor a,String target,String id,String reason,Object before,Object after){
  var scope=new LinkedHashMap<String,Object>();
  if("SERVICE_SUBJECT".equals(target))scope.put("serviceSubjectId",id);
  else if(after instanceof Map<?,?> values){
   for(String key:List.of("serviceSubjectId","datasetId","grantId","viewId","versionId","indexVersionId","viewAuthorizationVersion"))if(values.get(key)!=null)scope.put(key,values.get(key));
  }
  jdbc.update("""
   INSERT INTO ds_authorization_audit(actor_user_id,actor_org_id,operation_code,target_kind,target_id,outcome,request_id,scope,before_value,after_value,reason_code)
   VALUES(?,?,'identity.service.manage',?,?,'SUCCESS',?,?::jsonb,?::jsonb,?::jsonb,?)
   """,a.userId(),a.orgId(),target,id,UUID.randomUUID().toString(),encode(scope),encode(before),encode(after),reason);
 }
 public List<Map<String,Object>> list(){manager();return jdbc.queryForList("SELECT * FROM ds_service_subject ORDER BY created_at,service_subject_id").stream().map(this::view).toList();}
 @Transactional public Map<String,Object> create(Create req){
  manager();auth.lockIdentityMutation();var a=manager();
  if(req==null||req.orgId()==null||!Set.of("VIEW_BUILD","SCHEDULED_DELIVERY","QUALITY_CHECK").contains(Objects.toString(req.purpose(),"")))fail(HttpStatus.BAD_REQUEST,"INVALID_SERVICE_PURPOSE");
  String label=name(req.name());future(req.validUntil());
  if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM org WHERE org_id=? AND org_status=1)",Boolean.class,req.orgId())))fail(HttpStatus.NOT_FOUND,"SERVICE_ORG_NOT_AVAILABLE");
  UUID id=UUID.randomUUID();
  jdbc.update("INSERT INTO ds_service_subject(service_subject_id,org_id,purpose,display_name,valid_until,created_by) VALUES(?,?,?,?,?,?)",id,req.orgId(),req.purpose(),label,at(req.validUntil()),a.userId());
  var result=view(row(id,false));audit(a,"SERVICE_SUBJECT",id.toString(),"SERVICE_SUBJECT_CREATED_WITHOUT_RESOURCE_GRANTS",null,result);return result;
 }
 @Transactional public Map<String,Object> change(UUID id,Change req){
  manager();auth.lockIdentityMutation();var a=manager();var r=row(id,true);if(req==null)fail(HttpStatus.BAD_REQUEST,"SERVICE_CHANGE_REQUIRED");version(r,req.expectedVersion());
  if(r.get("closed_at")!=null)fail(HttpStatus.CONFLICT,"SERVICE_SUBJECT_CLOSED");
  String label=req.name()==null?(String)r.get("display_name"):name(req.name());future(req.validUntil());
  boolean active=!req.close()&&(req.active()==null?Boolean.TRUE.equals(r.get("active")):req.active());
  jdbc.update("UPDATE ds_service_subject SET display_name=?,active=?,valid_until=?,closed_at=?,version=version+1,updated_at=clock_timestamp() WHERE service_subject_id=?",
   label,active,req.validUntil()==null?r.get("valid_until"):at(req.validUntil()),req.close()?Timestamp.from(Instant.now()):null,id);
  var result=view(row(id,false));audit(a,"SERVICE_SUBJECT",id.toString(),req.close()?"SERVICE_SUBJECT_CLOSED":"SERVICE_SUBJECT_VERSION_CHANGED",view(r),result);
  return result;
 }
 public List<Map<String,Object>> bindings(UUID id){
  manager();row(id,false);
  var bindings=jdbc.queryForList("""
   SELECT grant_id,dataset_id,version_id,index_version_id,view_authorization_version,scope_kind,operation_codes,purpose_codes,state,version,valid_from,valid_to
   FROM ds_resource_grant WHERE subject_kind='SERVICE' AND subject_id=? ORDER BY created_at,grant_id
   """,id);
  for(var binding:bindings)for(String key:List.of("operation_codes","purpose_codes")){
   Object value=binding.get(key);
   if(value instanceof java.sql.Array array)try{binding.put(key,Arrays.asList((Object[])array.getArray()));}
   catch(java.sql.SQLException error){throw new IllegalStateException("INVALID_SERVICE_BINDING_ARRAY",error);}
  }
  return bindings;
 }
 @Transactional public Map<String,Object> bind(UUID id,Bind req){
  manager();auth.lockIdentityMutation();var a=manager();var s=row(id,true);
  if(req==null||req.datasetId()==null)fail(HttpStatus.BAD_REQUEST,"DATASET_BINDING_REQUIRED");version(s,req.expectedVersion());future(req.validUntil());
  boolean quality="QUALITY_CHECK".equals(s.get("purpose"));
  boolean delivery="SCHEDULED_DELIVERY".equals(s.get("purpose"));
  if(!Boolean.TRUE.equals(s.get("active"))||s.get("closed_at")!=null||(!quality&&!delivery&&!"VIEW_BUILD".equals(s.get("purpose"))))fail(HttpStatus.CONFLICT,"BOUND_WORKER_SERVICE_REQUIRED");
  if(delivery&&req.viewId()==null)fail(HttpStatus.BAD_REQUEST,"DELIVERY_SERVICE_EXACT_CONTROLLED_VIEW_REQUIRED");
  String purpose=quality?"GENERAL_READ":delivery?"CONTROLLED_DELIVERY":"VIEW_BUILD";
  var operations=new ArrayList<String>();
  if(delivery)operations.addAll(List.of("delivery.execute","data.view.export"));
  else operations.add(quality?"dataset.quality.run":"dataset.index.build");
  UUID org=(UUID)s.get("org_id");
  if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM mm_dataset WHERE dataset_id=? AND org_id=? AND status='ACTIVE')",Boolean.class,req.datasetId(),org)))fail(HttpStatus.NOT_FOUND,"OWN_SERVICE_DATASET_NOT_AVAILABLE");
  if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ds_resource_grant WHERE subject_kind='SERVICE' AND subject_id=? AND dataset_id=? AND state='ACTIVE')",Boolean.class,id,req.datasetId())))fail(HttpStatus.CONFLICT,"ACTIVE_SERVICE_BINDING_EXISTS");
  var views=jdbc.queryForList("""
   SELECT view_id,owner_org_id,version_id,index_version_id,authorization_version,state,
          COALESCE((public_scope_summary->>'hasOriginalByteCopies')::boolean,false) AS original_copies
   FROM ds_controlled_view WHERE dataset_id=? AND (?::uuid IS NULL OR view_id=?)
   """,req.datasetId(),req.viewId(),req.viewId());
  if(views.isEmpty()&&(req.viewId()!=null||Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ds_controlled_view WHERE dataset_id=?)",Boolean.class,req.datasetId()))))
   fail(HttpStatus.NOT_FOUND,"SERVICE_VIEW_NOT_AVAILABLE");
  if(views.size()>1)fail(HttpStatus.CONFLICT,"EXPLICIT_SERVICE_VIEW_REQUIRED");
  String scopeKind="DATASET_CUSTODY";UUID version=null,index=null,view=null;Long viewAuthorization=null;
  if(!views.isEmpty()){
   var controlled=views.get(0);
   if(delivery&&Boolean.TRUE.equals(controlled.get("original_copies")))operations.add("data.raw.download");
   if(!"READY".equals(controlled.get("state"))||!org.equals(controlled.get("owner_org_id")))fail(HttpStatus.CONFLICT,"CONTROLLED_SERVICE_VIEW_NOT_READY");
   scopeKind="CONTROLLED_VIEW";view=(UUID)controlled.get("view_id");version=(UUID)controlled.get("version_id");index=(UUID)controlled.get("index_version_id");
   viewAuthorization=((Number)controlled.get("authorization_version")).longValue();
   if(!Boolean.TRUE.equals(jdbc.queryForObject("""
    SELECT EXISTS(SELECT 1 FROM mm_dataset_version v JOIN mm_dataset d ON d.dataset_id=v.dataset_id
     JOIN mm_index_version i ON i.dataset_id=d.dataset_id AND i.dataset_version_id=v.version_id
     JOIN artifact_commit raw ON raw.repo_id=d.raw_repo_id AND raw.commit_id=v.raw_commit_id AND raw.commit_status='PUBLISHED'
     JOIN artifact_commit idx ON idx.repo_id=i.index_repo_id AND idx.commit_id=i.index_commit_id AND idx.commit_status='PUBLISHED'
     WHERE d.dataset_id=? AND v.version_id=? AND i.index_version_id=? AND i.index_repo_id=d.index_repo_id AND i.index_status='READY')
    """,Boolean.class,req.datasetId(),version,index)))fail(HttpStatus.CONFLICT,"CONTROLLED_SERVICE_VIEW_NOT_READY");
  }
  UUID grant=UUID.randomUUID();
  try{
   jdbc.update("""
    INSERT INTO ds_resource_grant(grant_id,subject_kind,subject_id,grantee_org_id,owner_org_id,scope_kind,dataset_id,version_id,index_version_id,view_authorization_version,operation_codes,purpose_codes,created_by,valid_to)
    VALUES(?,'SERVICE',?,?,?,?,?,?,?,?,?::varchar[],ARRAY[?]::varchar[],?,?)
    """,grant,id,org,org,scopeKind,req.datasetId(),version,index,viewAuthorization,"{"+String.join(",",operations)+"}",purpose,a.userId(),at(req.validUntil()));
  }catch(org.springframework.dao.DataIntegrityViolationException changed){fail(HttpStatus.CONFLICT,"SERVICE_VIEW_OR_BINDING_CHANGED");}
  var after=new LinkedHashMap<String,Object>();after.put("grantId",grant);after.put("serviceSubjectId",id);after.put("datasetId",req.datasetId());
  after.put("scopeKind",scopeKind);after.put("viewId",view);after.put("versionId",version);after.put("indexVersionId",index);
  after.put("viewAuthorizationVersion",viewAuthorization);after.put("operations",operations);after.put("purposes",List.of(purpose));
  after.put("validTo",req.validUntil()==null?null:req.validUntil().toString());after.put("version",1);after.put("state","ACTIVE");
  audit(a,"RESOURCE_GRANT",grant.toString(),"EXPLICIT_SERVICE_DATASET_BINDING",null,after);return Map.of("grantId",grant,"version",1);
 }
 @Transactional public Map<String,Object> revoke(UUID id,UUID grant,Revoke req){
  manager();auth.lockIdentityMutation();var a=manager();row(id,true);
  if(req==null||req.expectedVersion()==null)fail(HttpStatus.PRECONDITION_REQUIRED,"GRANT_VERSION_REQUIRED");
  int changed=jdbc.update("""
   UPDATE ds_resource_grant SET state='REVOKED',version=version+1,revoked_at=clock_timestamp(),revoke_reason='SERVICE_BINDING_REVOKED'
   WHERE grant_id=? AND subject_kind='SERVICE' AND subject_id=? AND version=? AND state='ACTIVE'
   """,grant,id,req.expectedVersion());
  if(changed!=1)fail(HttpStatus.CONFLICT,"SERVICE_BINDING_CHANGED");
  UUID dataset=jdbc.queryForObject("SELECT dataset_id FROM ds_resource_grant WHERE grant_id=?",UUID.class,grant);
  var before=Map.of("grantId",grant,"serviceSubjectId",id,"datasetId",dataset,"state","ACTIVE","version",req.expectedVersion());
  var after=Map.of("grantId",grant,"serviceSubjectId",id,"datasetId",dataset,"state","REVOKED","version",req.expectedVersion()+1);
  audit(a,"RESOURCE_GRANT",grant.toString(),"SERVICE_BINDING_REVOKED",before,after);return Map.of("success",true);
 }
}
