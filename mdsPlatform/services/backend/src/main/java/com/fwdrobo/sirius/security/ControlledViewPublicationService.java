package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fwdrobo.sirius.service.*;
import com.fwdrobo.sirius.dto.mm.MmBuildIndexReq;
import io.jsonwebtoken.Claims;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;

/** Review, independent frozen raw snapshot, fixed native index, then atomic view grants. */
@Service @Slf4j
public class ControlledViewPublicationService {
 private static final Set<String> CONSUMER=Set.of("data.catalog.read","data.preview","data.search","data.qa","data.raw.download","data.view.export");
 private static final long RAW_LIMIT=671088640L,INDEX_LIMIT=21474836480L;
 private final JdbcTemplate db;private final AuthorizationService auth;private final ControlledViewPublicationAuthority authority;
 private final ControlledViewPreviewAuthority previews;private final ManifestService manifests;private final MinioService objects;
 private final MmIndexService indexes;private final TrialLimitService limits;private final AuthorizationAuditService audit;private final ObjectMapper json;private final TransactionTemplate tx;
 private final Semaphore copying=new Semaphore(1);private final Set<UUID> verifying=ConcurrentHashMap.newKeySet();
 private final ThreadPoolExecutor verifier=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"controlled-view-publication-verifier");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
 public ControlledViewPublicationService(JdbcTemplate db,AuthorizationService auth,ControlledViewPublicationAuthority authority,
  ControlledViewPreviewAuthority previews,ManifestService manifests,MinioService objects,MmIndexService indexes,TrialLimitService limits,AuthorizationAuditService audit,ObjectMapper json,PlatformTransactionManager manager){
  this.db=db;this.auth=auth;this.authority=authority;this.previews=previews;this.manifests=manifests;this.objects=objects;this.indexes=indexes;this.limits=limits;this.audit=audit;this.json=json;this.tx=new TransactionTemplate(manager);
 }
 public record ReviewRequest(UUID attemptId,String name,List<String> operations,Instant validFrom,Instant validTo,String reason,Map<String,Object> indexConfig){}
 public record ConfirmRequest(UUID reviewId,String expectedReviewSha256,Boolean confirm){}
 public record RevokeRequest(Long expectedVersion,String reason,Boolean confirm){}
 private static void fail(HttpStatus status,String reason){throw new ResponseStatusException(status,reason);}
 private static UUID uuid(Object x){return ControlledViewPublicationAuthority.uuid(x);}
 private static String sha(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw new IllegalStateException(e);}}
 private String encode(Object x){try{return json.writeValueAsString(x);}catch(Exception e){throw new IllegalStateException("CONTROLLED_VIEW_PUBLICATION_ENCODING_FAILED");}}
 private JsonNode parse(Object x){return authority.parse(x);}
 private String text(JsonNode n,String field){return n.path(field).asText();}
 private static String reason(String x){if(x==null||x.trim().length()<6||x.length()>500||x.codePoints().anyMatch(c->c<32))fail(HttpStatus.BAD_REQUEST,"CONTROLLED_VIEW_PUBLICATION_REASON_REQUIRED");return x.trim();}
 private static String name(String x){if(x==null||x.trim().isBlank()||x.trim().length()>100||!Normalizer.isNormalized(x,Normalizer.Form.NFC)||x.codePoints().anyMatch(c->c<32))fail(HttpStatus.BAD_REQUEST,"CONTROLLED_VIEW_NAME_REQUIRED");return x.trim();}
 private Map<String,Object> reviewRow(UUID id,boolean lock){
  var a=authority.actor();Claims c=authority.interactive();
  var rows=db.queryForList("""
   SELECT * FROM ds_controlled_view_publication_review WHERE review_id=? AND actor_user_id=? AND actor_org_id=? AND parent_session_id=? AND actor_token_version=?
   """+(lock?" FOR UPDATE":""),id,a.userId(),a.orgId(),uuid(c.get("sid",String.class)),a.tokenVersion());
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"CONTROLLED_VIEW_PUBLICATION_REVIEW_NOT_AVAILABLE");return rows.get(0);
 }
 private void event(UUID publication,UUID review,UUID actor,String kind,Object metadata){
  db.update("INSERT INTO ds_controlled_view_publication_event(event_id,publication_id,review_id,actor_user_id,event_kind,metadata) VALUES(?,?,?,?,?,?::jsonb)",
   UUID.randomUUID(),publication,review,actor,kind,encode(metadata));
  UUID owner=db.queryForObject("SELECT actor_org_id FROM ds_controlled_view_publication_review WHERE review_id=?",UUID.class,review);
  audit.record(actor,owner,ControlledViewPublicationAuthority.SHARE,"CONTROLLED_VIEW_PUBLICATION",publication.toString(),"SUCCESS",
   "CONTROLLED_VIEW_"+kind,Map.of("reviewId",review,"eventKind",kind,"sourceKind",actor==null?"SYSTEM":"USER"),null,json.convertValue(json.valueToTree(metadata),Map.class));
 }
 private JsonNode payload(Map<String,Object> r){
  JsonNode p=parse(r.get("payload"));if(!ControlledViewPreviewAuthority.hash(p).equals(r.get("payload_sha256").toString().trim()))fail(HttpStatus.SERVICE_UNAVAILABLE,"CONTROLLED_VIEW_PUBLICATION_REVIEW_CHANGED");return p;
 }
 private ObjectNode publicReview(JsonNode p){
  ObjectNode out=json.createObjectNode();
  for(String key:List.of("publicationId","attemptId","viewId","name","target","consumerOperations","providerOperations","validFrom","providerValidFrom","validTo","hasOriginalByteCopies","ruleSha256","actualSelections","reason","targetOrgName"))out.set(key,p.path(key));
  var source=out.putObject("source");for(String k:List.of("datasetId","versionId","indexVersionId","manifestSha256"))source.set(k,p.path("source").path(k));
  var files=out.putArray("files");for(JsonNode file:p.path("files")){var f=files.addObject();for(String k:List.of("path","sha256","bytes"))f.set(k,file.path(k));}
  out.set("indexConfig",p.path("indexProgram").path("indexConfig"));out.put("approved",false);out.put("published",false);out.put("authorizationGranted",false);out.put("actualStoredPreviewBytesVerified",true);
  return out;
 }
 public Map<String,Object> review(ReviewRequest req)throws Exception{
  if(req==null||req.attemptId()==null)fail(HttpStatus.BAD_REQUEST,"VERIFIED_CONTROLLED_VIEW_PREVIEW_REQUIRED");
  var a=previews.forHuman(req.attemptId());var actor=a.actor();authority.configured();
  String display=name(req.name()),why=reason(req.reason());
  if(req.operations()==null||req.operations().isEmpty()||req.operations().size()>CONSUMER.size()||
   new HashSet<>(req.operations()).size()!=req.operations().size()||!CONSUMER.containsAll(req.operations()))fail(HttpStatus.BAD_REQUEST,"CONTROLLED_VIEW_EXPLICIT_CONSUMER_OPERATIONS_REQUIRED");
  var state=db.queryForMap("SELECT * FROM ds_controlled_view_preview_state WHERE attempt_id=?",req.attemptId());
  if(!"AWAITING_REVIEW".equals(state.get("state")))fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_VERIFIED_PREVIEW_REQUIRED");
  previews.requireVerifiedRepository(a,a.outputRepo(),a.outputCommit());
  JsonNode summary=parse(state.get("verification_summary"));
  if(!summary.path("actualStoredBytesVerified").asBoolean())fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_ACTUAL_PREVIEW_BYTES_REQUIRED");
  boolean copies=summary.path("hasOriginalByteCopies").asBoolean();
  var requested=new TreeSet<>(req.operations());
  if(copies&&requested.contains("data.view.export")&&!requested.contains("data.raw.download"))fail(HttpStatus.BAD_REQUEST,"ORIGINAL_BYTE_VIEW_EXPORT_REQUIRES_RAW_OPERATION");
  Instant now=Instant.now(),from=req.validFrom()==null?now:req.validFrom(),to=req.validTo();
  if(from.isBefore(now.minusSeconds(60))||to!=null&&(!to.isAfter(from)||!to.isAfter(now)))fail(HttpStatus.BAD_REQUEST,"CONTROLLED_VIEW_SHARING_PERIOD_INVALID");
  JsonNode plan=a.proposal(),source=plan.path("source");
  if(!actor.orgId().equals(uuid(plan.path("target").path("orgId").asText()))&&to==null)fail(HttpStatus.BAD_REQUEST,"CROSS_ENTERPRISE_SHARING_EXPIRY_REQUIRED");
  var provider=new TreeSet<>(requested);provider.add(ControlledViewPublicationAuthority.SHARE);
  Set<String> sourceCodes=new HashSet<>(db.queryForList("SELECT unnest(operation_codes) FROM ds_resource_grant WHERE grant_id=? AND version=?",String.class,
   uuid(plan.path("sourceAuthority").path("grantId").asText()),plan.path("sourceAuthority").path("version").asLong()));
  for(String op:List.of("job.read","job.log.read","job.cancel"))if(actor.operations().contains(op)&&sourceCodes.contains(op))provider.add(op);
  var rows=db.queryForList("SELECT space_id FROM mm_dataset WHERE dataset_id=? AND org_id=?",UUID.class,uuid(source.path("datasetId").asText()),actor.orgId());
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"CONTROLLED_VIEW_SOURCE_SPACE_NOT_AVAILABLE");
  UUID id=UUID.randomUUID(),review=UUID.randomUUID(),dataset=UUID.randomUUID(),version=UUID.randomUUID(),rawRepo=UUID.randomUUID(),rawCommit=UUID.randomUUID(),indexRepo=UUID.randomUUID();
  ObjectNode p=json.createObjectNode();p.put("schema","CONTROLLED_VIEW_PUBLICATION_REVIEW_V1");p.put("publicationId",id.toString());p.put("attemptId",a.id().toString());p.put("viewId",a.proposalId().toString());
  p.put("name",display);p.put("reason",why);p.put("targetOrgName",db.queryForObject("SELECT org_name FROM org WHERE org_id=?",String.class,uuid(plan.path("target").path("orgId").asText())));p.set("source",source.deepCopy());p.set("target",plan.path("target").deepCopy());p.set("sourceAuthority",plan.path("sourceAuthority").deepCopy());
  p.set("rules",plan.path("rules").deepCopy());p.put("ruleSha256",a.prepared().ruleSha256());p.put("proposalSha256",a.row().get("proposal_sha256").toString().trim());
  p.put("previewManifestSha256",state.get("verified_manifest_sha256").toString().trim());p.put("privateReceiptSha256",summary.path("privateReceiptSha256").asText());
  p.put("candidateReceiptSha256",summary.path("candidateReceiptSha256").asText());p.set("actualSelections",summary.path("actualSelections").deepCopy());p.put("hasOriginalByteCopies",copies);
  var who=p.putObject("actor");who.put("userId",actor.userId().toString());who.put("orgId",actor.orgId().toString());who.put("sessionId",a.session().toString());
  who.put("tokenVersion",actor.tokenVersion());who.put("permissionVersion",actor.permissionVersion());who.put("username",actor.username());
  p.set("consumerOperations",json.valueToTree(requested));p.set("providerOperations",json.valueToTree(provider));
  p.put("validFrom",from.toString());p.put("providerValidFrom",now.toString());if(to==null)p.putNull("validTo");else p.put("validTo",to.toString());
  p.put("providerGrantId",UUID.randomUUID().toString());p.put("consumerGrantId",UUID.randomUUID().toString());
  var program=json.valueToTree(authority.indexProgram());((ObjectNode)program).set("indexConfig",json.valueToTree(com.fwdrobo.sirius.util.IndexProcessingConfig.validate(req.indexConfig())));p.set("indexProgram",program);
  var destination=p.putObject("destination");destination.put("datasetId",dataset.toString());destination.put("versionId",version.toString());destination.put("rawRepoId",rawRepo.toString());
  destination.put("rawCommitId",rawCommit.toString());destination.put("indexRepoId",indexRepo.toString());destination.put("spaceId",rows.get(0).toString());
  var expected=new TreeMap<String,JsonNode>();for(JsonNode f:summary.path("publicFiles"))expected.put(f.path("path").asText(),f);
  var files=p.putArray("files");
  for(var f:manifests.getManifest(a.outputRepo(),a.outputCommit()).getFiles()){
   String path=f.getPath().replaceFirst("^/","");JsonNode e=expected.remove(path);
   var metadata=db.queryForList("SELECT * FROM artifact_file WHERE file_id=? AND commit_id=?",f.getFileId(),a.outputCommit());
   if(e==null||metadata.size()!=1)fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_PREVIEW_INVENTORY_CHANGED");var original=metadata.get(0);
   if(!"READY".equals(original.get("upload_state"))||!e.path("sha256").asText().equals(original.get("sha256").toString().trim())||
     e.path("bytes").asLong()!=((Number)original.get("size_bytes")).longValue())fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_PREVIEW_FILE_METADATA_CHANGED");
   var entry=files.addObject();entry.put("path",path);entry.set("sha256",e.path("sha256"));entry.set("bytes",e.path("bytes"));entry.put("fileId",UUID.randomUUID().toString());
   entry.put("sourceFileId",f.getFileId().toString());entry.put("sourceKey",original.get("file_key").toString());entry.put("bucket",original.get("bucket_name").toString());
   entry.put("contentType",Objects.toString(original.get("content_type"),"application/octet-stream"));
  }
  if(p.path("indexProgram").path("indexConfig").path("assetMappings").isObject()){
   var allowed=new HashSet<String>();for(JsonNode file:files)allowed.add("/"+file.path("path").asText());
   p.path("indexProgram").path("indexConfig").path("assetMappings").fieldNames().forEachRemaining(path->{if(!allowed.contains(path))fail(HttpStatus.BAD_REQUEST,"CONTROLLED_VIEW_INDEX_MAPPING_OUTSIDE_APPROVED_FILES");});
  }
  if(!expected.isEmpty()||files.isEmpty())fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_PREVIEW_FILE_SET_CHANGED");
  String digest=ControlledViewPreviewAuthority.hash(p);Instant expires=now.plusSeconds(600);
  tx.executeWithoutResult(status->{
   authority.fence();authority.current(p);
   if(!db.queryForList("SELECT view_id FROM ds_controlled_view WHERE view_id=?",a.proposalId()).isEmpty())fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_PROPOSAL_ALREADY_PUBLISHED");
   db.update("""
    INSERT INTO ds_controlled_view_publication_review(review_id,publication_id,attempt_id,actor_user_id,actor_org_id,parent_session_id,actor_token_version,payload,payload_sha256,expires_at)
    VALUES(?,?,?,?,?,?,?,?::jsonb,?,?)
    """,review,id,a.id(),actor.userId(),actor.orgId(),a.session(),actor.tokenVersion(),encode(p),digest,Timestamp.from(expires));
   event(id,review,actor.userId(),"REVIEWED",Map.of("reviewSha256",digest,"previewManifestSha256",text(p,"previewManifestSha256"),"consumerOperations",requested,"targetOrgId",text(p.path("target"),"orgId"),"publicFileCount",files.size()));
  });
  return Map.of("reviewId",review,"reviewSha256",digest,"expiresAt",expires.toString(),"review",publicReview(p));
 }
 private record Copied(List<Map<String,Object>> files,String manifestKey,String manifestSha){}
 private static void missing(Exception e){
  if(e instanceof io.minio.errors.ErrorResponseException m&&m.errorResponse()!=null&&Set.of("NoSuchKey","NoSuchObject","NotFound").contains(m.errorResponse().code()))
   fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_STORED_OBJECT_MISSING");
 }
 private String streamHash(String key,long expected,JsonNode plan)throws Exception{
  MessageDigest h=MessageDigest.getInstance("SHA-256");long count=0;
  try(var input=objects.getObject(key)){
   byte[] buffer=new byte[4*1024*1024];int n;
   while((n=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Publication verification stopped");
    count+=n;if(count>expected)fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_OBJECT_SIZE_CHANGED");h.update(buffer,0,n);authority.current(plan);}
  }catch(Exception e){missing(e);throw e;}
  if(count!=expected)fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_OBJECT_SIZE_CHANGED");
  return HexFormat.of().formatHex(h.digest());
 }
 private Copied copy(JsonNode p,List<String> ownedStagingKeys)throws Exception{
  authority.current(p);UUID publication=uuid(p.path("publicationId").asText()),repo=uuid(p.path("destination").path("rawRepoId").asText()),commit=uuid(p.path("destination").path("rawCommitId").asText());
  var proof=db.queryForMap("SELECT object_key,size_bytes FROM ds_controlled_view_preview_receipt WHERE attempt_id=?",uuid(p.path("attemptId").asText()));
  if(!p.path("privateReceiptSha256").asText().equals(streamHash(proof.get("object_key").toString(),((Number)proof.get("size_bytes")).longValue(),p)))
   fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_PRIVATE_PROOF_OBJECT_CHANGED");
  String copyAttempt=UUID.randomUUID().toString();var copied=new ArrayList<Map<String,Object>>();long total=0;
  for(JsonNode file:p.path("files")){
   authority.current(p);long bytes=file.path("bytes").asLong();total+=bytes;if(bytes<0||total>RAW_LIMIT)fail(HttpStatus.PAYLOAD_TOO_LARGE,"CONTROLLED_VIEW_RAW_VERIFICATION_BYTE_LIMIT");
   Path temporary=Files.createTempFile("mds-view-publication-",".private");String key="controlled-view-private/"+publication+"/staging/"+copyAttempt+"/"+file.path("fileId").asText();
   ownedStagingKeys.add(key);
   try{
    MessageDigest h=MessageDigest.getInstance("SHA-256");long count=0;
    try(var input=objects.getObject(file.path("sourceKey").asText());var output=Files.newOutputStream(temporary)){
     byte[] buffer=new byte[4*1024*1024];int n;
     while((n=input.read(buffer))!=-1){count+=n;if(count>bytes)fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_REVIEWED_PREVIEW_BYTES_CHANGED");output.write(buffer,0,n);h.update(buffer,0,n);authority.current(p);}
    }catch(Exception e){missing(e);throw e;}
    if(count!=bytes||!file.path("sha256").asText().equals(HexFormat.of().formatHex(h.digest())))fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_REVIEWED_PREVIEW_BYTES_CHANGED");
    authority.current(p);try(var input=Files.newInputStream(temporary)){objects.putObject(key,input,bytes,file.path("contentType").asText());}
    if(objects.statObject(key).size()!=bytes||!file.path("sha256").asText().equals(streamHash(key,bytes,p)))fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_COPIED_RAW_BYTES_CHANGED");
    copied.add(Map.of("fileId",file.path("fileId").asText(),"path","/"+file.path("path").asText(),"key",key,"bucket",file.path("bucket").asText(),"bytes",bytes,
     "sha256",file.path("sha256").asText(),"contentType",file.path("contentType").asText(),"etag",Objects.toString(objects.statObject(key).etag(),"")));
   }finally{Files.deleteIfExists(temporary);}
  }
  var manifest=json.createObjectNode();manifest.put("artifact_id",repo.toString());manifest.put("commit_id",commit.toString());manifest.put("version",1);
  String now=OffsetDateTime.now(ZoneOffset.UTC).toString();manifest.put("created_at",now);manifest.put("updated_at",now);
  var list=manifest.putArray("files");for(var file:copied){var entry=list.addObject();entry.put("path",file.get("path").toString());entry.put("file_id",file.get("fileId").toString());}
  byte[] encoded=json.writeValueAsBytes(manifest);String key="manifest/artifacts/"+repo+"/commits/"+commit+"/revisions/"+UUID.randomUUID()+".json",hash=sha(encoded);
  ownedStagingKeys.add(key);
  objects.putObject(key,new ByteArrayInputStream(encoded),encoded.length,"application/json");
  if(!hash.equals(streamHash(key,encoded.length,p)))fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_NEW_RAW_MANIFEST_CHANGED");
  return new Copied(copied,key,hash);
 }
 private ObjectNode publicScope(JsonNode p){
  var out=json.createObjectNode();out.put("schema","CONTROLLED_VIEW_PUBLIC_SCOPE_V1");out.set("projections",ControlledViewPublicProjection.describe(p.path("rules"),p.path("actualSelections")));out.put("viewId",p.path("viewId").asText());
  out.put("targetEnterpriseId",p.path("target").path("enterpriseId").asText());out.set("hasOriginalByteCopies",p.path("hasOriginalByteCopies"));out.set("ruleSha256",p.path("ruleSha256"));
  var files=out.putArray("files");for(JsonNode f:p.path("files")){var item=files.addObject();for(String field:List.of("path","sha256","bytes"))item.set(field,f.path(field));}
  var selections=out.putArray("selections");for(JsonNode f:p.path("actualSelections")){var item=selections.addObject();for(String field:List.of("path","kind","selectedRecordCount"))item.set(field,f.path(field));}
  return out;
 }
 private void insertSnapshot(UUID review,JsonNode p,Copied snapshot){
  JsonNode ids=p.path("destination"),source=p.path("source"),who=p.path("actor");UUID actor=uuid(who.path("userId").asText()),org=uuid(who.path("orgId").asText()),pub=uuid(p.path("publicationId").asText());
  UUID dataset=uuid(ids.path("datasetId").asText()),version=uuid(ids.path("versionId").asText()),raw=uuid(ids.path("rawRepoId").asText()),commit=uuid(ids.path("rawCommitId").asText()),index=uuid(ids.path("indexRepoId").asText());
  if(!db.queryForList("SELECT dataset_id FROM mm_dataset WHERE space_id=? AND dataset_name=?",uuid(ids.path("spaceId").asText()),p.path("name").asText()).isEmpty())
   fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_DATASET_NAME_ALREADY_EXISTS");
  long bytes=0;for(var file:snapshot.files())bytes+=((Number)file.get("bytes")).longValue();
  // The same transaction attaches the frozen files and charges their full size.
  // Failed or concurrent duplicate confirmations cannot leak quota reservations.
  boolean trial=limits.isTrialOrg(org);long reserved=limits.reserveUploadQuota(bytes,0);
  if(!trial)limits.addUsedStorageAfterSuccess(bytes);
  if(trial&&reserved!=bytes)fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_RAW_QUOTA_RESERVATION_CHANGED");
  for(var item:Map.of(raw,"MM_RAW",index,"MM_INDEX").entrySet())
   db.update("INSERT INTO artifact_repo(repo_id,repo_name,repo_type,description,org_id,owner_user_id,visibility) VALUES(?,?,?,?,?,?,'PRIVATE')",
    item.getKey(),"controlled-view-"+item.getValue().toLowerCase(Locale.ROOT)+"-"+pub,item.getValue(),"Reviewed controlled enterprise view",org,actor);
  db.update("INSERT INTO artifact_commit(commit_id,repo_id,commit_status,commit_source,created_by,comment,meta,manifest_object_key,manifest_sha256) VALUES(?,?,'DRAFT','CONTROLLED_VIEW_APPROVED',?,'approved independent derived raw snapshot',jsonb_build_object('controlledViewPublicationId',?::text),?,?)",
   commit,raw,actor,pub,snapshot.manifestKey(),snapshot.manifestSha());
  for(var f:snapshot.files())db.update("""
   INSERT INTO artifact_file(file_id,commit_id,logical_path,bucket_name,file_key,sha256,size_bytes,content_type,etag,upload_state)
   VALUES(?,?,?,?,?,?,?,?,?,'READY')
   """,uuid(f.get("fileId")),commit,f.get("path"),f.get("bucket"),f.get("key"),f.get("sha256"),f.get("bytes"),f.get("contentType"),f.get("etag"));
  db.update("UPDATE artifact_commit SET commit_status='PUBLISHED',published_by=?,published_at=clock_timestamp() WHERE repo_id=? AND commit_id=?",actor,raw,commit);
  db.update("""
   INSERT INTO mm_dataset(dataset_id,space_id,dataset_name,dataset_type,modality_type,description,raw_repo_id,index_repo_id,owner_user_id,org_id,status,meta)
   VALUES(?,?,?,'CORPUS','IMAGE_TEXT','Reviewed controlled enterprise view',?,?,?,?,'ACTIVE',jsonb_build_object('controlledViewPublicationId',?::text))
   """,dataset,uuid(ids.path("spaceId").asText()),p.path("name").asText(),raw,index,actor,org,pub);
  db.update("""
   INSERT INTO mm_dataset_version(version_id,dataset_id,version_name,raw_commit_id,version_status,created_by,published_by,published_at,comment,meta)
   VALUES(?,?,'v1',?,'RAW_READY',?,?,clock_timestamp(),'approved immutable controlled view',jsonb_build_object('controlledViewPublicationId',?::text,'activateOnReady',true))
   """,version,dataset,commit,actor,actor,pub);
  db.update("""
   INSERT INTO ds_controlled_view(view_id,owner_org_id,source_dataset_id,source_version_id,source_raw_commit_id,dataset_id,version_id,
    rule_version,rule_manifest_sha256,private_rule_manifest,public_scope_summary,state,created_by)
   VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,'BUILDING',?)
   """,uuid(p.path("viewId").asText()),org,uuid(source.path("datasetId").asText()),uuid(source.path("versionId").asText()),uuid(source.path("rawCommitId").asText()),
    dataset,version,p.path("rules").path("schema").asText(),p.path("ruleSha256").asText(),encode(p.path("rules")),encode(publicScope(p)),actor);
  db.update("""
   INSERT INTO ds_controlled_view_publication(publication_id,review_id,attempt_id,view_id,dataset_id,version_id,raw_repo_id,raw_commit_id,index_repo_id,raw_manifest_object_key,raw_manifest_sha256)
   VALUES(?,?,?,?,?,?,?,?,?,?,?)
   """,pub,review,uuid(p.path("attemptId").asText()),uuid(p.path("viewId").asText()),dataset,version,raw,commit,index,snapshot.manifestKey(),snapshot.manifestSha());
  authority.attachParent(p);
  db.update("INSERT INTO ds_controlled_view_publication_state(publication_id) VALUES(?)",pub);
  db.update("UPDATE ds_controlled_view_publication_review SET applied_at=clock_timestamp() WHERE review_id=?",review);
  event(pub,review,actor,"RAW_PUBLISHED",Map.of("rawManifestSha256",snapshot.manifestSha(),"fileCount",snapshot.files().size(),"consumerAuthorized",false,"previewPromotedInPlace",false));
 }
 public Map<String,Object> confirm(ConfirmRequest req,String token)throws Exception{
  if(req==null||req.reviewId()==null||!Boolean.TRUE.equals(req.confirm())||req.expectedReviewSha256()==null||!req.expectedReviewSha256().matches("[a-f0-9]{64}"))
   fail(HttpStatus.BAD_REQUEST,"CONTROLLED_VIEW_EXACT_REVIEW_CONFIRMATION_REQUIRED");
  var row=reviewRow(req.reviewId(),false);JsonNode p=payload(row);UUID pub=uuid(row.get("publication_id"));
  if(!req.expectedReviewSha256().equals(row.get("payload_sha256").toString().trim()))fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_CONFIRMATION_REVIEW_CHANGED");
  if(row.get("applied_at")!=null)return get(pub);
  if(!((Timestamp)row.get("expires_at")).toInstant().isAfter(Instant.now()))fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_REVIEW_EXPIRED");
  authority.current(p);
  if(!copying.tryAcquire())fail(HttpStatus.TOO_MANY_REQUESTS,"CONTROLLED_VIEW_PUBLICATION_COPY_BUSY");
  List<String> ownedStagingKeys=new ArrayList<>();
  try{
   Copied copied=copy(p,ownedStagingKeys);
   tx.executeWithoutResult(status->{
    authority.fence();var now=reviewRow(req.reviewId(),true);JsonNode current=payload(now);
    if(now.get("applied_at")!=null)return;
    if(!req.expectedReviewSha256().equals(now.get("payload_sha256").toString().trim())||!((Timestamp)now.get("expires_at")).toInstant().isAfter(Instant.now()))
     fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_REVIEW_CHANGED_OR_EXPIRED");
    authority.current(current);insertSnapshot(req.reviewId(),current,copied);
   });
  }finally{
   copying.release();cleanupStaging(req.reviewId(),pub,ownedStagingKeys);
  }
  return start(pub,token);
 }
 private void cleanupStaging(UUID review,UUID publication,List<String> keys){
  if(keys.isEmpty())return;
  try{
   // Wait for any uncertain metadata transaction to release the same review
   // fence before deciding whether these unique, call-owned keys are unreferenced.
   List<String> remove=tx.execute(status->{
    authority.fence();db.queryForList("SELECT review_id FROM ds_controlled_view_publication_review WHERE review_id=? FOR UPDATE",review);
    var result=new ArrayList<String>();for(String key:keys)
     if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM artifact_file WHERE file_key=?) OR EXISTS(SELECT 1 FROM artifact_commit WHERE manifest_object_key=?)",Boolean.class,key,key)))result.add(key);
    return result;
   });
   if(remove!=null)for(String key:remove)objects.removeObject(key);
  }catch(Exception deferred){log.warn("Unreferenced controlled-view copy cleanup deferred: publication={} type={}",publication,deferred.getClass().getSimpleName());}
 }
 public Map<String,Object> start(UUID publication,String token)throws Exception{
  var r=authority.origin(publication);
  if(!"RAW_PUBLISHED".equals(r.get("publication_state")))return get(publication);
  try(var scope=authority.enter(publication)){
   JsonNode p=authority.plan(r),program=p.path("indexProgram");
   indexes.buildIndex(uuid(r.get("dataset_id")),uuid(r.get("version_id")),new MmBuildIndexReq(program.path("modelName").asText(),program.path("indexType").asText(),false,json.convertValue(program.path("indexConfig"),Map.class),true),token);
  }
  return get(publication);
 }
 private boolean manage(AuthorizationService.Actor a,Map<String,Object> r){
  if(!a.orgId().equals(r.get("actor_org_id")))return false;
  // A current explicit sharing grant configures this view even when its content
  // policy is revoked; this path never grants a content read.
  boolean configured=Boolean.TRUE.equals(db.queryForObject("""
   SELECT EXISTS(SELECT 1 FROM ds_resource_grant g WHERE g.dataset_id=? AND g.owner_org_id=? AND g.grantee_org_id=?
    AND g.subject_kind IN('USER','ORG') AND ((g.subject_kind='USER' AND g.subject_id=?) OR (g.subject_kind='ORG' AND g.subject_id=?))
    AND g.scope_kind='CONTROLLED_VIEW' AND g.state='ACTIVE' AND 'dataset.share.manage'=ANY(g.operation_codes)
    AND 'GENERAL_READ'=ANY(g.purpose_codes) AND g.valid_from<=clock_timestamp() AND (g.valid_to IS NULL OR clock_timestamp()<g.valid_to)
    AND (g.contract_id IS NULL OR EXISTS(SELECT 1 FROM ds_contract c WHERE c.contract_id=g.contract_id AND c.revision=g.contract_revision AND c.status='CONFIRMED' AND c.revoked_at IS NULL AND c.valid_from<=clock_timestamp() AND clock_timestamp()<c.valid_to)))
   """,Boolean.class,r.get("dataset_id"),a.orgId(),a.orgId(),a.userId(),a.orgId()));
  if(configured)return true;
  JsonNode source=authority.plan(r).path("source");
  return auth.canUseDataset(a,uuid(source.path("datasetId").asText()),uuid(source.path("versionId").asText()),
   source.path("indexVersionId").isNull()?null:uuid(source.path("indexVersionId").asText()),ControlledViewPublicationAuthority.SHARE,"VIEW_BUILD");
 }
 private void requireManagement(Map<String,Object> r){
  var a=authority.actor();
  if(Set.of("READY","REVOKED").contains(r.get("publication_state"))){if(!manage(a,r))fail(HttpStatus.NOT_FOUND,"CONTROLLED_VIEW_PUBLICATION_NOT_AVAILABLE");return;}
  Claims c=authority.interactive();if(!a.userId().equals(r.get("actor_user_id"))||!a.orgId().equals(r.get("actor_org_id"))||
   !uuid(c.get("sid",String.class)).equals(r.get("parent_session_id"))||a.tokenVersion()!=((Number)r.get("actor_token_version")).intValue())
   fail(HttpStatus.NOT_FOUND,"CONTROLLED_VIEW_PUBLICATION_NOT_AVAILABLE");
  if(!Set.of("FAILED","CANCELED").contains(r.get("publication_state")))authority.current(authority.plan(r));
 }
 public Map<String,Object> get(UUID publication){
  var r=authority.origin(publication);requireManagement(r);var out=new LinkedHashMap<String,Object>();JsonNode p=authority.plan(r);
  for(var pair:Map.of("publicationId","publication_id","viewId","view_id","datasetId","dataset_id","versionId","version_id").entrySet())out.put(pair.getKey(),r.get(pair.getValue()));
  out.put("state",r.get("publication_state"));out.put("version",r.get("publication_version"));
  out.put("reasonCode",r.get("publication_reason_code"));out.put("targetOrgName",p.path("targetOrgName").isTextual()?p.path("targetOrgName").asText():db.queryForObject("SELECT org_name FROM org WHERE org_id=?",String.class,uuid(p.path("target").path("orgId").asText())));out.put("name",p.path("name").asText());out.put("target",p.path("target"));
  out.put("consumerOperations",p.path("consumerOperations"));out.put("providerOperations",p.path("providerOperations"));out.put("approved",true);
  out.put("published","READY".equals(r.get("publication_state")));out.put("authorizationGranted","READY".equals(r.get("publication_state")));
  out.put("hasOriginalByteCopies",p.path("hasOriginalByteCopies"));out.put("validFrom",p.path("validFrom"));out.put("validTo",p.path("validTo"));
  var index=db.queryForList("SELECT index_version_id,job_id,run_id FROM ds_controlled_view_publication_index WHERE publication_id=?",publication);
  if(!index.isEmpty()){out.put("index",index.get(0));out.put("jobStatus",db.queryForObject("SELECT run_status::text FROM job_run WHERE run_id=?",String.class,index.get(0).get("run_id")));}
  out.put("policyCurrent",db.queryForObject("SELECT controlled_view_publication_policy_current(?)",Boolean.class,r.get("dataset_id")));
  out.put("activeGrantCount",db.queryForObject("SELECT count(*) FROM ds_resource_grant WHERE dataset_id=? AND state='ACTIVE'",Long.class,r.get("dataset_id")));
  out.put("consumerGrantCurrentlyEffective",Boolean.TRUE.equals(db.queryForObject("""
   SELECT EXISTS(SELECT 1 FROM ds_controlled_view_publication_grant pg JOIN ds_resource_grant g ON g.grant_id=pg.grant_id
    JOIN ds_controlled_view cv ON cv.dataset_id=g.dataset_id AND cv.version_id=g.version_id AND cv.index_version_id=g.index_version_id
    JOIN org provider ON provider.org_id=g.owner_org_id AND provider.org_status=1 JOIN org consumer ON consumer.org_id=g.grantee_org_id AND consumer.org_status=1
    WHERE pg.publication_id=? AND pg.grant_kind='CONSUMER' AND g.state='ACTIVE' AND g.valid_from<=clock_timestamp()
     AND (g.valid_to IS NULL OR clock_timestamp()<g.valid_to) AND cv.state='READY' AND cv.authorization_version=g.view_authorization_version
     AND controlled_view_publication_policy_current(g.dataset_id)
     AND (g.contract_id IS NULL OR EXISTS(SELECT 1 FROM ds_contract c WHERE c.contract_id=g.contract_id AND c.revision=g.contract_revision
      AND c.status='CONFIRMED' AND c.revoked_at IS NULL AND c.valid_from<=clock_timestamp() AND clock_timestamp()<c.valid_to)))
   """,Boolean.class,publication)));

  return out;
 }
 public List<Map<String,Object>> list(Integer maximum){
  int limit=maximum==null?50:maximum;if(limit<1||limit>100)fail(HttpStatus.BAD_REQUEST,"CONTROLLED_VIEW_PUBLICATION_LIST_LIMIT");var actor=authority.actor();var out=new ArrayList<Map<String,Object>>();
  for(UUID id:db.queryForList("""
   SELECT p.publication_id FROM ds_controlled_view_publication p JOIN ds_controlled_view_publication_review r ON r.review_id=p.review_id
   WHERE r.actor_org_id=? ORDER BY p.created_at DESC,p.publication_id LIMIT 1000
   """,UUID.class,actor.orgId())){
   try{out.add(get(id));if(out.size()>=limit)break;}catch(ResponseStatusException denied){if(denied.getStatusCode().value()>=500)throw denied;}
  }return out;
 }
 private void verifySnapshot(UUID repo,UUID commit,JsonNode p,long maximum)throws Exception{
  var files=manifests.getManifest(repo,commit).getFiles();if(files.isEmpty()||files.size()>50000)fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_SNAPSHOT_FILE_COUNT_INVALID");long total=0;
  for(var f:files){
   authority.current(p);var rows=db.queryForList("SELECT file_key,size_bytes,btrim(sha256) AS sha,upload_state FROM artifact_file WHERE commit_id=? AND file_id=?",commit,f.getFileId());
   if(rows.size()!=1)fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_SNAPSHOT_FILE_IDENTITY_CHANGED");var v=rows.get(0);
   long size=((Number)v.get("size_bytes")).longValue();total+=size;if(size<0||total>maximum)fail(HttpStatus.PAYLOAD_TOO_LARGE,"CONTROLLED_VIEW_SNAPSHOT_VERIFICATION_BYTE_LIMIT");
   if(!"READY".equals(v.get("upload_state"))||!Objects.toString(v.get("sha"),"").matches("[a-f0-9]{64}")||objects.statObject(v.get("file_key").toString()).size()!=size||
    !v.get("sha").equals(streamHash(v.get("file_key").toString(),size,p)))fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_ACTUAL_FROZEN_BYTES_CHANGED");
  }
 }
 private void insertGrant(UUID publication,JsonNode p,Map<String,Object> r,UUID index,boolean provider){
  UUID id=uuid(p.path(provider?"providerGrantId":"consumerGrantId").asText()),org=uuid(p.path(provider?"actor":"target").path("orgId").asText());
  UUID subject=provider?uuid(p.path("actor").path("userId").asText()):org;JsonNode ops=p.path(provider?"providerOperations":"consumerOperations");
  var names=new TreeSet<String>();ops.forEach(x->names.add(x.asText()));Instant until=p.path("validTo").isNull()?null:Instant.parse(p.path("validTo").asText());
  db.update("""
   INSERT INTO ds_resource_grant(grant_id,subject_kind,subject_id,grantee_org_id,owner_org_id,scope_kind,dataset_id,version_id,index_version_id,view_authorization_version,
    operation_codes,purpose_codes,valid_from,valid_to,created_by)
   VALUES(?,?,?,?,?,'CONTROLLED_VIEW',?,?,?,1,?::varchar[],ARRAY['GENERAL_READ']::varchar[],?,?,?)
   """,id,provider?"USER":"ORG",subject,org,r.get("actor_org_id"),r.get("dataset_id"),r.get("version_id"),index,"{"+String.join(",",names)+"}",
    Timestamp.from(Instant.parse(p.path(provider?"providerValidFrom":"validFrom").asText())),until==null?null:Timestamp.from(until),r.get("actor_user_id"));
  db.update("INSERT INTO ds_controlled_view_publication_grant(grant_id,publication_id,grant_kind) VALUES(?,?,?)",id,publication,provider?"PROVIDER":"CONSUMER");
 }
 private void settle(UUID id,String state,String reason){
  tx.executeWithoutResult(status->{
   authority.fence();var r=authority.origin(id);
   int changed=db.update("UPDATE ds_controlled_view_publication_state SET state=?,version=version+1,reason_code=?,updated_at=clock_timestamp() WHERE publication_id=? AND state IN('RAW_PUBLISHED','INDEXING')",state,reason,id);
   if(changed==1){
    db.update("UPDATE ds_controlled_view SET state='FAILED' WHERE view_id=? AND state='BUILDING'",r.get("view_id"));
    event(id,uuid(r.get("review_id")),null,state,Map.of("reasonCode",reason,"consumerAuthorized",false));
   }
  });
 }
 private void finish(UUID id)throws Exception{
  var original=authority.origin(id);
  if("RAW_PUBLISHED".equals(original.get("publication_state"))){authority.current(id);return;}
  if(!"INDEXING".equals(original.get("publication_state")))return;
  var rows=db.queryForList("""
   SELECT b.*,i.index_status,i.index_repo_id,c.manifest_object_key,btrim(c.manifest_sha256) AS sha,j.run_status,j.runtime_quota_released
   FROM ds_controlled_view_publication_index b JOIN mm_index_version i ON i.index_version_id=b.index_version_id
   JOIN artifact_commit c ON c.repo_id=i.index_repo_id AND c.commit_id=i.index_commit_id JOIN job_run j ON j.run_id=b.run_id AND j.job_id=b.job_id
   WHERE b.publication_id=?
   """,id);
  if(rows.size()!=1)fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_INDEX_ORIGIN_NOT_AVAILABLE");var b=rows.get(0);
  if(Set.of("FAILED","CANCELED","CANCELING").contains(b.get("run_status"))||Set.of("FAILED","DEPRECATED").contains(b.get("index_status"))){
   settle(id,"CANCELED".equals(b.get("run_status"))||"CANCELING".equals(b.get("run_status"))?"CANCELED":"FAILED","CONTROLLED_VIEW_NATIVE_INDEX_EXECUTION_FAILED");return;
  }
  authority.current(id);
  if(!"SUCCEEDED".equals(b.get("run_status"))||!"READY".equals(b.get("index_status"))||((Number)b.get("runtime_quota_released")).intValue()!=1)return;
  JsonNode p=authority.plan(original);
  verifySnapshot(uuid(original.get("raw_repo_id")),uuid(original.get("raw_commit_id")),p,RAW_LIMIT);
  verifySnapshot(uuid(original.get("index_repo_id")),uuid(b.get("index_commit_id")),p,INDEX_LIMIT);
  tx.executeWithoutResult(status->{
   authority.fence();var r=authority.current(id);if(!"INDEXING".equals(r.get("publication_state")))return;
   if(!Boolean.TRUE.equals(db.queryForObject("""
    SELECT EXISTS(SELECT 1 FROM ds_controlled_view_publication_index b JOIN mm_index_version i ON i.index_version_id=b.index_version_id
     JOIN job_run j ON j.run_id=b.run_id AND j.job_id=b.job_id JOIN artifact_commit c ON c.repo_id=i.index_repo_id AND c.commit_id=i.index_commit_id
     JOIN mm_dataset_version v ON v.version_id=i.dataset_version_id
     WHERE b.publication_id=? AND i.index_status='READY' AND j.run_status='SUCCEEDED' AND j.runtime_quota_released=1 AND c.commit_status='PUBLISHED'
      AND c.manifest_object_key=? AND c.manifest_sha256=? AND v.active_index_version_id=i.index_version_id)
    """,Boolean.class,id,b.get("manifest_object_key"),b.get("sha"))))fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_FINAL_NATIVE_PUBLICATION_CHANGED");
   if(db.update("UPDATE ds_controlled_view SET state='READY' WHERE view_id=? AND state='BUILDING'",r.get("view_id"))!=1)fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_ALREADY_SETTLED");
   insertGrant(id,p,r,uuid(b.get("index_version_id")),true);insertGrant(id,p,r,uuid(b.get("index_version_id")),false);
   db.update("UPDATE ds_controlled_view_publication_state SET state='READY',version=version+1,reason_code='NATIVE_INDEX_PREWARM_AND_ALL_FROZEN_BYTES_VERIFIED',updated_at=clock_timestamp() WHERE publication_id=? AND state='INDEXING'",id);
   event(id,uuid(r.get("review_id")),uuid(r.get("actor_user_id")),"READY",Map.of("indexVersionId",b.get("index_version_id"),"indexManifestSha256",b.get("sha"),"providerGrantId",text(p,"providerGrantId"),"consumerGrantId",text(p,"consumerGrantId"),"allFrozenBytesVerified",true));
  });
 }
 public Map<String,Object> reconcile(UUID id)throws Exception{
  requireManagement(authority.origin(id));try{finish(id);}catch(ResponseStatusException denied){if(denied.getStatusCode().value()<500)settle(id,"FAILED","CONTROLLED_VIEW_PUBLICATION_AUTHORITY_OR_BYTES_REJECTED");throw denied;}return get(id);
 }
 public Map<String,Object> revoke(UUID id,RevokeRequest req){
  if(req==null||req.expectedVersion()==null||!Boolean.TRUE.equals(req.confirm()))fail(HttpStatus.BAD_REQUEST,"CONTROLLED_VIEW_REVOKE_CONFIRMATION_REQUIRED");String why=reason(req.reason());
  return tx.execute(status->{
   authority.fence();var r=authority.origin(id);requireManagement(r);
   if(!"READY".equals(r.get("publication_state"))||((Number)r.get("publication_version")).longValue()!=req.expectedVersion())fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_PUBLICATION_VERSION_CHANGED");
   long affected=db.queryForObject("SELECT count(*) FROM ds_resource_grant WHERE dataset_id=? AND state='ACTIVE'",Long.class,r.get("dataset_id"));
   db.update("UPDATE ds_controlled_view SET state='REVOKED',authorization_version=authorization_version+1 WHERE view_id=? AND state='READY'",r.get("view_id"));
   db.update("UPDATE ds_resource_grant SET state='REVOKED',version=version+1,revoked_at=clock_timestamp(),revoke_reason='CONTROLLED_VIEW_REVOKED' WHERE dataset_id=? AND state='ACTIVE'",r.get("dataset_id"));
   db.update("UPDATE ds_controlled_view_publication_state SET state='REVOKED',version=version+1,reason_code='REVIEWED_CONTROLLED_VIEW_REVOKED',updated_at=clock_timestamp() WHERE publication_id=? AND state='READY'",id);
   event(id,uuid(r.get("review_id")),authority.actor().userId(),"REVOKED",Map.of("reason",why,"affectedActiveGrants",affected,"before","READY","after","REVOKED"));
   return Map.of("publicationId",id,"before","READY","state","REVOKED","version",req.expectedVersion()+1,"affectedActiveGrants",affected);
  });
 }
 @Scheduled(fixedDelay=1000) public void reconcilePending(){
  for(UUID id:db.queryForList("SELECT publication_id FROM ds_controlled_view_publication_state WHERE state IN('RAW_PUBLISHED','INDEXING') ORDER BY updated_at,publication_id LIMIT 8",UUID.class)){
   if(!verifying.add(id))continue;
   try{verifier.execute(()->{
    try{finish(id);}catch(ResponseStatusException denied){if(denied.getStatusCode().value()<500)settle(id,"FAILED","CONTROLLED_VIEW_PUBLICATION_AUTHORITY_OR_BYTES_REJECTED");else log.warn("Controlled view publication dependency unavailable: publication={}",id);}
    catch(Exception dependency){log.warn("Controlled view publication deferred: publication={} type={}",id,dependency.getClass().getSimpleName());}
    finally{verifying.remove(id);}
   });}catch(RejectedExecutionException busy){verifying.remove(id);}
  }
 }
 @PreDestroy public void stop(){verifier.shutdownNow();}
}
