package com.fwdrobo.sirius.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.request.RequestContextHolder;
import java.util.*;

/** Short-lived institutional credentials read one immutable index for cache prewarming. */
@Service
public class ServiceScopeService {
 public static final String ATTRIBUTE="ds.serviceScope";
 private final AuthorizationService auth;private final JwtService jwt;private final JdbcTemplate jdbc;
 public ServiceScopeService(AuthorizationService auth,JwtService jwt,JdbcTemplate jdbc){this.auth=auth;this.jwt=jwt;this.jdbc=jdbc;}
 public record Scope(AuthorizationService.Actor actor,UUID dataset,UUID version,UUID index,
    UUID rawRepo,UUID rawCommit,UUID indexRepo,UUID indexCommit,AuthorizationService.GrantBinding grant){}
 private static void fail(HttpStatus status,String code){throw new ResponseStatusException(status,code);}
 private static UUID id(Claims c,String key){
  try{return UUID.fromString(c.get(key,String.class));}catch(Exception invalid){fail(HttpStatus.UNAUTHORIZED,"INVALID_SERVICE_SCOPE");return null;}
 }
 private static long number(Claims c,String key){
  Object value=c.get(key);if(!(value instanceof Number))fail(HttpStatus.UNAUTHORIZED,"INVALID_SERVICE_SCOPE");return ((Number)value).longValue();
 }
 private Scope resolve(AuthorizationService.Actor actor,UUID d,UUID v,UUID i,AuthorizationService.GrantBinding grant){
  if(actor.serviceId()==null||actor.userId()!=null||!actor.operations().contains("dataset.index.build"))fail(HttpStatus.FORBIDDEN,"VIEW_BUILD_SERVICE_REQUIRED");
  var rows=jdbc.queryForList("""
   SELECT d.raw_repo_id,v.raw_commit_id,i.index_repo_id,i.index_commit_id,i.index_status
   FROM mm_dataset d JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
   JOIN mm_index_version i ON i.dataset_id=d.dataset_id AND i.dataset_version_id=v.version_id AND i.index_repo_id=d.index_repo_id
   JOIN artifact_commit raw ON raw.repo_id=d.raw_repo_id AND raw.commit_id=v.raw_commit_id AND raw.commit_status='PUBLISHED'
   JOIN artifact_commit idx ON idx.repo_id=i.index_repo_id AND idx.commit_id=i.index_commit_id AND idx.commit_status='PUBLISHED'
   WHERE d.dataset_id=? AND v.version_id=? AND i.index_version_id=? AND i.index_status IN('PREWARMING','READY')
   """,d,v,i);
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"FROZEN_SERVICE_INDEX_NOT_AVAILABLE");
  var r=rows.get(0);
  UUID grantVersion="READY".equals(r.get("index_status"))?v:null;
  UUID grantIndex="READY".equals(r.get("index_status"))?i:null;
  if(!auth.canUseDatasetBound(actor,d,grantVersion,grantIndex,Set.of("dataset.index.build"),"VIEW_BUILD",grant))
   fail(HttpStatus.NOT_FOUND,"SERVICE_RESOURCE_SCOPE_NOT_AVAILABLE");
  return new Scope(actor,d,v,i,(UUID)r.get("raw_repo_id"),(UUID)r.get("raw_commit_id"),(UUID)r.get("index_repo_id"),(UUID)r.get("index_commit_id"),grant);
 }
 /** Trusted scheduler/worker factory only; no credential-issuance HTTP endpoint. */
 public String bearer(UUID service,UUID dataset,UUID version,UUID index){
  if(RequestContextHolder.getRequestAttributes()!=null)fail(HttpStatus.FORBIDDEN,"SERVICE_CREDENTIALS_REQUIRE_BACKGROUND_CONTEXT");
  var actor=auth.serviceActorFor(service);auth.requireOperation(actor,"dataset.index.build");
  for(var g:jdbc.queryForList("SELECT grant_id,version FROM ds_resource_grant WHERE subject_kind='SERVICE' AND subject_id=? AND dataset_id=? AND state='ACTIVE' ORDER BY grant_id",service,dataset)){
   var binding=new AuthorizationService.GrantBinding((UUID)g.get("grant_id"),((Number)g.get("version")).longValue());
   try{
    Scope scope=resolve(actor,dataset,version,index,binding);
    return jwt.generateServiceIoToken(actor,scope,120);
   }catch(ResponseStatusException denied){if(denied.getStatusCode().value()!=404)throw denied;}
  }
  fail(HttpStatus.NOT_FOUND,"EXPLICIT_SERVICE_GRANT_REQUIRED");return null;
 }
 private void audit(HttpServletRequest request,Claims claims,String outcome,String reason){
  String flag="ds.serviceAudit."+outcome;if(Boolean.TRUE.equals(request.getAttribute(flag)))return;
  UUID service;
  try{service=UUID.fromString(claims.get("service_subject_id",String.class));}catch(Exception unknown){return;}
  var organizations=jdbc.queryForList("SELECT org_id FROM ds_service_subject WHERE service_subject_id=?",UUID.class,service);
  if(organizations.size()!=1)return;
  var scope=new LinkedHashMap<String,Object>();
  for(String key:List.of("dataset_id","version_id","index_version_id","grant_id")){
   try{scope.put(key,UUID.fromString(claims.get(key,String.class)).toString());}catch(Exception invalid){}
  }
  if(claims.get("grant_version") instanceof Number version)scope.put("grant_version",version.longValue());
  scope.put("purpose","VIEW_BUILD");scope.put("service_kind","INDEX_CACHE");scope.put("method",request.getMethod());
  var artifact=java.util.regex.Pattern.compile("^/api/artifacts/([0-9a-fA-F-]{36})/commits/([0-9a-fA-F-]{36})/files/content$").matcher(request.getRequestURI());
  if(artifact.matches()){
   try{scope.put("requested_repo_id",UUID.fromString(artifact.group(1)).toString());scope.put("requested_commit_id",UUID.fromString(artifact.group(2)).toString());}
   catch(IllegalArgumentException invalid){}
  }
  String requestId=(String)request.getAttribute("ds.serviceAuditRequestId");
  if(requestId==null){requestId=UUID.randomUUID().toString();request.setAttribute("ds.serviceAuditRequestId",requestId);}
  String encoded;try{encoded=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(scope);}
  catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("SERVICE_AUDIT_ENCODING_FAILED",error);}
  String code=reason!=null&&reason.matches("[A-Z0-9_]{1,80}")?reason:"SERVICE_SCOPE_REJECTED";
  jdbc.update("""
   INSERT INTO ds_authorization_audit(actor_service_id,actor_org_id,operation_code,target_kind,target_id,outcome,request_id,scope,reason_code)
   VALUES(?,?,'dataset.index.build','INDEX_VERSION',?,?,?,?::jsonb,?)
   """,service,organizations.get(0),scope.get("index_version_id"),outcome,requestId,encoded,code);
  request.setAttribute(flag,true);
 }
 public Scope authenticate(HttpServletRequest request,Claims claims){
  try{
   Scope scope=authenticateVerified(request,claims);
   audit(request,claims,"ALLOW","EXPLICIT_SERVICE_GRANT_ALLOWED");return scope;
  }catch(ResponseStatusException denied){
   audit(request,claims,"DENY",denied.getReason());throw denied;
  }
 }
 private Scope authenticateVerified(HttpServletRequest request,Claims claims){
  if(!"SERVICE_IO".equals(claims.get("token_type",String.class))||!"INDEX_CACHE".equals(claims.get("service_kind",String.class))||
      !"VIEW_BUILD".equals(claims.get("purpose",String.class))||!"dataset.index.build".equals(claims.get("operation",String.class))||
      claims.containsKey("uid")||claims.containsKey("sid"))fail(HttpStatus.UNAUTHORIZED,"INVALID_SERVICE_CREDENTIAL");
  if(claims.getExpiration()==null||!claims.getExpiration().after(new Date()))fail(HttpStatus.UNAUTHORIZED,"SERVICE_CREDENTIAL_EXPIRED");
  var actor=auth.serviceActorFor(id(claims,"service_subject_id"));
  if(actor.permissionVersion()!=number(claims,"service_version")||!actor.orgId().equals(id(claims,"org_id")))
   fail(HttpStatus.UNAUTHORIZED,"SERVICE_IDENTITY_REVOKED_OR_CHANGED");
  Scope scope=resolve(actor,id(claims,"dataset_id"),id(claims,"version_id"),id(claims,"index_version_id"),
     new AuthorizationService.GrantBinding(id(claims,"grant_id"),number(claims,"grant_version")));
  if(!scope.rawRepo().equals(id(claims,"raw_repo_id"))||!scope.rawCommit().equals(id(claims,"raw_commit_id"))||
     !scope.indexRepo().equals(id(claims,"index_repo_id"))||!scope.indexCommit().equals(id(claims,"index_commit_id")))
   fail(HttpStatus.FORBIDDEN,"SERVICE_FROZEN_SCOPE_CHANGED");
  if(!permits(request,scope))fail(HttpStatus.FORBIDDEN,"SERVICE_ROUTE_OUTSIDE_SCOPE");
  request.setAttribute(ATTRIBUTE,scope);request.setAttribute("ds.serviceClaims",claims);return scope;
 }
 private boolean permits(HttpServletRequest request,Scope s){
  if(!"GET".equals(request.getMethod()))return false;
  String path=request.getRequestURI();
  return path.equals("/api/mm/service/authority")||
   path.equals("/api/mm/service/datasets/"+s.dataset()+"/versions/"+s.version()+"/indexes/"+s.index()+"/bundle")||
   path.equals("/api/artifacts/"+s.indexRepo()+"/commits/"+s.indexCommit()+"/files/content");
 }
 public Scope current(){
  if(RequestContextHolder.getRequestAttributes() instanceof org.springframework.web.context.request.ServletRequestAttributes attrs){
   var req=attrs.getRequest();
   if(req.getAttribute(ATTRIBUTE) instanceof Scope s&&req.getAttribute("ds.serviceClaims") instanceof Claims claims)return authenticate(req,claims);
  }
  fail(HttpStatus.UNAUTHORIZED,"SERVICE_SCOPE_REQUIRED");return null;
 }
 public static boolean matches(Scope s,UUID d,UUID v,UUID i){
  return s.dataset().equals(d)&&(v==null||s.version().equals(v))&&(i==null||s.index().equals(i));
 }
 public void requireArtifact(UUID repo,UUID commit,boolean write){
  Scope s=current();
  if(write||!s.indexRepo().equals(repo)||!s.indexCommit().equals(commit))fail(HttpStatus.FORBIDDEN,"SERVICE_ARTIFACT_OUTSIDE_SCOPE");
 }
 public Map<String,Object> authority(){
  Scope s=current();return Map.of("subjectKind","SERVICE","serviceSubjectId",s.actor().serviceId(),"orgId",s.actor().orgId(),
   "datasetId",s.dataset(),"versionId",s.version(),"indexVersionId",s.index(),"purpose","VIEW_BUILD","operation","dataset.index.build");
 }
}
