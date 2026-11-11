package com.fwdrobo.sirius.security;
import com.fasterxml.jackson.databind.*;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Reviewed human grants configure policy; no management capability implies a content read. */
@Service
public class HumanResourceGrantManagement {
 private static final String MANAGEMENT="identity.resource.manage",SHARE="dataset.share.manage";
 private static final Set<String> PURPOSES=Set.of("GENERAL_READ","VIEW_BUILD","CONTROLLED_DELIVERY");
 private static final Set<String> CROSS_MUTATIONS=Set.of("contract.edit","contract.confirm","contract.revoke","delivery.execute","delivery.endpoint.manage","job.cancel");
 private final JdbcTemplate db;private final AuthorizationService auth;private final IdentitySessionService sessions;
 private final AuthorizationAuditService audit;private final ObjectMapper json;
 public HumanResourceGrantManagement(JdbcTemplate db,AuthorizationService auth,IdentitySessionService sessions,AuthorizationAuditService audit,ObjectMapper json){
  this.db=db;this.auth=auth;this.sessions=sessions;this.audit=audit;this.json=json;
 }
 public record CreateRequest(String subjectKind,UUID subjectId,UUID granteeOrgId,String scopeKind,UUID datasetId,UUID versionId,
  UUID indexVersionId,UUID viewId,List<String> operations,String purpose,Instant validFrom,Instant validTo,UUID contractId,Integer contractRevision,String reason,List<String> purposeCodes){
  public CreateRequest(String subjectKind,UUID subjectId,UUID granteeOrgId,String scopeKind,UUID datasetId,UUID versionId,
   UUID indexVersionId,UUID viewId,List<String> operations,String purpose,Instant validFrom,Instant validTo,UUID contractId,Integer contractRevision,String reason){
   this(subjectKind,subjectId,granteeOrgId,scopeKind,datasetId,versionId,indexVersionId,viewId,operations,purpose,validFrom,validTo,contractId,contractRevision,reason,null);
  }
 }
 public record RevokeRequest(Long expectedVersion,String reason){}
 public record ConfirmRequest(boolean confirm){}
 private static void fail(HttpStatus status,String code){throw new ResponseStatusException(status,code);}
 private boolean exists(String sql,Object... args){return Boolean.TRUE.equals(db.queryForObject(sql,Boolean.class,args));}
 private Claims claims(){
  if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes w)||!(w.getRequest().getAttribute("ds.accessClaims") instanceof Claims))
   fail(HttpStatus.UNAUTHORIZED,"INTERACTIVE_SESSION_REQUIRED");
  return (Claims)((ServletRequestAttributes)RequestContextHolder.getRequestAttributes()).getRequest().getAttribute("ds.accessClaims");
 }
 private AuthorizationService.Actor caller(boolean protectOutput){
  var a=sessions.authenticateAccess(claims());String operation=a.platformAdmin()?MANAGEMENT:SHARE;
  if(a.platformAdmin())auth.requirePlatformManagement(MANAGEMENT);else auth.requireOperation(a,SHARE);
  if(protectOutput&&RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes w)
   w.getRequest().setAttribute(AuthorizationService.REQUEST_OPERATION,operation);
  return a;
 }
 private void lock(){auth.lockIdentityMutation();db.execute("SELECT pg_advisory_xact_lock(805014630400001::bigint)");}
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("GRANT_METADATA_ENCODING_FAILED");}}
 private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(Exception e){throw new IllegalStateException("GRANT_METADATA_NOT_READABLE");}}
 private JsonNode sorted(JsonNode v){
  if(v.isObject()){var n=json.createObjectNode();var keys=new TreeSet<String>();v.fieldNames().forEachRemaining(keys::add);for(String k:keys)n.set(k,sorted(v.get(k)));return n;}
  if(v.isArray()){var n=json.createArrayNode();v.forEach(x->n.add(sorted(x)));return n;}return v;
 }
 private String canonical(Object v){return encode(sorted(v instanceof JsonNode n?n:json.valueToTree(v)));}
 private String hash(Object v){return JobExecutionDefinitionService.sha(canonical(v));}
 private static long number(Object v){return ((Number)v).longValue();}
 private static Instant instant(Object v){return v==null?null:((Timestamp)v).toInstant();}
 private static UUID uuid(Object v){return v==null?null:UUID.fromString(v.toString());}
 private static String reason(String v){
  if(v==null||v.trim().length()<6||v.length()>500||v.indexOf('\0')>=0)fail(HttpStatus.BAD_REQUEST,"GRANT_REVIEW_REASON_REQUIRED");return v.trim();
 }
 private static void purpose(String v){if(!PURPOSES.contains(Objects.toString(v,"")))fail(HttpStatus.BAD_REQUEST,"GRANT_PURPOSE_REQUIRED");}
 private static List<String> selectedPurposes(String legacy,List<String> values){
  if(legacy!=null&&values!=null)fail(HttpStatus.BAD_REQUEST,"GRANT_PURPOSE_FIELDS_CONFLICT");
  if(values==null){purpose(legacy);return List.of(legacy);}
  if(values.isEmpty()||values.size()>PURPOSES.size()||new HashSet<>(values).size()!=values.size()||values.stream().anyMatch(v->v==null||!PURPOSES.contains(v)))
   fail(HttpStatus.BAD_REQUEST,"UNIQUE_SUPPORTED_GRANT_PURPOSES_REQUIRED");
  return List.copyOf(new TreeSet<>(values));
 }
 private static List<String> selectedPurposes(CreateRequest request){return selectedPurposes(request.purpose(),request.purposeCodes());}
 private static CreateRequest forPurposes(CreateRequest r,List<String> values){
  return new CreateRequest(r.subjectKind(),r.subjectId(),r.granteeOrgId(),r.scopeKind(),r.datasetId(),r.versionId(),r.indexVersionId(),r.viewId(),r.operations(),null,r.validFrom(),r.validTo(),r.contractId(),r.contractRevision(),r.reason(),values);
 }
 private static boolean scopedOperation(String code){return OperationCatalog.isBusiness(code)&&!Set.of("dataset.create","application.submit").contains(code);}
 public List<OperationCatalog.Operation> operations(){caller(true);return OperationCatalog.all().values().stream().filter(x->scopedOperation(x.code())).toList();}
 private Map<String,Object> org(UUID id,boolean requireMapping){
  var rows=db.queryForList("""
   SELECT o.org_id,o.org_name,o.permission_version,e.state AS mapping_state,e.version AS mapping_version
   FROM org o LEFT JOIN ds_enterprise_identity e ON e.org_id=o.org_id
   WHERE o.org_id=? AND o.org_status=1
   """,id);
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"GRANT_ORGANIZATION_NOT_AVAILABLE");
  var row=rows.get(0);
  if((row.get("mapping_state")!=null&&!"CONFIRMED".equals(row.get("mapping_state")))||
    (requireMapping&&!"CONFIRMED".equals(row.get("mapping_state"))))fail(HttpStatus.NOT_FOUND,"CONFIRMED_GRANTEE_ORGANIZATION_REQUIRED");
  return row;
 }
 private Map<String,Object> resource(CreateRequest r){
  if(r==null||r.datasetId()==null||!Set.of("DATASET_CUSTODY","FROZEN_VERSION","CONTROLLED_VIEW").contains(Objects.toString(r.scopeKind(),"")))
   fail(HttpStatus.BAD_REQUEST,"GRANT_RESOURCE_SCOPE_REQUIRED");
  var rows=db.queryForList("SELECT dataset_id,dataset_name,org_id FROM mm_dataset WHERE dataset_id=? AND status='ACTIVE'",r.datasetId());
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"GRANT_RESOURCE_NOT_AVAILABLE");
  var d=rows.get(0);var owner=org((UUID)d.get("org_id"),false);var out=new TreeMap<String,Object>();
  out.put("datasetId",r.datasetId().toString());out.put("datasetName",d.get("dataset_name"));out.put("ownerOrgId",d.get("org_id").toString());
  out.put("ownerOrgVersion",owner.get("permission_version"));out.put("scopeKind",r.scopeKind());
  boolean viewDataset=exists("SELECT EXISTS(SELECT 1 FROM ds_controlled_view WHERE dataset_id=?)",r.datasetId());
  if("DATASET_CUSTODY".equals(r.scopeKind())){
   if(viewDataset||r.versionId()!=null||r.indexVersionId()!=null||r.viewId()!=null)fail(HttpStatus.BAD_REQUEST,"DATASET_CUSTODY_MUST_BE_EXPLICIT_UNVERSIONED_OWN_SCOPE");
   return out;
  }
  if(r.versionId()==null||r.indexVersionId()==null)fail(HttpStatus.BAD_REQUEST,"FROZEN_GRANT_IDENTITIES_REQUIRED");
  var versions=db.queryForList("""
   SELECT v.raw_commit_id,v.version_name,i.index_commit_id FROM mm_dataset d
   JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
   JOIN mm_index_version i ON i.dataset_id=d.dataset_id AND i.dataset_version_id=v.version_id AND i.index_repo_id=d.index_repo_id
   JOIN artifact_commit raw ON raw.commit_id=v.raw_commit_id AND raw.repo_id=d.raw_repo_id AND raw.commit_status='PUBLISHED'
   JOIN artifact_commit idx ON idx.commit_id=i.index_commit_id AND idx.repo_id=i.index_repo_id AND idx.commit_status='PUBLISHED'
   WHERE d.dataset_id=? AND v.version_id=? AND i.index_version_id=? AND i.index_status='READY'
   """,r.datasetId(),r.versionId(),r.indexVersionId());
  if(versions.size()!=1)fail(HttpStatus.NOT_FOUND,"FROZEN_GRANT_RESOURCE_NOT_AVAILABLE");
  var v=versions.get(0);out.put("versionId",r.versionId().toString());out.put("indexVersionId",r.indexVersionId().toString());
  out.put("versionName",v.get("version_name"));out.put("rawCommitId",v.get("raw_commit_id").toString());out.put("indexCommitId",v.get("index_commit_id").toString());
  if("CONTROLLED_VIEW".equals(r.scopeKind())){
   if(r.viewId()==null)fail(HttpStatus.BAD_REQUEST,"EXPLICIT_CONTROLLED_VIEW_REQUIRED");
   var views=db.queryForList("""
    SELECT authorization_version,btrim(rule_manifest_sha256) AS sha,public_scope_summary FROM ds_controlled_view
    WHERE view_id=? AND dataset_id=? AND version_id=? AND index_version_id=? AND owner_org_id=? AND state='READY'
    """,r.viewId(),r.datasetId(),r.versionId(),r.indexVersionId(),d.get("org_id"));
   if(views.size()!=1)fail(HttpStatus.NOT_FOUND,"CONTROLLED_GRANT_VIEW_NOT_AVAILABLE");
   var cv=views.get(0);out.put("viewId",r.viewId().toString());out.put("viewAuthorizationVersion",cv.get("authorization_version"));
   out.put("ruleManifestSha256",cv.get("sha"));out.put("hasOriginalByteCopies",parse(cv.get("public_scope_summary")).path("hasOriginalByteCopies").asBoolean(false));
  }else if(viewDataset||r.viewId()!=null)fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_SCOPE_REQUIRED");
  return out;
 }
 private Map<String,Object> sourceAuthority(AuthorizationService.Actor a,CreateRequest r,Set<String> requested,Map<String,Object> fixed,boolean coverLifetime){
  if(fixed!=null&&"PLATFORM_SAME_ORG_CONFIGURATION".equals(fixed.get("kind"))){
   if(!a.platformAdmin())fail(HttpStatus.FORBIDDEN,"PLATFORM_ADMIN_REQUIRED");
   if(!r.granteeOrgId().equals(owner(r.datasetId())))fail(HttpStatus.CONFLICT,"GRANT_AUTHORIZATION_CONTEXT_CHANGED");return fixed;
  }
  if(fixed==null&&a.platformAdmin()&&r.granteeOrgId().equals(owner(r.datasetId())))return Map.of("kind","PLATFORM_SAME_ORG_CONFIGURATION");
  var all=new TreeSet<>(requested);all.add(SHARE);for(String code:all)auth.requireOperation(a,code);
  if(!a.orgId().equals(owner(r.datasetId())))fail(HttpStatus.NOT_FOUND,"RESOURCE_SCOPE_NOT_AVAILABLE");
  List<Map<String,Object>> candidates=fixed==null?
   db.queryForList("SELECT grant_id,version,valid_from,valid_to FROM ds_resource_grant WHERE dataset_id=? AND state='ACTIVE' ORDER BY grant_id",r.datasetId()):
   db.queryForList("SELECT grant_id,version,valid_from,valid_to FROM ds_resource_grant WHERE grant_id=? AND version=? AND state='ACTIVE'",uuid(fixed.get("grantId")),number(fixed.get("version")));
  for(var g:candidates){
   var binding=new AuthorizationService.GrantBinding((UUID)g.get("grant_id"),number(g.get("version")));
   if(selectedPurposes(r).stream().anyMatch(p->!auth.canConfigureGrantBound(a,r.datasetId(),r.versionId(),r.indexVersionId(),all,p,binding)))continue;
   if(!Boolean.TRUE.equals(db.queryForObject("SELECT ds_human_grant_origin_depth(?) BETWEEN 0 AND 31",Boolean.class,binding.grantId())))continue;
   Instant from=instant(g.get("valid_from")),to=instant(g.get("valid_to"));
   if(coverLifetime&&r.validFrom()!=null&&r.validFrom().isBefore(from))continue;
   if(coverLifetime&&to!=null&&(r.validTo()==null||r.validTo().isAfter(to)))continue;
   var purposes=selectedPurposes(r);
   return purposes.size()==1?Map.of("kind","ORIGINAL_PROVIDER_GRANT","grantId",binding.grantId().toString(),"version",binding.version(),"purpose",purposes.get(0)):
    Map.of("kind","ORIGINAL_PROVIDER_GRANT","grantId",binding.grantId().toString(),"version",binding.version(),"purposeCodes",purposes);
  }
  fail(HttpStatus.NOT_FOUND,"RESOURCE_SCOPE_NOT_AVAILABLE");return null;
 }
 private UUID owner(UUID dataset){
  var rows=db.queryForList("SELECT org_id FROM mm_dataset WHERE dataset_id=?",UUID.class,dataset);
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"GRANT_RESOURCE_NOT_AVAILABLE");return rows.get(0);
 }
 private Map<String,Object> grantee(AuthorizationService.Actor a,CreateRequest r,UUID owner){
  if(r.granteeOrgId()==null||r.subjectId()==null||!Set.of("USER","ORG").contains(Objects.toString(r.subjectKind(),"")))fail(HttpStatus.BAD_REQUEST,"HUMAN_GRANTEE_REQUIRED");
  var o=org(r.granteeOrgId(),!owner.equals(r.granteeOrgId()));var out=new TreeMap<String,Object>();
  out.put("orgId",r.granteeOrgId().toString());out.put("orgName",o.get("org_name"));out.put("orgVersion",o.get("permission_version"));
  out.put("subjectKind",r.subjectKind());out.put("subjectId",r.subjectId().toString());
  if("ORG".equals(r.subjectKind())){
   if(!r.subjectId().equals(r.granteeOrgId()))fail(HttpStatus.BAD_REQUEST,"GRANTEE_ORGANIZATION_MISMATCH");
   out.put("affectedMemberCount",db.queryForObject("SELECT count(*) FROM fwd_user WHERE org_id=? AND account_state<>'CLOSED'",Long.class,r.granteeOrgId()));
  }else{
   var users=db.queryForList("SELECT permission_version,account_state,user_name FROM fwd_user WHERE user_id=? AND org_id=? AND account_state='ACTIVE'",r.subjectId(),r.granteeOrgId());
   if(users.size()!=1)fail(HttpStatus.NOT_FOUND,"GRANTEE_MEMBER_NOT_AVAILABLE");
   out.put("memberVersion",users.get(0).get("permission_version"));out.put("affectedMemberCount",1);
   if(a.platformAdmin()||(a.enterpriseManager()&&a.orgId().equals(r.granteeOrgId())))out.put("username",users.get(0).get("user_name"));
  }
  return out;
 }
 private void contract(CreateRequest r,UUID owner,Set<String> actions,boolean cross){
  if((r.contractId()==null)!=(r.contractRevision()==null))fail(HttpStatus.BAD_REQUEST,"EXACT_CONTRACT_REVISION_REQUIRED");
  if(r.contractId()==null){
   if(cross&&"FROZEN_VERSION".equals(r.scopeKind()))fail(HttpStatus.CONFLICT,"CROSS_ENTERPRISE_FULL_VERSION_CONTRACT_REQUIRED");return;
  }
  if(r.versionId()==null||r.indexVersionId()==null||r.contractRevision()<1)fail(HttpStatus.BAD_REQUEST,"FROZEN_CONTRACT_SCOPE_REQUIRED");
  var rows=db.queryForList("""
   SELECT c.valid_from,c.valid_to,c.scope_type,plan.view_id,
     CASE WHEN c.scope_type='CONTROLLED_VIEW' THEN ds_controlled_contract_scope_current(c.contract_id,c.revision) ELSE true END AS scope_current
   FROM ds_contract c JOIN ds_product_version p ON p.product_version_id=c.product_version_id
   LEFT JOIN ds_contract_controlled_revision plan ON plan.contract_id=c.contract_id AND plan.revision=c.revision
   JOIN mm_dataset_version v ON v.dataset_id=p.dataset_id AND v.version_id=p.version_id AND v.raw_commit_id=p.raw_commit_id
   WHERE c.contract_id=? AND c.revision=? AND c.provider_org_id=? AND c.consumer_org_id=?
    AND c.status='CONFIRMED' AND c.revoked_at IS NULL AND c.provider_confirmed_at IS NOT NULL AND c.consumer_confirmed_at IS NOT NULL
    AND p.dataset_id=? AND p.version_id=? AND p.index_version_id=?
   """,r.contractId(),r.contractRevision(),owner,r.granteeOrgId(),r.datasetId(),r.versionId(),r.indexVersionId());
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"EXACT_CONFIRMED_CONTRACT_NOT_AVAILABLE");
  var row=rows.get(0);
  if("FROZEN_VERSION".equals(r.scopeKind())&&!"FULL_VERSION".equals(row.get("scope_type")))fail(HttpStatus.CONFLICT,"FULL_VERSION_CONTRACT_REQUIRED");
  if("CONTROLLED_VIEW".equals(r.scopeKind())&&(!"CONTROLLED_VIEW".equals(row.get("scope_type"))||
     !Objects.equals(r.viewId(),row.get("view_id"))||!Boolean.TRUE.equals(row.get("scope_current"))))
   fail(HttpStatus.CONFLICT,"CURRENT_CONTROLLED_CONTRACT_VIEW_REQUIRED");
  if(r.validFrom().isBefore(instant(row.get("valid_from")))||r.validTo()==null||r.validTo().isAfter(instant(row.get("valid_to"))))fail(HttpStatus.CONFLICT,"GRANT_OUTSIDE_CONTRACT_VALIDITY");
  for(String op:actions)for(String selected:selectedPurposes(r))if(!exists("SELECT EXISTS(SELECT 1 FROM ds_contract_operation WHERE contract_id=? AND contract_revision=? AND operation_code=? AND ?=ANY(purpose_codes))",r.contractId(),r.contractRevision(),op,selected))
   fail(HttpStatus.CONFLICT,"CONTRACT_ACTION_OR_PURPOSE_NOT_GRANTED");
 }
 private CreateRequest normalized(CreateRequest r){
  if(r==null)fail(HttpStatus.BAD_REQUEST,"GRANT_FIELDS_REQUIRED");var purposes=selectedPurposes(r);reason(r.reason());
  if(r.operations()==null||r.operations().isEmpty()||r.operations().size()>64||new HashSet<>(r.operations()).size()!=r.operations().size()||r.operations().stream().anyMatch(op->!scopedOperation(op)))
   fail(HttpStatus.BAD_REQUEST,"IMPLEMENTED_UNIQUE_RESOURCE_OPERATIONS_REQUIRED");
  Instant from=r.validFrom()==null?Instant.now():r.validFrom();
  if(r.validTo()!=null&&(!from.isBefore(r.validTo())||!Instant.now().isBefore(r.validTo())))fail(HttpStatus.BAD_REQUEST,"GRANT_VALIDITY_INVALID");
  return new CreateRequest(r.subjectKind(),r.subjectId(),r.granteeOrgId(),r.scopeKind(),r.datasetId(),r.versionId(),r.indexVersionId(),r.viewId(),
   new TreeSet<>(r.operations()).stream().toList(),r.purpose(),from,r.validTo(),r.contractId(),r.contractRevision(),r.reason().trim(),r.purposeCodes()==null?null:purposes);
 }
 private Map<String,Object> createPlan(AuthorizationService.Actor a,UUID grantId,CreateRequest raw,Map<String,Object> fixedSource){
  CreateRequest r=normalized(raw);var actions=new TreeSet<>(r.operations());
  // A provider must prove the complete scope before resolving descriptive resource or foreign-grantee metadata.
  Map<String,Object> earlyAuthority=a.platformAdmin()?null:sourceAuthority(a,r,actions,fixedSource,true);
  var scope=resource(r);UUID owner=uuid(scope.get("ownerOrgId"));var target=grantee(a,r,owner);
  boolean cross=!owner.equals(r.granteeOrgId());
  if("DATASET_CUSTODY".equals(r.scopeKind())&&cross)fail(HttpStatus.BAD_REQUEST,"CROSS_ENTERPRISE_CUSTODY_FORBIDDEN");
  if(cross&&(r.validTo()==null||actions.stream().anyMatch(op->op.equals(SHARE)||(OperationCatalog.all().get(op).mutation()&&!CROSS_MUTATIONS.contains(op)))))
   fail(HttpStatus.BAD_REQUEST,"CROSS_ENTERPRISE_CONSUMPTION_ACTIONS_AND_EXPIRY_REQUIRED");
  if(actions.contains("data.view.export")&&!"CONTROLLED_VIEW".equals(r.scopeKind()))fail(HttpStatus.BAD_REQUEST,"VIEW_EXPORT_REQUIRES_CONTROLLED_VIEW");
  if(Boolean.TRUE.equals(scope.get("hasOriginalByteCopies"))&&actions.contains("data.view.export")&&!actions.contains("data.raw.download"))
   fail(HttpStatus.BAD_REQUEST,"VIEW_WITH_ORIGINAL_COPIES_REQUIRES_RAW_OPERATION");
  if(actions.stream().anyMatch(op->Set.of("delivery.execute","delivery.package.download").contains(op))){
   if(!actions.contains("CONTROLLED_VIEW".equals(r.scopeKind())?"data.view.export":"data.raw.download")||
     (Boolean.TRUE.equals(scope.get("hasOriginalByteCopies"))&&!actions.contains("data.raw.download")))fail(HttpStatus.BAD_REQUEST,"COMPLETE_DELIVERY_EXPORT_ACTIONS_REQUIRED");
   boolean ownerDeliveryScope=!cross&&"CONTROLLED_VIEW".equals(r.scopeKind())&&
     selectedPurposes(r).contains("CONTROLLED_DELIVERY");
   if(r.contractId()==null&&!ownerDeliveryScope)fail(HttpStatus.BAD_REQUEST,"DELIVERY_CONTRACT_REQUIRED");
  }
  contract(r,owner,actions,cross);
  var authority=earlyAuthority==null?sourceAuthority(a,r,actions,fixedSource,true):earlyAuthority;
  var out=new TreeMap<String,Object>();out.put("action","CREATE");out.put("grantId",grantId.toString());out.put("definition",json.valueToTree(r));
  out.put("resource",scope);out.put("grantee",target);out.put("sourceAuthority",authority);out.put("reason",r.reason());out.put("purposeCodes",selectedPurposes(r));
  out.put("doesNotAssignBusinessCapabilities",true);out.put("explicitOrganizationWideGrant","ORG".equals(r.subjectKind()));return out;
 }
 private Map<String,Object> row(UUID grant){
  var rows=db.queryForList("SELECT * FROM ds_resource_grant WHERE grant_id=? AND subject_kind IN('USER','ORG')",grant);
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"HUMAN_GRANT_NOT_AVAILABLE");return rows.get(0);
 }
 private static String[] array(Object value){try{return (String[])((java.sql.Array)value).getArray();}catch(java.sql.SQLException e){throw new IllegalStateException("GRANT_ARRAY_METADATA_INVALID");}}
 private CreateRequest definition(Map<String,Object> g){
  String[] operations=array(g.get("operation_codes"));
  String[] purposes=array(g.get("purpose_codes"));
  UUID view=null;
  if("CONTROLLED_VIEW".equals(g.get("scope_kind"))){
   var ids=db.queryForList("SELECT view_id FROM ds_controlled_view WHERE dataset_id=? AND version_id=? AND index_version_id=?",UUID.class,g.get("dataset_id"),g.get("version_id"),g.get("index_version_id"));view=ids.size()==1?ids.get(0):null;
  }
  return new CreateRequest((String)g.get("subject_kind"),(UUID)g.get("subject_id"),(UUID)g.get("grantee_org_id"),(String)g.get("scope_kind"),
   (UUID)g.get("dataset_id"),(UUID)g.get("version_id"),(UUID)g.get("index_version_id"),view,Arrays.asList(operations),
   null,instant(g.get("valid_from")),instant(g.get("valid_to")),(UUID)g.get("contract_id"),
   g.get("contract_revision")==null?null:((Number)g.get("contract_revision")).intValue(),"HISTORICAL_GRANT_CONFIGURATION",Arrays.asList(purposes));
 }
 private Map<String,Object> revokePlan(AuthorizationService.Actor a,UUID grant,RevokeRequest req,Map<String,Object> fixed){
  if(req==null||req.expectedVersion()==null)fail(HttpStatus.PRECONDITION_REQUIRED,"GRANT_VERSION_REQUIRED");
  var g=row(grant);if(number(g.get("version"))!=req.expectedVersion()||!"ACTIVE".equals(g.get("state")))fail(HttpStatus.CONFLICT,"GRANT_VERSION_OR_STATE_CHANGED");
  var d=definition(g);Map<String,Object> authority=null;
  if(a.platformAdmin())authority=Map.of("kind","PLATFORM_GRANT_REVOCATION");
  else{
   var choices=new TreeSet<>(Arrays.asList(array(g.get("purpose_codes"))));
   if(fixed!=null){choices.clear();choices.add(Objects.toString(fixed.get("purpose"),""));}
   for(String selected:choices){
    if(!PURPOSES.contains(selected))continue;
    var scoped=new CreateRequest(d.subjectKind(),d.subjectId(),d.granteeOrgId(),d.scopeKind(),d.datasetId(),d.versionId(),d.indexVersionId(),d.viewId(),d.operations(),selected,d.validFrom(),d.validTo(),d.contractId(),d.contractRevision(),d.reason());
    try{authority=sourceAuthority(a,scoped,Set.of(),fixed,false);break;}catch(ResponseStatusException denied){}
   }
   if(authority==null)fail(HttpStatus.NOT_FOUND,"COMPLETE_PROVIDER_SHARE_AUTHORITY_REQUIRED");
  }
  var out=new TreeMap<String,Object>();out.put("action","REVOKE");out.put("grantId",grant.toString());out.put("expectedVersion",req.expectedVersion());
  out.put("definition",json.valueToTree(d));out.put("purposeCodes",Arrays.asList(array(g.get("purpose_codes"))));out.put("sourceAuthority",authority);out.put("reason",reason(req.reason()));
  out.put("sourceImpact",originImpact(a,grant));
  out.put("otherActiveGrantCount",db.queryForObject("""
   SELECT count(*) FROM ds_resource_grant WHERE grant_id<>? AND dataset_id=? AND grantee_org_id=?
    AND ((subject_kind='ORG' AND subject_id=grantee_org_id) OR (subject_kind='USER' AND (subject_id=? OR ?='ORG')))
    AND state='ACTIVE' AND valid_from<=clock_timestamp() AND (valid_to IS NULL OR clock_timestamp()<valid_to)
    AND EXISTS(SELECT 1 FROM unnest(operation_codes) supported(code) WHERE ds_human_grant_origin_current(grant_id,ARRAY[supported.code]::varchar[],ARRAY[]::uuid[]))
   """,Long.class,grant,g.get("dataset_id"),g.get("grantee_org_id"),g.get("subject_id"),g.get("subject_kind")));
  out.put("revokesOnlyThisGrant",true);return out;
 }
 private Map<String,Object> review(AuthorizationService.Actor a,Map<String,Object> plan){
  UUID id=UUID.randomUUID();Instant expires=Instant.now().plusSeconds(300);String payload=canonical(plan);
  db.update("""
   INSERT INTO ds_human_grant_review(review_id,actor_user_id,actor_org_id,parent_session_id,actor_token_version,action,grant_id,payload_sha256,payload,expires_at)
   VALUES(?,?,?,?,?,?,?,?,?::jsonb,?)
   """,id,a.userId(),a.orgId(),UUID.fromString(claims().get("sid",String.class)),a.tokenVersion(),plan.get("action"),uuid(plan.get("grantId")),JobExecutionDefinitionService.sha(payload),payload,Timestamp.from(expires));
  return Map.of("reviewId",id,"expiresAt",expires.toString(),"preview",plan);
 }
 @Transactional public Map<String,Object> preview(CreateRequest req){
  caller(false);lock();var a=caller(true);return review(a,createPlan(a,UUID.randomUUID(),req,null));
 }
 @Transactional public Map<String,Object> previewRevoke(UUID grant,RevokeRequest req){
  caller(false);lock();var a=caller(true);return review(a,revokePlan(a,grant,req,null));
 }
 private <T>T convert(JsonNode n,Class<T> type){try{return json.treeToValue(n,type);}catch(Exception e){throw new IllegalStateException("GRANT_REVIEW_IDENTITY_INVALID");}}
 @SuppressWarnings("unchecked") private Map<String,Object> object(JsonNode n){return json.convertValue(n,Map.class);}
 @Transactional public void apply(UUID review,ConfirmRequest req){
  caller(false);lock();var a=caller(false);
  if(req==null||!req.confirm())fail(HttpStatus.BAD_REQUEST,"GRANT_REVIEW_CONFIRMATION_REQUIRED");
  var rows=db.queryForList("""
   SELECT * FROM ds_human_grant_review WHERE review_id=? AND actor_user_id=? AND actor_org_id=? AND parent_session_id=? AND actor_token_version=? FOR UPDATE
   """,review,a.userId(),a.orgId(),UUID.fromString(claims().get("sid",String.class)),a.tokenVersion());
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"GRANT_REVIEW_NOT_AVAILABLE");var stored=rows.get(0);
  if(stored.get("applied_at")!=null)return;
  if(!instant(stored.get("expires_at")).isAfter(Instant.now()))fail(HttpStatus.CONFLICT,"GRANT_REVIEW_EXPIRED");
  JsonNode payload=parse(stored.get("payload"));
  if(!JobExecutionDefinitionService.sha(canonical(payload)).equals(stored.get("payload_sha256").toString().trim()))fail(HttpStatus.SERVICE_UNAVAILABLE,"GRANT_REVIEW_IDENTITY_MISMATCH");
  UUID grant=(UUID)stored.get("grant_id");boolean create="CREATE".equals(stored.get("action"));var source=object(payload.path("sourceAuthority"));
  Map<String,Object> current=create?createPlan(a,grant,convert(payload.path("definition"),CreateRequest.class),source):
   revokePlan(a,grant,new RevokeRequest(payload.path("expectedVersion").asLong(),payload.path("reason").asText()),source);
  if(!canonical(current).equals(canonical(payload)))fail(HttpStatus.CONFLICT,"GRANT_REVIEW_STALE");
  if(create){
   var r=convert(payload.path("definition"),CreateRequest.class);var scope=payload.path("resource");
   db.update("""
    INSERT INTO ds_resource_grant(grant_id,subject_kind,subject_id,grantee_org_id,owner_org_id,scope_kind,dataset_id,version_id,index_version_id,
     view_authorization_version,operation_codes,purpose_codes,contract_id,contract_revision,valid_from,valid_to,created_by)
    VALUES(?,?,?,?,?,?,?,?,?,?,?::varchar[],?::varchar[],?,?,?,?,?)
    """,grant,r.subjectKind(),r.subjectId(),r.granteeOrgId(),uuid(scope.path("ownerOrgId").asText()),r.scopeKind(),r.datasetId(),r.versionId(),r.indexVersionId(),
    scope.has("viewAuthorizationVersion")?scope.path("viewAuthorizationVersion").asLong():null,"{"+String.join(",",r.operations())+"}","{"+String.join(",",selectedPurposes(r))+"}",r.contractId(),r.contractRevision(),Timestamp.from(r.validFrom()),r.validTo()==null?null:Timestamp.from(r.validTo()),a.userId());
   db.update("INSERT INTO ds_human_grant_origin(grant_id,review_id,source_authority,confirmed_by) VALUES(?,?,?::jsonb,?)",grant,review,encode(source),a.userId());
  }else{
   int changed=db.update("UPDATE ds_resource_grant SET state='REVOKED',version=version+1,revoked_at=clock_timestamp(),revoke_reason=? WHERE grant_id=? AND state='ACTIVE' AND version=?",
    "REVIEWED_HUMAN_GRANT_REVOKED",grant,payload.path("expectedVersion").asLong());
   if(changed!=1)fail(HttpStatus.CONFLICT,"GRANT_VERSION_OR_STATE_CHANGED");
  }
  db.update("INSERT INTO ds_human_grant_event(event_id,review_id,grant_id,actor_user_id,actor_org_id,action,reason_code,payload) VALUES(?,?,?,?,?,?,?,?::jsonb)",
   UUID.randomUUID(),review,grant,a.userId(),a.orgId(),create?"CREATE":"REVOKE",create?"REVIEWED_HUMAN_GRANT_CREATED":"REVIEWED_HUMAN_GRANT_REVOKED",canonical(payload));
  db.update("UPDATE ds_human_grant_review SET applied_at=clock_timestamp() WHERE review_id=?",review);
  var auditScope=new LinkedHashMap<String,Object>();auditScope.put("grantId",grant);auditScope.put("reviewId",review);
  JsonNode def=payload.path("definition");
  for(String key:List.of("datasetId","versionId","indexVersionId","viewId","subjectKind","subjectId","granteeOrgId","scopeKind","purpose","purposeCodes","contractId","contractRevision","validFrom","validTo"))
   if(!def.path(key).isMissingNode()&&!def.path(key).isNull())auditScope.put(key,def.get(key));
  audit.record(a.userId(),a.orgId(),a.platformAdmin()?MANAGEMENT:SHARE,"RESOURCE_GRANT",grant.toString(),"SUCCESS",create?"REVIEWED_HUMAN_GRANT_CREATED":"REVIEWED_HUMAN_GRANT_REVOKED",
   auditScope,create?null:Map.of("state","ACTIVE","version",payload.path("expectedVersion").asLong()),
   Map.of("state",create?"ACTIVE":"REVOKED","version",create?1:payload.path("expectedVersion").asLong()+1,"operations",def.path("operations")));
 }
 private boolean mayInspect(AuthorizationService.Actor a,CreateRequest r){
  if(a.platformAdmin())return true;
  if(!a.orgId().equals(owner(r.datasetId())))return false;
  for(String selected:selectedPurposes(r))try{sourceAuthority(a,forPurposes(r,List.of(selected)),Set.of(),null,false);return true;}catch(ResponseStatusException denied){}return false;
 }
 public Map<String,Object> grants(UUID dataset,Integer page,Integer size){
  var a=caller(true);int p=page==null?1:page,n=size==null?25:size;if(p<1||n<1||n>100)fail(HttpStatus.BAD_REQUEST,"GRANT_PAGE_INVALID");
  var permitted=new ArrayList<Map<String,Object>>();
  for(var g:db.queryForList("SELECT * FROM ds_resource_grant WHERE subject_kind IN('USER','ORG') AND (?::uuid IS NULL OR dataset_id=?) AND (? OR owner_org_id=?) ORDER BY created_at DESC,grant_id",dataset,dataset,a.platformAdmin(),a.orgId())){
   var d=definition(g);if(!mayInspect(a,d))continue;
   var out=new LinkedHashMap<String,Object>();out.put("grantId",g.get("grant_id"));out.put("version",g.get("version"));out.put("state",g.get("state"));out.put("definition",json.valueToTree(d));
   out.put("purposeCodes",Arrays.asList(array(g.get("purpose_codes"))));
   out.put("reviewed",exists("SELECT EXISTS(SELECT 1 FROM ds_human_grant_origin WHERE grant_id=?) OR EXISTS(SELECT 1 FROM ds_controlled_view_publication_grant WHERE grant_id=?)",g.get("grant_id"),g.get("grant_id")));
   out.put("sourceConstraintsCurrent",db.queryForObject("SELECT ds_human_grant_origin_current(?,ARRAY[]::uuid[])",Boolean.class,g.get("grant_id")));
   out.put("sourceCurrentOperations",db.queryForList("SELECT action.code FROM ds_resource_grant g CROSS JOIN LATERAL unnest(g.operation_codes) action(code) WHERE g.grant_id=? AND ds_human_grant_origin_current(g.grant_id,ARRAY[action.code]::varchar[],ARRAY[]::uuid[]) ORDER BY action.code",String.class,g.get("grant_id")));
   out.put("sourceDelegationDepth",db.queryForObject("SELECT ds_human_grant_origin_depth(?)",Integer.class,g.get("grant_id")));
   out.put("liveTimeWindow",!instant(g.get("valid_from")).isAfter(Instant.now())&&(g.get("valid_to")==null||instant(g.get("valid_to")).isAfter(Instant.now())));permitted.add(out);
  }
  return page(permitted,p,n);
 }
 private Map<String,Object> page(List<Map<String,Object>> rows,int p,int n){int from=(int)Math.min((long)(p-1)*n,rows.size()),to=Math.min(from+n,rows.size());return Map.of("page",p,"size",n,"total",rows.size(),"items",rows.subList(from,to));}
 public List<Map<String,Object>> organizations(){
  var a=caller(true);return db.queryForList("""
   SELECT o.org_id AS "orgId",o.org_name AS "orgName",e.entity_id AS "entityId",e.enterprise_name AS "enterpriseName"
   FROM org o LEFT JOIN ds_enterprise_identity e ON e.org_id=o.org_id
   WHERE o.org_status=1 AND (? OR o.org_id=? OR e.state='CONFIRMED') ORDER BY o.org_name,o.org_id
   """,a.platformAdmin(),a.orgId());
 }
 public List<Map<String,Object>> members(UUID organization){
  var a=caller(true);if(organization==null||(!a.platformAdmin()&&(!a.enterpriseManager()||!a.orgId().equals(organization))))fail(HttpStatus.NOT_FOUND,"GRANT_MEMBER_DIRECTORY_NOT_AVAILABLE");
  auth.requireMemberManagement(organization,"identity.member.read");
  return db.queryForList("SELECT user_id AS \"userId\",user_name AS username,permission_version AS \"permissionVersion\" FROM fwd_user WHERE org_id=? AND account_state='ACTIVE' ORDER BY user_name,user_id",organization);
 }
 public Map<String,Object> resources(String purpose,Integer page,Integer size){return resources(purpose,null,page,size);}
 public Map<String,Object> resources(String purpose,List<String> purposeCodes,Integer page,Integer size){
  var a=caller(true);var selected=selectedPurposes(purpose==null&&purposeCodes==null?"GENERAL_READ":purpose,purposeCodes);int p=page==null?1:page,n=size==null?25:size;
  if(p<1||n<1||n>100)fail(HttpStatus.BAD_REQUEST,"GRANT_PAGE_INVALID");var permitted=new ArrayList<Map<String,Object>>();
  for(var d:db.queryForList("SELECT dataset_id FROM mm_dataset WHERE status='ACTIVE' AND (? OR org_id=?) ORDER BY dataset_name,dataset_id",a.platformAdmin(),a.orgId())){
   UUID dataset=(UUID)d.get("dataset_id");
   var scopes=new ArrayList<CreateRequest>();
   if(!exists("SELECT EXISTS(SELECT 1 FROM ds_controlled_view WHERE dataset_id=?)",dataset))
    scopes.add(new CreateRequest("ORG",a.orgId(),a.orgId(),"DATASET_CUSTODY",dataset,null,null,null,List.of(SHARE),null,null,null,null,null,"LIST_RESOURCE_METADATA",selected));
   for(var v:db.queryForList("""
    SELECT v.version_id,i.index_version_id,cv.view_id FROM mm_dataset_version v
    JOIN mm_index_version i ON i.dataset_id=v.dataset_id AND i.dataset_version_id=v.version_id AND i.index_status='READY'
    LEFT JOIN ds_controlled_view cv ON cv.dataset_id=v.dataset_id AND cv.version_id=v.version_id AND cv.index_version_id=i.index_version_id
    WHERE v.dataset_id=? ORDER BY v.created_at DESC,i.created_at DESC,i.index_version_id
    """,dataset))
    scopes.add(new CreateRequest("ORG",a.orgId(),a.orgId(),v.get("view_id")==null?"FROZEN_VERSION":"CONTROLLED_VIEW",dataset,(UUID)v.get("version_id"),(UUID)v.get("index_version_id"),(UUID)v.get("view_id"),List.of(SHARE),null,null,null,null,null,"LIST_RESOURCE_METADATA",selected));
   for(var request:scopes)try{if(!a.platformAdmin())sourceAuthority(a,request,Set.of(),null,false);var scope=resource(request);scope.put("purposeCodes",selected);permitted.add(scope);}catch(ResponseStatusException unavailable){}
  }
  return page(permitted,p,n);
 }
 private Map<String,Object> originImpact(AuthorizationService.Actor a,UUID grant){
  String closure="""
   WITH RECURSIVE affected(grant_id,grant_version,path) AS (
    SELECT grant_id,version,ARRAY[grant_id] FROM ds_resource_grant WHERE grant_id=?
    UNION ALL
    SELECT child.grant_id,child.version,array_append(parent.path,child.grant_id)
    FROM affected parent JOIN ds_human_grant_origin origin
     ON origin.source_authority->>'kind'='ORIGINAL_PROVIDER_GRANT'
      AND origin.source_authority->>'grantId'=parent.grant_id::text
      AND origin.source_authority->>'version'=parent.grant_version::text
    JOIN ds_resource_grant child ON child.grant_id=origin.grant_id
    WHERE cardinality(parent.path)<33 AND NOT child.grant_id=ANY(parent.path)
   )
   """;
  Long records=db.queryForObject(closure+" SELECT count(*) FROM affected WHERE grant_id<>?",Long.class,grant,grant);
  Long subjects=db.queryForObject(closure+" SELECT count(DISTINCT (g.subject_kind,g.subject_id)) FROM affected a JOIN ds_resource_grant g ON g.grant_id=a.grant_id WHERE g.grant_id<>?",Long.class,grant,grant);
  var tasks=db.queryForList(closure+"""
   SELECT DISTINCT r.job_id AS "jobId",r.run_id AS "runId",r.org_id AS "orgId",r.run_status::text AS "status"
   FROM affected a JOIN ds_job_execution_definition d ON EXISTS(
    SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(d.definition->'bindings')='array' THEN d.definition->'bindings' ELSE '[]'::jsonb END) b
    WHERE b->>'grantId'=a.grant_id::text AND b->>'grantVersion'=a.grant_version::text)
   JOIN job_run r ON r.run_id=d.run_id
   WHERE r.run_status IN('QUEUED','RUNNING') AND jsonb_exists(d.definition,'ioManifest')
   ORDER BY r.run_id
   """,grant);
  var visible=new ArrayList<Map<String,Object>>();
  for(var row:tasks)if(a.platformAdmin()||a.orgId().equals(row.get("orgId"))){
   var safe=new LinkedHashMap<String,Object>();for(String key:List.of("jobId","runId","status"))safe.put(key,row.get(key));visible.add(safe);
  }
  return Map.of("dependentGrantRecordCount",records,"dependentSubjectCount",subjects,"genericActiveTaskCount",tasks.size(),"visibleGenericTasks",visible);
 }

}
