package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fwdrobo.sirius.service.*;
import com.fwdrobo.sirius.util.PathUtils;
import io.jsonwebtoken.Claims;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Administrators configure identity metadata; source bytes still require explicit raw rights. */
@Service
public class EnterpriseCatalogManagement {
 private static final String OP="identity.enterprise.manage";
 private final JdbcTemplate jdbc;private final AuthorizationService auth;private final IdentitySessionService sessions;
 private final AuthorizationAuditService audit;private final ObjectMapper json;private final FileService files;private final ManifestService manifests;
 public EnterpriseCatalogManagement(JdbcTemplate jdbc,AuthorizationService auth,IdentitySessionService sessions,
   AuthorizationAuditService audit,ObjectMapper json,FileService files,ManifestService manifests){
  this.jdbc=jdbc;this.auth=auth;this.sessions=sessions;this.audit=audit;this.json=json;this.files=files;this.manifests=manifests;
 }
 public record ImportRequest(UUID datasetId,UUID versionId,String path){}
 public record MappingRequest(UUID catalogRevisionId,String entityId,String identityKind,Long expectedOrgVersion,Long expectedMappingVersion,String reason){}
 public record DisableRequest(Long expectedOrgVersion,Long expectedMappingVersion,String reason){}
 public record ConfirmRequest(UUID reviewId,boolean confirm){}
 private static void fail(HttpStatus status,String code){throw new ResponseStatusException(status,code);}
 private boolean exists(String sql,Object... values){return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,values));}
 private Claims claims(){
  if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes web)||
    !(web.getRequest().getAttribute("ds.accessClaims") instanceof Claims))fail(HttpStatus.UNAUTHORIZED,"INTERACTIVE_SESSION_REQUIRED");
  return (Claims)((ServletRequestAttributes)RequestContextHolder.getRequestAttributes()).getRequest().getAttribute("ds.accessClaims");
 }
 private AuthorizationService.Actor manager(boolean protectedResponse){
  var a=sessions.authenticateAccess(claims());auth.requirePlatformManagement(OP);
  if(protectedResponse&&RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes web)
   web.getRequest().setAttribute(AuthorizationService.REQUEST_OPERATION,OP);
  return a;
 }
 private void lock(){auth.lockIdentityMutation();jdbc.execute("SELECT pg_advisory_xact_lock(805014630400001::bigint)");}
 private String encode(Object value){
  try{return json.writeValueAsString(value);}catch(Exception invalid){throw new IllegalStateException("ENTERPRISE_METADATA_ENCODING_FAILED");}
 }
 private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(Exception invalid){throw new IllegalStateException("ENTERPRISE_METADATA_NOT_READABLE");}}
 private JsonNode sorted(JsonNode value){
  if(value.isObject()){var out=json.createObjectNode();var keys=new TreeSet<String>();value.fieldNames().forEachRemaining(keys::add);for(String key:keys)out.set(key,sorted(value.get(key)));return out;}
  if(value.isArray()){var out=json.createArrayNode();value.forEach(v->out.add(sorted(v)));return out;}
  return value;
 }
 private String canonical(Object value){return encode(sorted(value instanceof JsonNode n?n:json.valueToTree(value)));}
 private String hash(Object value){return JobExecutionDefinitionService.sha(canonical(value));}
 private static String reason(String value){
  if(value==null||value.trim().length()<6||value.length()>500||value.indexOf('\0')>=0)fail(HttpStatus.BAD_REQUEST,"ENTERPRISE_CONFIRMATION_REASON_REQUIRED");
  return value.trim();
 }
 private Map<String,Object> org(UUID id){
  var rows=jdbc.queryForList("SELECT org_id,org_name,org_status,permission_version FROM org WHERE org_id=?",id);
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"ENTERPRISE_NOT_AVAILABLE");return rows.get(0);
 }
 private Map<String,Object> mapping(UUID id){
  var rows=jdbc.queryForList("SELECT * FROM ds_enterprise_identity WHERE org_id=?",id);
  return rows.isEmpty()?null:rows.get(0);
 }
 private Map<String,Object> mappingView(Map<String,Object> value){
  if(value==null)return null;
  var out=new LinkedHashMap<String,Object>();
  for(var pair:Map.ofEntries(Map.entry("mappingId","mapping_id"),Map.entry("entityId","entity_id"),Map.entry("enterpriseName","enterprise_name"),
   Map.entry("industryKind","industry_kind"),Map.entry("identityKind","identity_kind"),Map.entry("state","state"),Map.entry("mappingVersion","version"),
   Map.entry("catalogRevisionId","catalog_revision_id"),Map.entry("sourceDatasetId","source_dataset_id"),Map.entry("sourceVersionId","source_version_id"),
   Map.entry("sourceSha256","source_sha256")).entrySet())out.put(pair.getKey(),value.get(pair.getValue()));
  return out;
 }
 public List<Map<String,Object>> organizations(){
  manager(true);
  return jdbc.queryForList("""
   SELECT o.org_id AS "orgId",o.org_name AS "orgName",o.org_status AS status,o.permission_version AS "orgVersion",
    e.mapping_id AS "mappingId",e.entity_id AS "entityId",e.enterprise_name AS "enterpriseName",e.industry_kind AS "industryKind",
    e.identity_kind AS "identityKind",e.state AS "mappingState",COALESCE(e.version,0) AS "mappingVersion",e.catalog_revision_id AS "catalogRevisionId",
    EXISTS(SELECT 1 FROM ds_enterprise_catalog_custodian p WHERE p.org_id=o.org_id) AS "catalogCustodian",
    (SELECT count(*) FROM fwd_user u WHERE u.org_id=o.org_id AND u.account_state<>'CLOSED') AS "memberCount"
   FROM org o LEFT JOIN ds_enterprise_identity e ON e.org_id=o.org_id ORDER BY o.org_name,o.org_id
   """);
 }
 public List<Map<String,Object>> catalogs(){
  manager(true);
  return jdbc.queryForList("""
   SELECT c.catalog_revision_id AS "catalogRevisionId",c.source_dataset_id AS "sourceDatasetId",c.source_version_id AS "sourceVersionId",
    d.dataset_name AS "datasetName",v.version_name AS "versionName",c.source_logical_path AS path,c.source_sha256 AS "sourceSha256",
    c.company_count AS "companyCount",c.node_count AS "nodeCount",c.imported_at AS "importedAt",
    (SELECT jsonb_agg(jsonb_build_object('entityId',e.entity_id,'name',e.enterprise_name,'industryKind',e.industry_kind) ORDER BY e.entity_id)
     FROM ds_enterprise_catalog_entry e WHERE e.catalog_revision_id=c.catalog_revision_id)::text AS "entriesJson"
   FROM ds_enterprise_catalog_revision c JOIN mm_dataset d ON d.dataset_id=c.source_dataset_id
   JOIN mm_dataset_version v ON v.version_id=c.source_version_id ORDER BY c.imported_at DESC,c.catalog_revision_id
   """).stream().map(row->{row.put("entries",parse(row.remove("entriesJson")));return row;}).toList();
 }
 public List<Map<String,Object>> sources(){
  var a=manager(true);var out=new ArrayList<Map<String,Object>>();
  for(var row:jdbc.queryForList("""
   SELECT d.dataset_id AS "datasetId",d.dataset_name AS "datasetName",v.version_id AS "versionId",v.version_name AS "versionName"
   FROM mm_dataset d JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
   JOIN artifact_commit c ON c.repo_id=d.raw_repo_id AND c.commit_id=v.raw_commit_id AND c.commit_status='PUBLISHED'
   WHERE d.status='ACTIVE' ORDER BY d.dataset_name,v.created_at DESC,v.version_id
   """)){
   row.put("canImport",a.operations().contains("data.raw.download")&&auth.canUseDataset(a,(UUID)row.get("datasetId"),(UUID)row.get("versionId"),null,"data.raw.download","GENERAL_READ"));
   out.add(row);
  }
  return out;
 }
 private Map<String,Object> sourceAuthority(AuthorizationService.Actor a,UUID dataset,UUID version){
  for(var row:jdbc.queryForList("SELECT grant_id,version FROM ds_resource_grant WHERE dataset_id=? AND state='ACTIVE' ORDER BY grant_id",dataset)){
   var binding=new AuthorizationService.GrantBinding((UUID)row.get("grant_id"),((Number)row.get("version")).longValue());
   if(auth.canUseDatasetBound(a,dataset,version,null,Set.of("data.raw.download"),"GENERAL_READ",binding))
    return Map.of("kind","GRANT","grantId",binding.grantId().toString(),"version",binding.version());
  }
  auth.requireDataset(a,dataset,version,null,"data.raw.download","GENERAL_READ");
  var rows=jdbc.queryForList("""
   SELECT c.contract_id,c.revision FROM ds_contract c JOIN ds_product_version p ON p.product_version_id=c.product_version_id
    JOIN mm_dataset d ON d.dataset_id=p.dataset_id AND d.org_id=c.provider_org_id
    JOIN mm_dataset_version v ON v.version_id=p.version_id AND v.dataset_id=d.dataset_id
    JOIN mm_index_version i ON i.index_version_id=p.index_version_id AND i.dataset_id=d.dataset_id AND i.dataset_version_id=v.version_id
    JOIN artifact_commit raw ON raw.repo_id=d.raw_repo_id AND raw.commit_id=v.raw_commit_id AND raw.commit_status='PUBLISHED'
    JOIN artifact_commit idx ON idx.repo_id=i.index_repo_id AND idx.commit_id=i.index_commit_id AND idx.commit_status='PUBLISHED'
   WHERE c.consumer_org_id=? AND c.scope_type='FULL_VERSION' AND c.status='CONFIRMED' AND c.revoked_at IS NULL
    AND c.provider_confirmed_at IS NOT NULL AND c.consumer_confirmed_at IS NOT NULL AND c.valid_from<=clock_timestamp() AND clock_timestamp()<c.valid_to
    AND p.dataset_id=? AND p.version_id=? AND p.raw_commit_id=v.raw_commit_id AND i.index_status='READY'
    AND EXISTS(SELECT 1 FROM ds_contract_operation op WHERE op.contract_id=c.contract_id AND op.contract_revision=c.revision
      AND op.operation_code='data.raw.download' AND 'GENERAL_READ'=ANY(op.purpose_codes))
   ORDER BY c.contract_id
   """,a.orgId(),dataset,version);
  if(rows.isEmpty())fail(HttpStatus.NOT_FOUND,"CATALOG_SOURCE_AUTHORITY_NOT_AVAILABLE");
  return Map.of("kind","LEGACY_CONTRACT","contractId",rows.get(0).get("contract_id").toString(),"revision",((Number)rows.get(0).get("revision")).intValue());
 }
 private void currentSource(AuthorizationService.Actor a,UUID dataset,UUID version,Map<String,Object> binding){
  manager(false);
  if("GRANT".equals(binding.get("kind"))){
   var ref=new AuthorizationService.GrantBinding(UUID.fromString((String)binding.get("grantId")),((Number)binding.get("version")).longValue());
   if(!auth.canUseDatasetBound(a,dataset,version,null,Set.of("data.raw.download"),"GENERAL_READ",ref))fail(HttpStatus.NOT_FOUND,"CATALOG_SOURCE_AUTHORITY_CHANGED");
  }else{
   auth.requireDataset(a,dataset,version,null,"data.raw.download","GENERAL_READ");
   if(!exists("""
    SELECT EXISTS(SELECT 1 FROM ds_contract c JOIN ds_contract_operation op ON op.contract_id=c.contract_id AND op.contract_revision=c.revision
     WHERE c.contract_id=? AND c.revision=? AND c.status='CONFIRMED' AND c.revoked_at IS NULL
      AND c.provider_confirmed_at IS NOT NULL AND c.consumer_confirmed_at IS NOT NULL AND c.consumer_org_id=?
      AND c.valid_from<=clock_timestamp() AND clock_timestamp()<c.valid_to AND op.operation_code='data.raw.download' AND 'GENERAL_READ'=ANY(op.purpose_codes))
    """,UUID.fromString((String)binding.get("contractId")),((Number)binding.get("revision")).intValue(),a.orgId()))
    fail(HttpStatus.NOT_FOUND,"CATALOG_SOURCE_AUTHORITY_CHANGED");
  }
 }
 @Transactional public Map<String,Object> importCatalog(ImportRequest req)throws Exception{
  var a=manager(true);
  if(req==null||req.datasetId()==null||req.versionId()==null||req.path()==null)fail(HttpStatus.BAD_REQUEST,"CATALOG_SOURCE_REQUIRED");
  String path=PathUtils.normalizePath(req.path());
  if(!path.substring(path.lastIndexOf('/')+1).equals("organizations.csv"))fail(HttpStatus.BAD_REQUEST,"ENTERPRISE_MASTER_FILE_REQUIRED");
  var rows=jdbc.queryForList("""
   SELECT d.org_id,d.raw_repo_id,v.raw_commit_id FROM mm_dataset d JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
   JOIN artifact_commit c ON c.repo_id=d.raw_repo_id AND c.commit_id=v.raw_commit_id AND c.commit_status='PUBLISHED'
   WHERE d.dataset_id=? AND v.version_id=? AND d.status='ACTIVE'
   """,req.datasetId(),req.versionId());
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"CATALOG_SOURCE_NOT_AVAILABLE");
  var src=rows.get(0);UUID repo=(UUID)src.get("raw_repo_id"),commit=(UUID)src.get("raw_commit_id");
  var binding=sourceAuthority(a,req.datasetId(),req.versionId());
  UUID file;long expectedSize;String expectedSha;byte[] bytes;
  try(var fixed=auth.enterOperation("data.raw.download","GENERAL_READ")){
   var manifest=manifests.getManifest(repo,commit);
   var matches=manifest.getFiles().stream().filter(item->path.equals(item.getPath())).toList();
   if(matches.size()!=1)fail(HttpStatus.NOT_FOUND,"CATALOG_SOURCE_FILE_NOT_AVAILABLE");
   file=matches.get(0).getFileId();
   var identities=jdbc.queryForList("SELECT size_bytes,btrim(sha256) AS sha,upload_state FROM artifact_file WHERE file_id=?",file);
   if(identities.size()!=1)fail(HttpStatus.NOT_FOUND,"CATALOG_SOURCE_FILE_NOT_AVAILABLE");
   var identity=identities.get(0);
   if(!(identity.get("size_bytes") instanceof Number))fail(HttpStatus.SERVICE_UNAVAILABLE,"CATALOG_SOURCE_INTEGRITY_NOT_READY");
   expectedSize=((Number)identity.get("size_bytes")).longValue();expectedSha=Objects.toString(identity.get("sha"),"").trim().toLowerCase(Locale.ROOT);
   if(expectedSize<1||expectedSize>1048576)fail(HttpStatus.PAYLOAD_TOO_LARGE,"ENTERPRISE_CATALOG_SIZE_LIMIT");
   if(!"READY".equals(identity.get("upload_state"))||!expectedSha.matches("[a-f0-9]{64}"))fail(HttpStatus.CONFLICT,"CATALOG_SOURCE_INTEGRITY_NOT_READY");
   try(var stream=files.downloadContent(repo,commit,path)){
    currentSource(a,req.datasetId(),req.versionId(),binding);
    if(stream.size()!=expectedSize)fail(HttpStatus.SERVICE_UNAVAILABLE,"CATALOG_SOURCE_IDENTITY_CHANGED");
    bytes=stream.inputStream().readNBytes(1048577);
   }
  }
  String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  if(bytes.length!=expectedSize||!actual.equals(expectedSha))fail(HttpStatus.SERVICE_UNAVAILABLE,"CATALOG_SOURCE_IDENTITY_CHANGED");
  var parsed=EnterpriseCatalogCsv.parse(bytes);currentSource(a,req.datasetId(),req.versionId(),binding);
  // No identity lock or resource fence spans source-object I/O.
  lock();manager(true);currentSource(a,req.datasetId(),req.versionId(),binding);
  var existing=jdbc.queryForList("SELECT catalog_revision_id FROM ds_enterprise_catalog_revision WHERE source_dataset_id=? AND source_version_id=? AND source_logical_path=? AND source_sha256=?",UUID.class,req.datasetId(),req.versionId(),path,actual);
  if(!existing.isEmpty())return Map.of("catalogRevisionId",existing.get(0),"companyCount",parsed.companies().size(),"nodeCount",parsed.nodeCount(),"alreadyImported",true,"difference",difference(existing.get(0)));
  UUID id=UUID.randomUUID();String entriesHash=hash(parsed.companies());
  jdbc.update("""
   INSERT INTO ds_enterprise_catalog_revision(catalog_revision_id,source_dataset_id,source_version_id,source_raw_commit_id,source_file_id,
    source_logical_path,source_sha256,source_size_bytes,entries_sha256,company_count,node_count,parser_version,source_authority,parent_session_id,imported_by)
   VALUES(?,?,?,?,?,?,?,?,?,?,?,'enterprise-master-v1',?::jsonb,?,?)
   """,id,req.datasetId(),req.versionId(),commit,file,path,actual,bytes.length,entriesHash,parsed.companies().size(),parsed.nodeCount(),encode(binding),UUID.fromString(claims().get("sid",String.class)),a.userId());
  for(var company:parsed.companies())jdbc.update("INSERT INTO ds_enterprise_catalog_entry(catalog_revision_id,entity_id,enterprise_name,industry_kind,source_row) VALUES(?,?,?,?,?)",
   id,company.entityId(),company.name(),company.industryKind(),company.sourceRow());
  if(parsed.companies().size()>1&&!exists("SELECT EXISTS(SELECT 1 FROM ds_enterprise_identity WHERE org_id=?)",src.get("org_id")))
   jdbc.update("INSERT INTO ds_enterprise_catalog_custodian(org_id,catalog_revision_id,source_dataset_id) VALUES(?,?,?) ON CONFLICT(org_id) DO NOTHING",src.get("org_id"),id,req.datasetId());
  audit.record(a.userId(),a.orgId(),OP,"ENTERPRISE_CATALOG",id.toString(),"SUCCESS","VERIFIED_ENTERPRISE_CATALOG_IMPORTED",
   Map.of("sourceDatasetId",req.datasetId(),"sourceVersionId",req.versionId(),"sourceSha256",actual),null,Map.of("count",parsed.companies().size(),"catalogRevisionId",id));
  return Map.of("catalogRevisionId",id,"companyCount",parsed.companies().size(),"nodeCount",parsed.nodeCount(),"sourceSha256",actual,"alreadyImported",false,"difference",difference(id));
 }
 private Map<String,Object> difference(UUID id){
  var refs=jdbc.queryForList("""
   SELECT previous.catalog_revision_id FROM ds_enterprise_catalog_revision current
   JOIN ds_enterprise_catalog_revision previous ON previous.source_dataset_id=current.source_dataset_id
    AND previous.source_logical_path=current.source_logical_path AND previous.imported_at<current.imported_at
   WHERE current.catalog_revision_id=? ORDER BY previous.imported_at DESC,previous.catalog_revision_id LIMIT 1
   """,UUID.class,id);
  UUID previous=refs.isEmpty()?null:refs.get(0);var old=new TreeMap<String,Map<String,Object>>();var now=new TreeMap<String,Map<String,Object>>();
  if(previous!=null)for(var row:jdbc.queryForList("SELECT entity_id,enterprise_name,industry_kind FROM ds_enterprise_catalog_entry WHERE catalog_revision_id=?",previous))old.put(row.get("entity_id").toString(),row);
  for(var row:jdbc.queryForList("SELECT entity_id,enterprise_name,industry_kind FROM ds_enterprise_catalog_entry WHERE catalog_revision_id=?",id))now.put(row.get("entity_id").toString(),row);
  var added=new ArrayList<String>();var removed=new ArrayList<String>();var changed=new ArrayList<String>();
  now.forEach((key,value)->{if(!old.containsKey(key))added.add(key);else if(!value.equals(old.get(key)))changed.add(key);});
  old.keySet().stream().filter(key->!now.containsKey(key)).forEach(removed::add);
  var out=new LinkedHashMap<String,Object>();out.put("previousCatalogRevisionId",previous);out.put("addedEntityIds",added);out.put("removedEntityIds",removed);out.put("changedEntityIds",changed);return out;
 }
 private Map<String,Object> impact(UUID org){
  var out=new TreeMap<String,Object>();
  out.put("memberCount",jdbc.queryForObject("SELECT count(*) FROM fwd_user WHERE org_id=? AND account_state<>'CLOSED'",Long.class,org));
  out.put("datasetCount",jdbc.queryForObject("SELECT count(*) FROM mm_dataset WHERE org_id=?",Long.class,org));
  out.put("contractCount",jdbc.queryForObject("SELECT count(*) FROM ds_contract WHERE provider_org_id=? OR consumer_org_id=?",Long.class,org,org));
  out.put("grantCount",jdbc.queryForObject("SELECT count(*) FROM ds_resource_grant WHERE owner_org_id=? OR grantee_org_id=?",Long.class,org,org));
  return out;
 }
 private Map<String,Object> plan(UUID target,MappingRequest req,String action,DisableRequest disable){
  var org=org(target);var old=mapping(target);Long expectedOrg=req==null?disable.expectedOrgVersion():req.expectedOrgVersion();
  Long expectedMap=req==null?disable.expectedMappingVersion():req.expectedMappingVersion();
  if(expectedOrg==null||expectedMap==null)fail(HttpStatus.PRECONDITION_REQUIRED,"ENTERPRISE_MAPPING_VERSIONS_REQUIRED");
  long version=old==null?0:((Number)old.get("version")).longValue();
  if(expectedOrg.longValue()!=((Number)org.get("permission_version")).longValue()||expectedMap!=version)fail(HttpStatus.CONFLICT,"ENTERPRISE_MAPPING_VERSION_CHANGED");
  if(((Number)org.get("org_status")).intValue()!=1)fail(HttpStatus.CONFLICT,"ENTERPRISE_ORGANIZATION_DISABLED");
  var out=new TreeMap<String,Object>();out.put("action",action);out.put("orgId",target.toString());out.put("orgName",org.get("org_name"));
  out.put("orgVersion",expectedOrg);out.put("mappingVersion",version);out.put("before",mappingView(old));
  var affected=impact(target);out.put("impact",affected);
  if("DISABLE".equals(action)){
   if(old==null||!"CONFIRMED".equals(old.get("state")))fail(HttpStatus.CONFLICT,"CONFIRMED_ENTERPRISE_MAPPING_REQUIRED");
   out.put("reason",reason(disable.reason()));var after=new TreeMap<>(mappingView(old));after.put("state","DISABLED");out.put("after",after);return out;
  }
  if(req.catalogRevisionId()==null||req.entityId()==null||!Set.of("BUSINESS","TEST").contains(Objects.toString(req.identityKind(),"")))fail(HttpStatus.BAD_REQUEST,"ENTERPRISE_MAPPING_FIELDS_REQUIRED");
  if(exists("SELECT EXISTS(SELECT 1 FROM ds_enterprise_catalog_custodian WHERE org_id=?)",target))fail(HttpStatus.CONFLICT,"CATALOG_CUSTODIAN_ORG_MUST_REMAIN_SEPARATE");
  var entries=jdbc.queryForList("""
   SELECT e.entity_id,e.enterprise_name,e.industry_kind,c.source_dataset_id,c.source_version_id,c.source_sha256
   FROM ds_enterprise_catalog_entry e JOIN ds_enterprise_catalog_revision c ON c.catalog_revision_id=e.catalog_revision_id
   WHERE e.catalog_revision_id=? AND e.entity_id=?
   """,req.catalogRevisionId(),req.entityId());
  if(entries.size()!=1)fail(HttpStatus.NOT_FOUND,"VERIFIED_ENTERPRISE_ENTRY_NOT_AVAILABLE");
  var entry=entries.get(0);
  if(old!=null&&(!Objects.equals(old.get("entity_id"),req.entityId())||!Objects.equals(old.get("identity_kind"),req.identityKind())||!Objects.equals(old.get("industry_kind"),entry.get("industry_kind")))&&
   List.of("datasetCount","contractCount","grantCount").stream().anyMatch(k->((Number)affected.get(k)).longValue()>0))
   fail(HttpStatus.CONFLICT,"ENTERPRISE_REBIND_REQUIRES_RESOURCE_MIGRATION");
  if("BUSINESS".equals(req.identityKind())&&exists("SELECT EXISTS(SELECT 1 FROM ds_enterprise_identity WHERE entity_id=? AND identity_kind='BUSINESS' AND state='CONFIRMED' AND org_id<>?)",req.entityId(),target))
   fail(HttpStatus.CONFLICT,"BUSINESS_ENTERPRISE_ALREADY_MAPPED");
  var after=new TreeMap<String,Object>();after.put("entityId",req.entityId());after.put("enterpriseName",entry.get("enterprise_name"));after.put("industryKind",entry.get("industry_kind"));
  after.put("identityKind",req.identityKind());after.put("state","CONFIRMED");after.put("catalogRevisionId",req.catalogRevisionId().toString());
  after.put("sourceDatasetId",entry.get("source_dataset_id").toString());after.put("sourceVersionId",entry.get("source_version_id").toString());after.put("sourceSha256",entry.get("source_sha256").toString().trim());
  out.put("reason",reason(req.reason()));out.put("after",after);return out;
 }
 @Transactional public Map<String,Object> preview(UUID org,MappingRequest req){
  if(req==null)fail(HttpStatus.BAD_REQUEST,"ENTERPRISE_MAPPING_FIELDS_REQUIRED");
  manager(false);lock();var a=manager(true);var payload=plan(org,req,"CONFIRM",null);return review(a,org,"CONFIRM",payload);
 }
 @Transactional public Map<String,Object> previewDisable(UUID org,DisableRequest req){
  if(req==null)fail(HttpStatus.BAD_REQUEST,"ENTERPRISE_MAPPING_FIELDS_REQUIRED");
  manager(false);lock();var a=manager(true);return review(a,org,"DISABLE",plan(org,null,"DISABLE",req));
 }
 private Map<String,Object> review(AuthorizationService.Actor a,UUID org,String action,Map<String,Object> payload){
  UUID id=UUID.randomUUID();Instant expires=Instant.now().plusSeconds(300);String encoded=canonical(payload);
  jdbc.update("""
   INSERT INTO ds_enterprise_mapping_review(review_id,actor_user_id,actor_org_id,parent_session_id,actor_token_version,target_org_id,action,payload_sha256,payload,expires_at)
   VALUES(?,?,?,?,?,?,?,?,?::jsonb,?)
   """,id,a.userId(),a.orgId(),UUID.fromString(claims().get("sid",String.class)),a.tokenVersion(),org,action,JobExecutionDefinitionService.sha(encoded),encoded,Timestamp.from(expires));
  var response=new LinkedHashMap<String,Object>();response.put("reviewId",id);response.put("expiresAt",expires.toString());response.put("preview",payload);return response;
 }
 @Transactional public void apply(UUID target,ConfirmRequest req){
  manager(false);lock();var a=manager(false);
  if(req==null||req.reviewId()==null||!req.confirm())fail(HttpStatus.BAD_REQUEST,"ENTERPRISE_MAPPING_CONFIRMATION_REQUIRED");
  var rows=jdbc.queryForList("SELECT * FROM ds_enterprise_mapping_review WHERE review_id=? AND target_org_id=? AND actor_user_id=? AND actor_org_id=? AND parent_session_id=? AND actor_token_version=? FOR UPDATE",
   req.reviewId(),target,a.userId(),a.orgId(),UUID.fromString(claims().get("sid",String.class)),a.tokenVersion());
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"ENTERPRISE_MAPPING_REVIEW_NOT_AVAILABLE");var r=rows.get(0);
  if(r.get("applied_at")!=null)return;
  if(!((Timestamp)r.get("expires_at")).toInstant().isAfter(Instant.now()))fail(HttpStatus.CONFLICT,"ENTERPRISE_MAPPING_REVIEW_EXPIRED");
  JsonNode stored=parse(r.get("payload"));
  if(!JobExecutionDefinitionService.sha(canonical(stored)).equals(r.get("payload_sha256").toString().trim()))fail(HttpStatus.SERVICE_UNAVAILABLE,"ENTERPRISE_MAPPING_REVIEW_IDENTITY_MISMATCH");
  JsonNode after=stored.path("after");String action=(String)r.get("action");
  MappingRequest mappingReq="CONFIRM".equals(action)?new MappingRequest(UUID.fromString(after.path("catalogRevisionId").asText()),after.path("entityId").asText(),after.path("identityKind").asText(),
   stored.path("orgVersion").asLong(),stored.path("mappingVersion").asLong(),stored.path("reason").asText()):null;
  DisableRequest disable="DISABLE".equals(action)?new DisableRequest(stored.path("orgVersion").asLong(),stored.path("mappingVersion").asLong(),stored.path("reason").asText()):null;
  var current=plan(target,mappingReq,action,disable);
  if(!canonical(current).equals(canonical(stored)))fail(HttpStatus.CONFLICT,"ENTERPRISE_MAPPING_REVIEW_STALE");
  var before=mapping(target);UUID mappingId=before==null?UUID.randomUUID():(UUID)before.get("mapping_id");
  long next=stored.path("mappingVersion").asLong()+1;
  if("CONFIRM".equals(action)){
   jdbc.update("""
    INSERT INTO ds_enterprise_identity(mapping_id,org_id,entity_id,enterprise_name,industry_kind,identity_kind,state,version,
     source_dataset_id,source_version_id,source_sha256,confirmation_reason,confirmed_by,confirmed_at,catalog_revision_id)
    VALUES(?,?,?,?,?,?,'CONFIRMED',?,?,?,?,?,?,clock_timestamp(),?)
    ON CONFLICT(org_id) DO UPDATE SET entity_id=EXCLUDED.entity_id,enterprise_name=EXCLUDED.enterprise_name,
     industry_kind=EXCLUDED.industry_kind,identity_kind=EXCLUDED.identity_kind,state='CONFIRMED',version=EXCLUDED.version,
     source_dataset_id=EXCLUDED.source_dataset_id,source_version_id=EXCLUDED.source_version_id,source_sha256=EXCLUDED.source_sha256,
     confirmation_reason=EXCLUDED.confirmation_reason,confirmed_by=EXCLUDED.confirmed_by,confirmed_at=EXCLUDED.confirmed_at,catalog_revision_id=EXCLUDED.catalog_revision_id
    """,mappingId,target,after.path("entityId").asText(),after.path("enterpriseName").asText(),after.path("industryKind").asText(),after.path("identityKind").asText(),next,
    UUID.fromString(after.path("sourceDatasetId").asText()),UUID.fromString(after.path("sourceVersionId").asText()),after.path("sourceSha256").asText(),stored.path("reason").asText(),a.userId(),UUID.fromString(after.path("catalogRevisionId").asText()));
  }else jdbc.update("UPDATE ds_enterprise_identity SET state='DISABLED',version=?,confirmation_reason=? WHERE org_id=?",next,stored.path("reason").asText(),target);
  jdbc.update("UPDATE org SET permission_version=permission_version+1 WHERE org_id=?",target);
  for(UUID uid:jdbc.queryForList("SELECT user_id FROM fwd_user WHERE org_id=? AND account_state<>'CLOSED' ORDER BY user_id",UUID.class,target))
   sessions.revokeAll(uid,"ENTERPRISE_MAPPING_CHANGED");
  jdbc.update("UPDATE ds_auth_initialization SET policy_version=policy_version+1,updated_at=clock_timestamp() WHERE singleton");
  var result=mappingView(mapping(target));String beforeJson=before==null?null:encode(mappingView(before));
  jdbc.update("INSERT INTO ds_enterprise_mapping_event(event_id,review_id,actor_user_id,actor_org_id,target_org_id,mapping_id,mapping_version,reason_code,before_value,after_value) VALUES(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)",
   UUID.randomUUID(),req.reviewId(),a.userId(),a.orgId(),target,mappingId,next,"CONFIRM".equals(action)?"ENTERPRISE_MAPPING_CONFIRMED":"ENTERPRISE_MAPPING_DISABLED",beforeJson,encode(result));
  jdbc.update("UPDATE ds_enterprise_mapping_review SET applied_at=clock_timestamp(),result_mapping_version=? WHERE review_id=?",next,req.reviewId());
  audit.record(a.userId(),a.orgId(),OP,"ENTERPRISE_MAPPING",mappingId.toString(),"SUCCESS","ENTERPRISE_MAPPING_REVIEW_APPLIED",
   Map.of("orgId",target,"mappingId",mappingId,"mappingVersion",next),before==null?null:mappingView(before),result);
 }
}
