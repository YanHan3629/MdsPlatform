package com.fwdrobo.sirius.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/** Backend-signed, parent-session-bound credentials for one frozen evidence scope. */
@Service
public class WorkerEvidenceScopeService {
 public static final String ATTRIBUTE="ds.verifiedEvidenceScope";
 private static final String ID="([0-9a-fA-F-]{36})";
 private static final Set<String> OPERATIONS=Set.of("data.qa","data.search","data.preview");
 private final AuthorizationService authorization;private final IdentitySessionService sessions;
 private final JwtService jwt;private final JdbcTemplate jdbc;private final byte[] internalToken;
 public WorkerEvidenceScopeService(AuthorizationService authorization,IdentitySessionService sessions,JwtService jwt,JdbcTemplate jdbc,
   @Value("${mm.internal.token:}") String internalToken){
  this.authorization=authorization;this.sessions=sessions;this.jwt=jwt;this.jdbc=jdbc;
  this.internalToken=internalToken==null?new byte[0]:internalToken.getBytes(StandardCharsets.UTF_8);
 }
 public record Scope(AuthorizationService.Actor actor,UUID session,UUID dataset,UUID version,UUID index,String operation,
                     UUID rawRepo,UUID rawCommit,UUID indexRepo,UUID indexCommit,AuthorizationService.ResourceLease lease){
  public boolean userInputOnly(){return dataset==null&&version==null&&index==null;}
 }
 private static void deny(HttpStatus status,String reason){throw new ResponseStatusException(status,reason);}
 private static UUID id(Claims claims,String key){
  try{return UUID.fromString(claims.get(key,String.class));}catch(Exception invalid){deny(HttpStatus.UNAUTHORIZED,"INVALID_EVIDENCE_CREDENTIAL");return null;}
 }
 private HttpServletRequest request(){
  if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)return attrs.getRequest();
  deny(HttpStatus.UNAUTHORIZED,"INTERACTIVE_OR_SCOPED_REQUEST_REQUIRED");return null;
 }
 private Scope resolve(AuthorizationService.Actor actor,UUID session,UUID dataset,UUID version,UUID index,String operation,AuthorizationService.ResourceAuthority authority){
  if(!OPERATIONS.contains(operation))deny(HttpStatus.FORBIDDEN,"EVIDENCE_OPERATION_REQUIRED");
  var lease=authorization.lease(actor,dataset,version,index,Set.of(operation),"GENERAL_READ",authority);
  var rows=jdbc.queryForList("""
    SELECT d.raw_repo_id,v.raw_commit_id,i.index_repo_id,i.index_commit_id
    FROM mm_dataset d JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
    JOIN mm_index_version i ON i.dataset_id=d.dataset_id AND i.dataset_version_id=v.version_id
    JOIN artifact_commit raw ON raw.repo_id=d.raw_repo_id AND raw.commit_id=v.raw_commit_id AND raw.commit_status='PUBLISHED'
    JOIN artifact_commit idx ON idx.repo_id=i.index_repo_id AND idx.commit_id=i.index_commit_id AND idx.commit_status='PUBLISHED'
    WHERE d.dataset_id=? AND v.version_id=? AND i.index_version_id=? AND i.index_status='READY'
    """,dataset,version,index);
  if(rows.size()!=1)deny(HttpStatus.NOT_FOUND,"FROZEN_EVIDENCE_SCOPE_NOT_AVAILABLE");
  var r=rows.get(0);return new Scope(actor,session,dataset,version,index,operation,
    (UUID)r.get("raw_repo_id"),(UUID)r.get("raw_commit_id"),(UUID)r.get("index_repo_id"),(UUID)r.get("index_commit_id"),lease);
 }
 private static long positive(Claims claims,String key){
  Object value=claims.get(key);
  if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<1)
   deny(HttpStatus.UNAUTHORIZED,"INVALID_EVIDENCE_AUTHORITY");
  return ((Number)value).longValue();
 }
 private static AuthorizationService.ResourceAuthority authority(Claims claims){
  String kind=claims.get("authority_kind",String.class);
  if("GRANT".equals(kind)&&!claims.containsKey("legacy_contract_id")&&!claims.containsKey("legacy_contract_revision"))
   return new AuthorizationService.ResourceAuthority(new AuthorizationService.GrantBinding(id(claims,"grant_id"),positive(claims,"grant_version")),null);
  if("LEGACY_FULL_VERSION_CONTRACT".equals(kind)&&!claims.containsKey("grant_id")&&!claims.containsKey("grant_version"))
   return new AuthorizationService.ResourceAuthority(null,new AuthorizationService.ContractBinding(id(claims,"legacy_contract_id"),positive(claims,"legacy_contract_revision")));
  deny(HttpStatus.UNAUTHORIZED,"EVIDENCE_ORIGINAL_AUTHORITY_REQUIRED");return null;
 }
 public Scope authenticate(HttpServletRequest request,Claims claims){
  byte[] supplied=Objects.toString(request.getHeader(InternalTokenFilter.HEADER),"").getBytes(StandardCharsets.UTF_8);
  if(internalToken.length==0||!MessageDigest.isEqual(internalToken,supplied))deny(HttpStatus.UNAUTHORIZED,"PRIVATE_WORKER_CREDENTIAL_REQUIRED");
  if(!"EVIDENCE_IO".equals(claims.get("token_type",String.class)))
    deny(HttpStatus.UNAUTHORIZED,"EVIDENCE_CREDENTIAL_REQUIRED");
  String purpose=claims.get("purpose",String.class),operation=claims.get("operation",String.class);
  var actor=sessions.authenticateAccess(claims);Scope scope;
  if("USER_INPUT_ONLY".equals(purpose)){
   if(!"data.qa".equals(operation)||java.util.stream.Stream.of("dataset_id","version_id","index_version_id","authority_kind","grant_id","grant_version","legacy_contract_id","legacy_contract_revision").anyMatch(claims::containsKey))
    deny(HttpStatus.UNAUTHORIZED,"USER_INPUT_CREDENTIAL_SCOPE_INVALID");
   authorization.requireOperation(actor,"data.qa");
   scope=new Scope(actor,id(claims,"sid"),null,null,null,operation,null,null,null,null,null);
  }else{
   if(!"GENERAL_READ".equals(purpose))deny(HttpStatus.UNAUTHORIZED,"EVIDENCE_PURPOSE_REQUIRED");
   scope=resolve(actor,id(claims,"sid"),id(claims,"dataset_id"),id(claims,"version_id"),id(claims,"index_version_id"),operation,authority(claims));
  }
  if(!permits(request,scope))deny(HttpStatus.FORBIDDEN,"EVIDENCE_ROUTE_OUTSIDE_SCOPE");
  request.setAttribute(ATTRIBUTE,scope);request.setAttribute("ds.evidenceClaims",claims);return scope;
 }
 private static boolean tuple(Matcher match,Scope scope,boolean includeIndex){
  if(!match.matches())return false;
  try{return UUID.fromString(match.group(1)).equals(scope.dataset())&&UUID.fromString(match.group(2)).equals(scope.version())&&
      (!includeIndex||UUID.fromString(match.group(3)).equals(scope.index()));}
  catch(IllegalArgumentException invalid){return false;}
 }
 public boolean permits(HttpServletRequest request,Scope scope){
  String path=request.getRequestURI(),method=request.getMethod();
  if("GET".equals(method)&&"/api/mm/internal/evidence/authorize".equals(path))return true;
  if(scope.userInputOnly())return false;
  if("GET".equals(method)&&tuple(Pattern.compile("/api/mm/internal/datasets/"+ID+"/versions/"+ID+"/indexes/"+ID+"/bundle").matcher(path),scope,true))return true;
  Matcher artifact=Pattern.compile("/api/artifacts/"+ID+"/commits/"+ID+"/files/content").matcher(path);
  if("GET".equals(method)&&artifact.matches()){
   UUID repo=UUID.fromString(artifact.group(1)),commit=UUID.fromString(artifact.group(2));
   return (repo.equals(scope.rawRepo())&&commit.equals(scope.rawCommit()))||(repo.equals(scope.indexRepo())&&commit.equals(scope.indexCommit()));
  }
  if("GET".equals(method)&&tuple(Pattern.compile("/api/data-space/qa/(?:access|capabilities)/"+ID+"/"+ID+"/"+ID).matcher(path),scope,true))return true;
  if("GET".equals(method)&&tuple(Pattern.compile("/api/data-space/qa/assets/"+ID+"/"+ID+"/"+ID).matcher(path),scope,false))return true;
  Matcher evidence=Pattern.compile("/api/data-space/qa/evidence/"+ID+"/"+ID+"/"+ID+"/"+ID+"(?:/([^/]+))?").matcher(path);
  if(!tuple(evidence,scope,true))return false;
  String tail=evidence.group(5);
  return "GET".equals(method)&&(tail==null||!Set.of("read","render").contains(tail))||
    "POST".equals(method)&&Set.of("read","render").contains(Objects.toString(tail,""));
 }
 public void revalidate(Scope original,Claims claims,HttpServletRequest request){
  if(claims.getExpiration()==null||!claims.getExpiration().after(new Date()))deny(HttpStatus.UNAUTHORIZED,"EVIDENCE_CREDENTIAL_EXPIRED");
  Scope current=authenticate(request,claims);
  if(!original.actor().userId().equals(current.actor().userId())||!original.actor().orgId().equals(current.actor().orgId())||
     original.actor().tokenVersion()!=current.actor().tokenVersion()||!Objects.equals(original.session(),current.session())||
     !Objects.equals(original.dataset(),current.dataset())||!Objects.equals(original.version(),current.version())||!Objects.equals(original.index(),current.index())||
     !Objects.equals(original.operation(),current.operation())||!Objects.equals(original.rawRepo(),current.rawRepo())||!Objects.equals(original.rawCommit(),current.rawCommit())||
     !Objects.equals(original.indexRepo(),current.indexRepo())||!Objects.equals(original.indexCommit(),current.indexCommit())||
     !Objects.equals(original.lease()==null?null:original.lease().authority(),current.lease()==null?null:current.lease().authority()))deny(HttpStatus.UNAUTHORIZED,"EVIDENCE_IDENTITY_CHANGED");
 }
 public String bearer(UUID dataset,UUID version,UUID index){
  HttpServletRequest request=request();
  String operation=authorization.currentOperation("data.qa");
  if(request.getAttribute(ATTRIBUTE) instanceof Scope scoped){
   if(scoped.userInputOnly()||!scoped.dataset().equals(dataset)||!scoped.version().equals(version)||!scoped.index().equals(index)||!scoped.operation().equals(operation))
     deny(HttpStatus.FORBIDDEN,"WORKER_SCOPE_REBIND_DENIED");
   revalidate(scoped,(Claims)request.getAttribute("ds.evidenceClaims"),request);
   return request.getHeader("Authorization");
  }
  if(!(request.getAttribute("ds.accessClaims") instanceof Claims))deny(HttpStatus.UNAUTHORIZED,"PARENT_ACCESS_SESSION_REQUIRED");
  Claims claims=(Claims)request.getAttribute("ds.accessClaims");
  if(!"ACCESS".equals(claims.get("token_type",String.class)))deny(HttpStatus.UNAUTHORIZED,"PARENT_ACCESS_SESSION_REQUIRED");
  var actor=sessions.authenticateAccess(claims);UUID sid=id(claims,"sid");
  Scope scope=resolve(actor,sid,dataset,version,index,operation,null);
  long remaining=(claims.getExpiration().getTime()-System.currentTimeMillis())/1000;
  if(remaining<=0)deny(HttpStatus.UNAUTHORIZED,"PARENT_ACCESS_EXPIRED");
  return "Bearer "+jwt.generateEvidenceIoToken(actor,sid,dataset,version,index,operation,scope.lease().authority(),Math.min(remaining,1200));
 }
 /** Attachment-only QA grants no corpus, renderer, search or artifact route. */
 public String userInputBearer(){
  HttpServletRequest request=request();
  if(!"data.qa".equals(authorization.currentOperation("data.qa")))deny(HttpStatus.FORBIDDEN,"QA_OPERATION_REQUIRED");
  if(!(request.getAttribute("ds.accessClaims") instanceof Claims))deny(HttpStatus.UNAUTHORIZED,"PARENT_ACCESS_SESSION_REQUIRED");
  Claims claims=(Claims)request.getAttribute("ds.accessClaims");
  if(!"ACCESS".equals(claims.get("token_type",String.class)))deny(HttpStatus.UNAUTHORIZED,"PARENT_ACCESS_SESSION_REQUIRED");
  var actor=sessions.authenticateAccess(claims);authorization.requireOperation(actor,"data.qa");
  long remaining=(claims.getExpiration().getTime()-System.currentTimeMillis())/1000;
  if(remaining<=0)deny(HttpStatus.UNAUTHORIZED,"PARENT_ACCESS_EXPIRED");
  return "Bearer "+jwt.generateUserInputIoToken(actor,id(claims,"sid"),Math.min(remaining,1200));
 }
 public String internalCredential(){return new String(internalToken,StandardCharsets.UTF_8);}
}
