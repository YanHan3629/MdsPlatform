package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fwdrobo.sirius.service.ManifestService;
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
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/**
 * Freezes provider proposals, not approvals. Source bytes are parsed only by the
 * fixed worker; publication requires a later verified candidate and review.
 */
@Service
public class ControlledViewProposalManagement {
 private static final String SHARE="dataset.share.manage",RAW="data.raw.download",PURPOSE="VIEW_BUILD";
 private static final Set<String> ACTIONS=Set.of(SHARE);
 public static final String INTERNAL_BUILD="FIXED_PRIVATE_VIEW_BUILD_V2";
 /** Missing protocol retains the original RAW-dependent authority of historic proposals. */
 public static boolean internalBuild(JsonNode authority){
  if(!authority.has("buildProtocol"))return false;
  if(!INTERNAL_BUILD.equals(authority.path("buildProtocol").asText()))fail(HttpStatus.SERVICE_UNAVAILABLE,"VIEW_BUILD_PROTOCOL_NOT_SUPPORTED");return true;
 }
 public static Set<String> sourceOperations(JsonNode authority){return internalBuild(authority)?Set.of(SHARE):Set.of(SHARE,RAW);}

 private static final Set<String> FORMATS=Set.of("csv","xlsx","json","jsonl","txt","pdf","png","jpg","jpeg","svg","obj","step","stp");
 private final JdbcTemplate db;private final AuthorizationService auth;private final IdentitySessionService sessions;
 private final AuthorizationAuditService audit;private final ManifestService manifests;private final ObjectMapper json;
 private final ControlledViewWorkerScriptFactory scripts;private final ControlledViewSourceLineage lineage;
 public ControlledViewProposalManagement(JdbcTemplate db,AuthorizationService auth,IdentitySessionService sessions,
  AuthorizationAuditService audit,ManifestService manifests,ObjectMapper json,ControlledViewWorkerScriptFactory scripts,ControlledViewSourceLineage lineage){
  this.db=db;this.auth=auth;this.sessions=sessions;this.audit=audit;this.manifests=manifests;this.json=json;this.scripts=scripts;this.lineage=lineage;
 }
 public record CreateRequest(UUID datasetId,UUID versionId,UUID indexVersionId,UUID targetOrgId,JsonNode files,String reason,String scopeMode){}
 public record WithdrawRequest(Long expectedVersion,String reason){}
 private static void fail(HttpStatus status,String code){throw new ResponseStatusException(status,code);}
 private static long number(Object value){return ((Number)value).longValue();}
 private static UUID uuid(Object value){return value==null?null:UUID.fromString(value.toString());}
 private static String sha(byte[] bytes){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
  catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
 }
 private static byte[] canonical(JsonNode value){return ControlledViewWorkerScriptFactory.canonical(value);}
 private String canonicalText(Object value){return new String(canonical(value instanceof JsonNode n?n:json.valueToTree(value)),StandardCharsets.UTF_8);}
 private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(Exception e){throw new IllegalStateException("VIEW_PROPOSAL_METADATA_INVALID");}}
 private Claims claims(){
  if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes w)||!(w.getRequest().getAttribute("ds.accessClaims") instanceof Claims))
   fail(HttpStatus.UNAUTHORIZED,"INTERACTIVE_SESSION_REQUIRED");
  return (Claims)((ServletRequestAttributes)RequestContextHolder.getRequestAttributes()).getRequest().getAttribute("ds.accessClaims");
 }
 private AuthorizationService.Actor caller(boolean content){
  var a=sessions.authenticateAccess(claims());
  if(content)for(String operation:ACTIONS)auth.requireOperation(a,operation);
  if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes w)
   w.getRequest().setAttribute(AuthorizationService.REQUEST_OPERATION,content?SHARE:"identity.self.read");
  return a;
 }
 private UUID sid(){return UUID.fromString(claims().get("sid",String.class));}
 private void lock(){auth.lockIdentityMutation();db.execute("SELECT pg_advisory_xact_lock(805014630400001::bigint)");}
 private static String reason(String value){
  if(value==null||value.trim().length()<6||value.length()>500||value.indexOf('\0')>=0)fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_REASON_REQUIRED");return value.trim();
 }
 private Map<String,Object> authority(AuthorizationService.Actor a,UUID dataset,UUID version,UUID index,AuthorizationService.GrantBinding fixed){
  return authority(a,dataset,version,index,fixed,ACTIONS);
 }
 private Map<String,Object> authority(AuthorizationService.Actor a,UUID dataset,UUID version,UUID index,AuthorizationService.GrantBinding fixed,Set<String> actions){
  if(dataset==null||version==null)fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_FROZEN_SOURCE_REQUIRED");
  var choices=fixed==null?db.queryForList("SELECT grant_id,version FROM ds_resource_grant WHERE dataset_id=? AND state='ACTIVE' ORDER BY grant_id",dataset):
   List.<Map<String,Object>>of(Map.of("grant_id",fixed.grantId(),"version",fixed.version()));
  for(var g:choices){
   var binding=new AuthorizationService.GrantBinding(uuid(g.get("grant_id")),number(g.get("version")));
   if(auth.canUseDatasetBound(a,dataset,version,index,actions,PURPOSE,binding))
    return Map.of("grantId",binding.grantId().toString(),"version",binding.version(),"purpose",PURPOSE,"buildProtocol",INTERNAL_BUILD);
  }
  fail(HttpStatus.NOT_FOUND,"VIEW_PROPOSAL_SOURCE_NOT_AVAILABLE");return null;
 }
 private Map<String,Object> source(AuthorizationService.Actor a,UUID dataset,UUID version,UUID index){
  var parent=lineage.ownedParent(a.orgId(),dataset,version,index);if(parent!=null)return parent;
  var rows=db.queryForList("""
   SELECT d.org_id,d.raw_repo_id,v.raw_commit_id,c.manifest_object_key,btrim(c.manifest_sha256) AS manifest_sha256,o.permission_version
   FROM mm_dataset d JOIN org o ON o.org_id=d.org_id AND o.org_status=1
   JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
   JOIN artifact_commit c ON c.repo_id=d.raw_repo_id AND c.commit_id=v.raw_commit_id AND c.commit_status='PUBLISHED'
   WHERE d.dataset_id=? AND v.version_id=? AND d.org_id=? AND d.status='ACTIVE'
    AND NOT EXISTS(SELECT 1 FROM ds_controlled_view cv WHERE cv.dataset_id=d.dataset_id)
   """,dataset,version,a.orgId());
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"VIEW_PROPOSAL_SOURCE_NOT_AVAILABLE");
  if(index!=null&&!Boolean.TRUE.equals(db.queryForObject("""
   SELECT EXISTS(SELECT 1 FROM mm_index_version i JOIN mm_dataset d ON d.dataset_id=i.dataset_id AND d.index_repo_id=i.index_repo_id
    JOIN artifact_commit c ON c.repo_id=i.index_repo_id AND c.commit_id=i.index_commit_id AND c.commit_status='PUBLISHED'
    WHERE i.dataset_id=? AND i.dataset_version_id=? AND i.index_version_id=? AND i.index_status='READY')
   """,Boolean.class,dataset,version,index)))fail(HttpStatus.NOT_FOUND,"VIEW_PROPOSAL_SOURCE_INDEX_NOT_AVAILABLE");
  var r=rows.get(0);String key=Objects.toString(r.get("manifest_object_key"),""),hash=Objects.toString(r.get("manifest_sha256"),"");
  String prefix="manifest/artifacts/"+r.get("raw_repo_id")+"/commits/"+r.get("raw_commit_id")+"/revisions/";
  if(!hash.matches("[a-f0-9]{64}")||!key.matches(java.util.regex.Pattern.quote(prefix)+"[a-f0-9-]{36}\\.json"))
   fail(HttpStatus.CONFLICT,"VIEW_PROPOSAL_IMMUTABLE_SOURCE_MANIFEST_REQUIRED");
  var out=new TreeMap<String,Object>();out.put("datasetId",dataset.toString());out.put("versionId",version.toString());out.put("indexVersionId",index==null?null:index.toString());
  out.put("ownerOrgId",a.orgId().toString());out.put("ownerOrgVersion",r.get("permission_version"));out.put("rawRepoId",r.get("raw_repo_id").toString());
  out.put("rawCommitId",r.get("raw_commit_id").toString());out.put("manifestObjectKey",key);out.put("manifestSha256",hash);return out;
 }
 private UUID sourceIndex(Map<String,Object> source){return uuid(source.get("indexVersionId"));}
 private Map<String,Object> targetForSource(UUID organization,Map<String,Object> source){
  JsonNode frozen=json.valueToTree(source);return target(organization,lineage.rootDataset(frozen),lineage.rootVersion(frozen));
 }
 private Map<String,Object> target(UUID organization,UUID dataset,UUID version){
  if(organization==null)fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_TARGET_REQUIRED");
  var rows=db.queryForList("""
   SELECT o.org_id,o.permission_version,e.entity_id,e.version AS mapping_version,e.mapping_id,e.catalog_revision_id,e.enterprise_name
   FROM org o JOIN ds_enterprise_identity e ON e.org_id=o.org_id AND e.state='CONFIRMED'
   JOIN ds_enterprise_catalog_revision c ON c.catalog_revision_id=e.catalog_revision_id
   JOIN ds_enterprise_catalog_entry entry ON entry.catalog_revision_id=c.catalog_revision_id AND entry.entity_id=e.entity_id
   WHERE o.org_id=? AND o.org_status=1 AND c.source_dataset_id=? AND c.source_version_id=?
    AND e.source_dataset_id=c.source_dataset_id AND e.source_version_id=c.source_version_id AND e.source_sha256=c.source_sha256
   """,organization,dataset,version);
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"VIEW_PROPOSAL_CONFIRMED_TARGET_NOT_AVAILABLE");
  var r=rows.get(0);var out=new TreeMap<String,Object>();out.put("orgId",organization.toString());out.put("orgVersion",r.get("permission_version"));
  out.put("enterpriseId",r.get("entity_id"));out.put("enterpriseName",r.get("enterprise_name"));out.put("mappingVersion",r.get("mapping_version"));
  out.put("mappingId",r.get("mapping_id").toString());out.put("catalogRevisionId",r.get("catalog_revision_id").toString());return out;
 }
 private static String path(String value,boolean corpus){
  if(value==null||value.length()>1024||!Normalizer.isNormalized(value,Normalizer.Form.NFC)||value.startsWith("/")||value.contains("\\")||value.contains(":")||
   value.codePoints().anyMatch(c->c<32)||(!value.startsWith("corpus/")&&(corpus||!value.startsWith("catalog/"))))
   fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_UNSAFE_PATH");
  for(String part:value.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_UNSAFE_PATH");return value;
 }
 private static String text(JsonNode value){
  if(value==null||!value.isTextual()||value.asText().isEmpty()||value.asText().length()>4096||value.asText().indexOf('\0')>=0)
   fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_TEXT_REQUIRED");return value.asText();
 }
 private static Set<String> strings(JsonNode value){
  if(value==null||!value.isArray()||value.isEmpty()||value.size()>100000)fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_EXPLICIT_SELECTION_REQUIRED");
  var result=new HashSet<String>();for(JsonNode item:value)if(!result.add(text(item)))fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_DUPLICATE_SELECTION");return result;
 }
 private static void keys(JsonNode value,Set<String> required,Set<String> optional){
  if(value==null||!value.isObject())fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_RULE_FIELDS_INVALID");var found=new HashSet<String>();value.fieldNames().forEachRemaining(found::add);
  if(!found.containsAll(required)||found.stream().anyMatch(k->!required.contains(k)&&!optional.contains(k)))fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_RULE_FIELDS_INVALID");
 }
 private static long positive(JsonNode value){
  if(value==null||!value.isIntegralNumber()||!value.canConvertToLong()||value.asLong()<1||value.asLong()>9007199254740991L)fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_POSITIVE_INTEGER_REQUIRED");return value.asLong();
 }
 private static void rows(JsonNode rule){
  String key=text(rule.get("key"));if(!strings(rule.get("columns")).contains(key))fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_KEY_COLUMN_REQUIRED");
  if(rule.has("rowKeys")==rule.has("allRows"))fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_EXACT_ROW_SELECTION_REQUIRED");
  if(rule.has("rowKeys"))strings(rule.get("rowKeys"));else if(!rule.get("allRows").isBoolean()||!rule.get("allRows").booleanValue())fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_EXPLICIT_ALL_ROWS_REQUIRED");
 }
 private static JsonNode selection(JsonNode files){
  if(files==null||!files.isArray()||files.isEmpty()||files.size()>250)fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_FILE_SELECTION_REQUIRED");
  if(canonical(files).length>1048576)fail(HttpStatus.PAYLOAD_TOO_LARGE,"VIEW_PROPOSAL_RULE_SIZE_LIMIT");
  var sources=new HashSet<String>();var outputs=new HashSet<String>();
  for(JsonNode f:files){
   String kind=text(f.get("kind"));var required=new HashSet<>(Set.of("sourcePath","sourceSha256","outputPath","kind"));Set<String> optional=Set.of();
   switch(kind){
    case "CSV_ROWS","JSONL_RECORDS"->{required.addAll(Set.of("key","columns"));optional=Set.of("rowKeys","allRows");}
    case "JSON_RECORDS"->{required.addAll(Set.of("key","columns","collectionPointer"));optional=Set.of("rowKeys","allRows");}
    case "XLSX_SHEETS"->required.add("sheets");case "TEXT_LINES"->required.add("ranges");case "COMPLETE_FILE"->required.add("acknowledgement");
    default->fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_PROJECTION_KIND_NOT_IMPLEMENTED");
   }
   keys(f,required,optional);String in=path(text(f.get("sourcePath")),true),out=path(text(f.get("outputPath")),true);
   if(!sources.add(in.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT))||!outputs.add(out.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT)))
    fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_PATH_COLLISION");
   String ext=in.substring(in.lastIndexOf('.')+1).toLowerCase(Locale.ROOT),outputExt=out.substring(out.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
   if(!FORMATS.contains(ext)||!ext.equals(outputExt))fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_FORMAT_MISMATCH");
   String expected=switch(kind){case "CSV_ROWS"->"csv";case "JSON_RECORDS"->"json";case "JSONL_RECORDS"->"jsonl";case "XLSX_SHEETS"->"xlsx";case "TEXT_LINES"->"txt";default->ext;};
   if(!expected.equals(ext)||!text(f.get("sourceSha256")).matches("[a-f0-9]{64}"))fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_FORMAT_OR_HASH_INVALID");
   if(Set.of("CSV_ROWS","JSONL_RECORDS","JSON_RECORDS").contains(kind))rows(f);
   if(kind.equals("JSON_RECORDS")){
    String p=f.path("collectionPointer").asText();if(!f.get("collectionPointer").isTextual()||(!p.isEmpty()&&!p.startsWith("/"))||java.util.regex.Pattern.compile("~(?![01])").matcher(p).find())fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_JSON_POINTER_INVALID");
   }
   if(kind.equals("XLSX_SHEETS")){
    JsonNode sheets=f.get("sheets");if(!sheets.isArray()||sheets.isEmpty()||sheets.size()>100)fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_SHEET_SELECTION_REQUIRED");
    var names=new HashSet<String>();for(JsonNode sheet:sheets){keys(sheet,Set.of("sheet","headerRow","key","columns"),Set.of("rowKeys","allRows"));if(!names.add(text(sheet.get("sheet"))))fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_DUPLICATE_SHEET");positive(sheet.get("headerRow"));rows(sheet);}
   }
   if(kind.equals("TEXT_LINES")){
    JsonNode ranges=f.get("ranges");if(!ranges.isArray()||ranges.isEmpty()||ranges.size()>10000)fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_LINE_RANGES_REQUIRED");
    long end=0;for(JsonNode range:ranges){if(!range.isArray()||range.size()!=2)fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_LINE_RANGE_INVALID");long first=positive(range.get(0)),last=positive(range.get(1));if(first<=end||last<first)fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_LINE_RANGE_OVERLAP");end=last;}
   }
   if(kind.equals("COMPLETE_FILE")&&!"EXACT_SOURCE_BYTES_INCLUDING_HIDDEN_CONTENT".equals(text(f.get("acknowledgement"))))fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_COMPLETE_FILE_ACKNOWLEDGEMENT_REQUIRED");
  }
  return files.deepCopy();
 }
 private static boolean responsibilityInput(String p){
  return p.startsWith("catalog/")||p.startsWith("corpus/")&&(p.endsWith(".csv")||p.endsWith(".json")||p.endsWith(".jsonl")||p.endsWith(".xlsx"));
 }
 private JsonNode inventory(Map<String,Object> source,JsonNode selected,boolean responsibility)throws Exception{
  UUID repo=uuid(source.get("rawRepoId")),commit=uuid(source.get("rawCommitId"));var manifest=manifests.getManifest(repo,commit);
  var byPath=new TreeMap<String,UUID>();var aliases=new HashSet<String>();var ids=new HashSet<UUID>();
  for(var f:manifest.getFiles()){
   String p=f.getPath();if(p.startsWith("/"))p=p.substring(1);p=path(p,false);
   if(byPath.put(p,f.getFileId())!=null||!aliases.add(p.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT))||!ids.add(f.getFileId()))
    fail(HttpStatus.CONFLICT,"VIEW_PROPOSAL_SOURCE_INVENTORY_COLLISION");
  }
  if(responsibility&&(byPath.isEmpty()||byPath.size()>2048))fail(HttpStatus.PAYLOAD_TOO_LARGE,"VIEW_RESPONSIBILITY_SOURCE_FILE_COUNT_LIMIT");
  var requested=new TreeMap<String,String>();for(JsonNode f:selected)requested.put(f.path("sourcePath").asText(),f.path("sourceSha256").asText());
  if(responsibility)for(String name:byPath.keySet())if(responsibilityInput(name))requested.putIfAbsent(name,null);
  if(byPath.containsKey("catalog/field_dictionary.json"))requested.put("catalog/field_dictionary.json",null);
  if(!byPath.keySet().containsAll(requested.keySet()))fail(HttpStatus.NOT_FOUND,"VIEW_PROPOSAL_SELECTED_FILE_NOT_AVAILABLE");
  var list=json.createArrayNode();var sourceFiles=json.createArrayNode();long bytes=0;
  for(var entry:byPath.entrySet()){
   String name=entry.getKey();boolean needed=requested.containsKey(name);if(!responsibility&&!needed)continue;
   var rows=db.queryForList("SELECT size_bytes,btrim(sha256) AS sha,upload_state FROM artifact_file WHERE file_id=? AND commit_id=?",entry.getValue(),commit);
   if(rows.size()!=1)fail(HttpStatus.CONFLICT,"VIEW_PROPOSAL_SELECTED_FILE_INTEGRITY_REQUIRED");var f=rows.get(0);String hash=Objects.toString(f.get("sha"),"");
   if(!"READY".equals(f.get("upload_state"))||!(f.get("size_bytes") instanceof Number)||!hash.matches("[a-f0-9]{64}"))fail(HttpStatus.CONFLICT,"VIEW_PROPOSAL_SELECTED_FILE_INTEGRITY_REQUIRED");
   long size=number(f.get("size_bytes"));if(size<0||size>9007199254740991L)fail(HttpStatus.CONFLICT,"VIEW_PROPOSAL_SELECTED_FILE_INTEGRITY_REQUIRED");
   var item=json.createObjectNode();item.put("logical_path","/"+name);item.put("file_id",entry.getValue().toString());item.put("size_bytes",size);item.put("sha256",hash);
   if(responsibility)sourceFiles.add(item);
   if(!needed)continue;
   if(size>268435456L||(bytes+=size)>536870912L)fail(HttpStatus.PAYLOAD_TOO_LARGE,"VIEW_PROPOSAL_SELECTED_BYTE_LIMIT");
   if(requested.get(name)!=null&&!requested.get(name).equals(hash))fail(HttpStatus.CONFLICT,"VIEW_PROPOSAL_SELECTED_SOURCE_HASH_CHANGED");list.add(item);
  }
  ObjectNode out=json.createObjectNode();out.put("datasetId",source.get("datasetId").toString());var version=out.putObject("version");version.put("version_id",source.get("versionId").toString());
  version.put("raw_commit_id",commit.toString());version.put("raw_file_count",byPath.size());out.put("inventoryFileCount",list.size());out.set("files",list);
  if(responsibility){out.put("inventoryScope","ENTERPRISE_RESPONSIBILITY_INPUTS_V1");out.set("sourceFiles",sourceFiles);}return out;
 }
 private static int page(Integer p){int v=p==null?1:p;if(v<1)fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_PAGE_INVALID");return v;}
 private static int size(Integer n){int v=n==null?25:n;if(v<1||v>100)fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_PAGE_INVALID");return v;}
 private static <T> Map<String,Object> pageOf(List<T> list,int p,int n){int from=(int)Math.min((long)(p-1)*n,list.size());return Map.of("page",p,"size",n,"total",list.size(),"items",list.subList(from,Math.min(from+n,list.size())));}
 /** Discovery uses the same complete original grant and purpose as proposal creation. */
 @Transactional(readOnly=true) public Map<String,Object> sources(Integer requestedPage,Integer requestedSize,String keyword){
  var a=caller(true);int p=page(requestedPage),n=size(requestedSize);String query=keyword==null?"":keyword.trim();
  if(query.length()>100)fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_SOURCE_QUERY_INVALID");
  var candidates=db.queryForList("""
   SELECT d.dataset_id,v.version_id,d.dataset_name,v.version_name,btrim(c.manifest_sha256) AS sha
   FROM mm_dataset d JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
   JOIN artifact_commit c ON c.repo_id=d.raw_repo_id AND c.commit_id=v.raw_commit_id AND c.commit_status='PUBLISHED'
   WHERE d.org_id=? AND d.status='ACTIVE'
    AND position(lower(?) in lower(d.dataset_name||' / '||v.version_name))>0
   ORDER BY d.created_at DESC,v.created_at DESC,d.dataset_id,v.version_id LIMIT 5001
   """,a.orgId(),query);
  if(candidates.size()>5000)fail(HttpStatus.PAYLOAD_TOO_LARGE,"VIEW_PROPOSAL_SOURCE_QUERY_TOO_BROAD");
  var visible=new ArrayList<Map<String,Object>>();
  for(var row:candidates)try{
   UUID d=uuid(row.get("dataset_id")),v=uuid(row.get("version_id"));var frozen=source(a,d,v,null);authority(a,d,v,sourceIndex(frozen),null);
   var item=new TreeMap<String,Object>();item.put("datasetId",d);item.put("versionId",v);item.put("datasetName",row.get("dataset_name"));item.put("versionName",row.get("version_name"));item.put("manifestSha256",row.get("sha"));
   item.put("indexVersionId",frozen.get("indexVersionId"));item.put("sourceKind",frozen.containsKey("controlledParent")?"CONTROLLED_VIEW":"ORIGINAL");
   if(frozen.containsKey("controlledParent"))item.put("validTo",((JsonNode)frozen.get("controlledParent")).path("validTo"));visible.add(item);
  }catch(ResponseStatusException denied){if(denied.getStatusCode().value()!=404)throw denied;}
  return pageOf(visible,p,n);
 }
 @Transactional(readOnly=true) public List<Map<String,Object>> targets(UUID dataset,UUID version){
  var a=caller(true);var frozen=source(a,dataset,version,null);authority(a,dataset,version,sourceIndex(frozen),null);
  JsonNode source=json.valueToTree(frozen);UUID rootDataset=lineage.rootDataset(source),rootVersion=lineage.rootVersion(source);
  var result=new ArrayList<Map<String,Object>>();
  for(var row:db.queryForList("""
   SELECT o.org_id,o.org_name FROM org o JOIN ds_enterprise_identity e ON e.org_id=o.org_id AND e.state='CONFIRMED'
   JOIN ds_enterprise_catalog_revision c ON c.catalog_revision_id=e.catalog_revision_id
   WHERE o.org_status=1 AND c.source_dataset_id=? AND c.source_version_id=? ORDER BY o.org_name,o.org_id
   """,rootDataset,rootVersion)){
   try{var value=new LinkedHashMap<String,Object>(targetForSource(uuid(row.get("org_id")),frozen));value.put("orgName",row.get("org_name"));result.add(value);}
   catch(ResponseStatusException denied){if(denied.getStatusCode().value()!=404)throw denied;}
  }
  return result;
 }
 @Transactional(readOnly=true) public Map<String,Object> files(UUID dataset,UUID version,Integer requestedPage,Integer requestedSize)throws Exception{
  var a=caller(true);int p=page(requestedPage),n=size(requestedSize);
  var frozen=source(a,dataset,version,null);var original=authority(a,dataset,version,sourceIndex(frozen),null);
  var manifest=manifests.getManifest(uuid(frozen.get("rawRepoId")),uuid(frozen.get("rawCommitId")));
  if(manifest.getFiles().size()>50000)fail(HttpStatus.PAYLOAD_TOO_LARGE,"VIEW_PROPOSAL_SOURCE_FILE_COUNT_LIMIT");
  var visible=new ArrayList<Map<String,Object>>();var aliases=new HashSet<String>();
  for(var file:manifest.getFiles()){
   String path=file.getPath().replaceFirst("^/","");
   if(!path.startsWith("corpus/"))continue;path=path(path,true);
   if(!aliases.add(path.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT)))fail(HttpStatus.CONFLICT,"VIEW_PROPOSAL_SOURCE_INVENTORY_COLLISION");
   String format=path.substring(path.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);if(!FORMATS.contains(format))continue;
   visible.add(Map.of("path",path,"fileId",file.getFileId(),"format",format));
  }
  visible.sort(Comparator.comparing(x->x.get("path").toString()));
  int from=(int)Math.min((long)(p-1)*n,visible.size()),to=Math.min(from+n,visible.size());var items=new ArrayList<Map<String,Object>>();
  for(var file:visible.subList(from,to)){
   var rows=db.queryForList("SELECT size_bytes,btrim(sha256) AS sha,upload_state FROM artifact_file WHERE file_id=? AND commit_id=?",file.get("fileId"),uuid(frozen.get("rawCommitId")));
   if(rows.size()!=1)fail(HttpStatus.CONFLICT,"VIEW_PROPOSAL_SELECTED_FILE_INTEGRITY_REQUIRED");var row=rows.get(0);
   if(!"READY".equals(row.get("upload_state"))||!(row.get("size_bytes") instanceof Number)||!Objects.toString(row.get("sha"),"").matches("[a-f0-9]{64}"))
    fail(HttpStatus.CONFLICT,"VIEW_PROPOSAL_SELECTED_FILE_INTEGRITY_REQUIRED");
   items.add(Map.of("path",file.get("path"),"sha256",row.get("sha"),"bytes",row.get("size_bytes"),"format",file.get("format")));
  }
  var now=caller(true);authority(now,dataset,version,sourceIndex(frozen),new AuthorizationService.GrantBinding(uuid(original.get("grantId")),number(original.get("version"))));
  if(!canonicalText(frozen).equals(canonicalText(source(now,dataset,version,null))))fail(HttpStatus.CONFLICT,"VIEW_PROPOSAL_CONTEXT_CHANGED");
  return Map.of("page",p,"size",n,"total",visible.size(),"items",items);
 }
 @Transactional(rollbackFor=Exception.class) public Map<String,Object> create(CreateRequest req)throws Exception{
  var a=caller(true);if(req==null)fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_FIELDS_REQUIRED");String reason=reason(req.reason());
  var source=source(a,req.datasetId(),req.versionId(),req.indexVersionId());UUID sourceIndex=sourceIndex(source);
  var original=authority(a,req.datasetId(),req.versionId(),sourceIndex,null);
  boolean responsibility="ENTERPRISE_RESPONSIBILITY".equals(req.scopeMode());
  if(req.scopeMode()!=null&&!Set.of("MANUAL","ENTERPRISE_RESPONSIBILITY").contains(req.scopeMode()))fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_SCOPE_MODE_INVALID");
  if(responsibility&&source.containsKey("controlledParent"))fail(HttpStatus.BAD_REQUEST,"VIEW_RESPONSIBILITY_ORIGINAL_SOURCE_REQUIRED");
  var target=targetForSource(req.targetOrgId(),source);JsonNode files=responsibility?ControlledViewResponsibilityRules.completeFiles(req.files()):selection(req.files()),inventory=inventory(source,files,responsibility);
  UUID id=UUID.randomUUID();ObjectNode rules=json.createObjectNode();rules.put("schema",responsibility?ControlledViewResponsibilityRules.SCHEMA:"COLLABORATION_VIEW_RULES_V1");rules.put("viewId",id.toString());rules.put("targetOrgId",req.targetOrgId().toString());
  rules.put("targetEnterpriseId",target.get("enterpriseId").toString());var frozen=rules.putObject("source");frozen.put("datasetId",req.datasetId().toString());frozen.put("versionId",req.versionId().toString());
  frozen.put("rawCommitId",source.get("rawCommitId").toString());frozen.put("inputInventorySha256",sha(canonical(inventory)));rules.set("files",files);rules.put("reason",reason);
  var prepared=scripts.prepare(uuid(source.get("rawRepoId")),inventory,rules);
  var payload=new TreeMap<String,Object>();payload.put("schema","CONTROLLED_VIEW_PROPOSAL_V1");payload.put("proposalId",id.toString());payload.put("source",source);payload.put("target",target);
  payload.put("sourceAuthority",original);payload.put("rules",rules);payload.put("inventory",inventory);payload.put("ruleSha256",prepared.ruleSha256());payload.put("inventorySha256",prepared.inventorySha256());
  payload.put("scriptSha256",prepared.scriptSha256());payload.put("moduleSha256",prepared.moduleSha256());payload.put("packagedModuleSha256",scripts.packagedModuleSha256());payload.put("actorPermissionVersion",a.permissionVersion());
  boolean complete=false;for(JsonNode file:files)complete|="COMPLETE_FILE".equals(file.path("kind").asText());
  payload.put("requiresOriginalByteReview",complete);payload.put("sourceFileBytesVerifiedByWorker",false);payload.put("materializationExecuted",false);payload.put("approved",false);payload.put("published",false);payload.put("authorizationGranted",false);
  String encoded=canonicalText(payload);Instant expires=Instant.now().plusSeconds(86400);
  // Object I/O and script construction precede the authority lock; recheck all bindings now.
  lock();a=caller(true);var binding=new AuthorizationService.GrantBinding(uuid(original.get("grantId")),number(original.get("version")));
  authority(a,req.datasetId(),req.versionId(),sourceIndex,binding);
  if(!canonicalText(source).equals(canonicalText(source(a,req.datasetId(),req.versionId(),req.indexVersionId())))||
   !canonicalText(target).equals(canonicalText(targetForSource(req.targetOrgId(),source)))||a.permissionVersion()!=number(payload.get("actorPermissionVersion")))
   fail(HttpStatus.CONFLICT,"VIEW_PROPOSAL_CONTEXT_CHANGED");
  db.update("""
   INSERT INTO ds_controlled_view_proposal(proposal_id,owner_org_id,actor_user_id,parent_session_id,actor_token_version,source_grant_id,source_grant_version,
    source_dataset_id,source_version_id,target_org_id,payload,payload_sha256,expires_at)
   VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?)
   """,id,a.orgId(),a.userId(),sid(),a.tokenVersion(),binding.grantId(),binding.version(),req.datasetId(),req.versionId(),req.targetOrgId(),encoded,sha(encoded.getBytes(StandardCharsets.UTF_8)),Timestamp.from(expires));
  event(a,id,"PROPOSE",reason,1);return Map.of("proposalId",id,"state","PROPOSED","version",1,"expiresAt",expires.toString(),"proposal",payload);
 }
 private Map<String,Object> owned(UUID id,AuthorizationService.Actor a){
  var rows=db.queryForList("SELECT * FROM ds_controlled_view_proposal WHERE proposal_id=? AND owner_org_id=? AND actor_user_id=? AND parent_session_id=? AND actor_token_version=?",
   id,a.orgId(),a.userId(),sid(),a.tokenVersion());
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"VIEW_PROPOSAL_NOT_AVAILABLE");return rows.get(0);
 }
 private JsonNode current(Map<String,Object> row,AuthorizationService.Actor a){
  if(!"PROPOSED".equals(row.get("state"))||!((Timestamp)row.get("expires_at")).toInstant().isAfter(Instant.now()))fail(HttpStatus.NOT_FOUND,"VIEW_PROPOSAL_NOT_AVAILABLE");
  JsonNode p=parse(row.get("payload"));if(!sha(canonical(p)).equals(row.get("payload_sha256").toString().trim()))fail(HttpStatus.SERVICE_UNAVAILABLE,"VIEW_PROPOSAL_IDENTITY_MISMATCH");
  authority(a,uuid(row.get("source_dataset_id")),uuid(row.get("source_version_id")),uuid(p.path("source").path("indexVersionId").isNull()?null:p.path("source").path("indexVersionId").asText()),
   new AuthorizationService.GrantBinding(uuid(row.get("source_grant_id")),number(row.get("source_grant_version"))),sourceOperations(p.path("sourceAuthority")));
  var s=source(a,uuid(row.get("source_dataset_id")),uuid(row.get("source_version_id")),uuid(p.path("source").path("indexVersionId").isNull()?null:p.path("source").path("indexVersionId").asText()));
  var t=targetForSource(uuid(row.get("target_org_id")),s);
  if(!canonicalText(s).equals(canonicalText(p.path("source")))||!canonicalText(t).equals(canonicalText(p.path("target")))||a.permissionVersion()!=p.path("actorPermissionVersion").asLong()||
   !canonicalText(scripts.packagedModuleSha256()).equals(canonicalText(p.path("packagedModuleSha256"))))
   fail(HttpStatus.CONFLICT,"VIEW_PROPOSAL_CONTEXT_CHANGED");
  if(!row.get("proposal_id").toString().equals(p.path("proposalId").asText())||
   !row.get("proposal_id").toString().equals(p.path("rules").path("viewId").asText())||
   !row.get("target_org_id").toString().equals(p.path("rules").path("targetOrgId").asText())||
   !p.path("target").path("enterpriseId").asText().equals(p.path("rules").path("targetEnterpriseId").asText())||
   !s.get("datasetId").toString().equals(p.path("rules").path("source").path("datasetId").asText())||
   !s.get("versionId").toString().equals(p.path("rules").path("source").path("versionId").asText())||
   !s.get("rawCommitId").toString().equals(p.path("rules").path("source").path("rawCommitId").asText())||
   !row.get("source_grant_id").toString().equals(p.path("sourceAuthority").path("grantId").asText())||
   number(row.get("source_grant_version"))!=p.path("sourceAuthority").path("version").asLong()||
   !PURPOSE.equals(p.path("sourceAuthority").path("purpose").asText()))
   fail(HttpStatus.SERVICE_UNAVAILABLE,"VIEW_PROPOSAL_IDENTITY_MISMATCH");
  var prepared=scripts.prepare(uuid(s.get("rawRepoId")),p.path("inventory"),p.path("rules"));
  if(!prepared.scriptSha256().equals(p.path("scriptSha256").asText())||
   !prepared.ruleSha256().equals(p.path("ruleSha256").asText())||
   !prepared.inventorySha256().equals(p.path("inventorySha256").asText())||
   !canonicalText(prepared.moduleSha256()).equals(canonicalText(p.path("moduleSha256"))))
   fail(HttpStatus.CONFLICT,"VIEW_PROPOSAL_EXECUTABLE_CHANGED");
  return p;
 }
 @Transactional(readOnly=true) public Map<String,Object> get(UUID id){
  var a=caller(true);var row=owned(id,a);JsonNode p=current(row,a);return Map.of("proposalId",id,"state",row.get("state"),"version",row.get("version"),"expiresAt",row.get("expires_at").toString(),"proposal",p);
 }
 @Transactional(readOnly=true) public Map<String,Object> list(Integer page,Integer size){
  var a=caller(true);int p=page==null?1:page,n=size==null?25:size;if(p<1||n<1||n>100)fail(HttpStatus.BAD_REQUEST,"VIEW_PROPOSAL_PAGE_INVALID");
  var visible=new ArrayList<Map<String,Object>>();
  for(var row:db.queryForList("SELECT * FROM ds_controlled_view_proposal WHERE owner_org_id=? AND actor_user_id=? AND parent_session_id=? AND actor_token_version=? AND state='PROPOSED' AND expires_at>clock_timestamp() ORDER BY created_at DESC,proposal_id",a.orgId(),a.userId(),sid(),a.tokenVersion()))
   try{JsonNode payload=current(row,a);visible.add(Map.of("proposalId",row.get("proposal_id"),"version",row.get("version"),"state",row.get("state"),"expiresAt",row.get("expires_at").toString(),"source",payload.path("source"),"target",payload.path("target"),"ruleSha256",payload.path("ruleSha256"),"requiresOriginalByteReview",payload.path("requiresOriginalByteReview")));}catch(ResponseStatusException e){if(!Set.of(404,409).contains(e.getStatusCode().value()))throw e;}
  int from=(int)Math.min((long)(p-1)*n,visible.size()),to=Math.min(from+n,visible.size());return Map.of("page",p,"size",n,"total",visible.size(),"items",visible.subList(from,to));
 }
 private void event(AuthorizationService.Actor a,UUID id,String action,String reason,long version){
  db.update("INSERT INTO ds_controlled_view_proposal_event(event_id,proposal_id,actor_user_id,actor_org_id,action,reason,proposal_version) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID(),id,a.userId(),a.orgId(),action,reason,version);
  audit.record(a.userId(),a.orgId(),action.equals("PROPOSE")?SHARE:"identity.self.read","CONTROLLED_VIEW_PROPOSAL",id.toString(),"SUCCESS","VIEW_PROPOSAL_"+action,Map.of("proposalId",id,"version",version),null,Map.of("state",action.equals("PROPOSE")?"PROPOSED":"WITHDRAWN"));
 }
 @Transactional public void withdraw(UUID id,WithdrawRequest req){
  caller(false);if(req==null||req.expectedVersion()==null||req.expectedVersion()<1)fail(HttpStatus.PRECONDITION_REQUIRED,"VIEW_PROPOSAL_VERSION_REQUIRED");
  String reason=reason(req.reason());lock();var a=caller(false);var row=owned(id,a);
  if("WITHDRAWN".equals(row.get("state"))&&number(row.get("version"))==req.expectedVersion()+1)return;
  if(!"PROPOSED".equals(row.get("state"))||number(row.get("version"))!=req.expectedVersion())fail(HttpStatus.CONFLICT,"VIEW_PROPOSAL_VERSION_CHANGED");
  int changed=db.update("UPDATE ds_controlled_view_proposal SET state='WITHDRAWN',version=version+1,withdrawn_at=clock_timestamp() WHERE proposal_id=? AND state='PROPOSED' AND version=?",id,req.expectedVersion());
  if(changed!=1)fail(HttpStatus.CONFLICT,"VIEW_PROPOSAL_VERSION_CHANGED");event(a,id,"WITHDRAW",reason,req.expectedVersion()+1);
 }

 /** Internal typed-run access: callers authenticate the durable original session. */
 public record Execution(JsonNode proposal,ControlledViewWorkerScriptFactory.Prepared prepared){}
 @Transactional(readOnly=true) public Execution execution(UUID id,AuthorizationService.Actor actor,UUID parentSession){
  var rows=db.queryForList("SELECT * FROM ds_controlled_view_proposal WHERE proposal_id=? AND owner_org_id=? AND actor_user_id=? AND parent_session_id=? AND actor_token_version=?",
   id,actor.orgId(),actor.userId(),parentSession,actor.tokenVersion());
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"VIEW_PROPOSAL_NOT_AVAILABLE");
  JsonNode plan=current(rows.get(0),actor);
  return new Execution(plan.deepCopy(),scripts.prepare(uuid(plan.path("source").path("rawRepoId").asText()),plan.path("inventory"),plan.path("rules")));
 }
}
