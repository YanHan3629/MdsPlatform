package com.fwdrobo.sirius.service;
import com.fwdrobo.sirius.dto.file.*;
import com.fwdrobo.sirius.mapper.ArtifactFileMapper;
import com.fwdrobo.sirius.mapper.OrgStorageUsageMapper;
import com.fwdrobo.sirius.security.ArtifactWriteAuthority;
import com.fwdrobo.sirius.util.*;
import io.minio.StatObjectResponse;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** Upload bytes remain private until live authorization and an atomic metadata switch. */
@Service @Slf4j
public class ArtifactUploadService {
 @org.springframework.beans.factory.annotation.Autowired private com.fwdrobo.sirius.security.GenericFileOriginService origins;
 public static final long AUTHORITY_LOCK=805014630400001L;
 private final JdbcTemplate jdbc;private final MinioService minio;private final ManifestService manifests;
 private final ArtifactFileMapper files;private final TrialLimitService quotas;private final OrgStorageUsageMapper usage;
 private final DatasetAccessService access;private final ArtifactWriteAuthority authority;private final TransactionTemplate tx;
 private final long maximumBytes;private final int maximumSeconds;private final Semaphore active;
 private final ScheduledExecutorService watchdog=Executors.newScheduledThreadPool(4,r->{Thread t=new Thread(r,"artifact-upload-authority");t.setDaemon(true);return t;});
 private final ConcurrentMap<UUID,GuardedInput> activeInputs=new ConcurrentHashMap<>();
 public ArtifactUploadService(JdbcTemplate jdbc,MinioService minio,ManifestService manifests,ArtifactFileMapper files,
  TrialLimitService quotas,OrgStorageUsageMapper usage,DatasetAccessService access,ArtifactWriteAuthority authority,
  PlatformTransactionManager transactions,@Value("${artifact.upload.maximum-bytes:1073741824}")long maximumBytes,
  @Value("${artifact.upload.maximum-seconds:900}")int maximumSeconds,@Value("${artifact.upload.maximum-active:16}")int maximumActive){
  this.jdbc=jdbc;this.minio=minio;this.manifests=manifests;this.files=files;this.quotas=quotas;this.usage=usage;this.access=access;this.authority=authority;
  this.tx=new TransactionTemplate(transactions);this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);this.tx.setTimeout(30);
  if(maximumBytes<1||maximumSeconds<5||maximumSeconds>3600||maximumActive<1||maximumActive>64)throw new IllegalArgumentException("Invalid upload bounds");
  this.maximumBytes=maximumBytes;this.maximumSeconds=maximumSeconds;this.active=new Semaphore(maximumActive);
 }
 private record Attempt(UUID id,UUID repo,UUID commit,UUID user,UUID org,String path,String key,long reserved){}
 private record Completed(UUID fileId,long size,String contentType,String previousKey){}
 private static final class CheckedFailure extends RuntimeException{CheckedFailure(Exception cause){super(cause);}}
 private <T>T transaction(ArtifactWriteAuthority.Work<T> work)throws Exception{
  try{return tx.execute(status->{try{return work.run();}catch(RuntimeException error){throw error;}catch(Exception error){throw new CheckedFailure(error);}});}
  catch(CheckedFailure error){throw (Exception)error.getCause();}
 }
 private void sharedAuthority(){jdbc.execute("SELECT pg_advisory_xact_lock_shared("+AUTHORITY_LOCK+"::bigint)");}
 private void draft(UUID repo,UUID commit){
  var rows=jdbc.queryForList("SELECT commit_status FROM artifact_commit WHERE repo_id=? AND commit_id=? FOR UPDATE",repo,commit);
  if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"UPLOAD_SCOPE_NOT_AVAILABLE");
  if(!"DRAFT".equals(rows.get(0).get("commit_status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"ONLY_DRAFT_CAN_CHANGE");
 }
 private static long size(FileResp file){return file==null||file.getSizeBytes()==null?0L:Math.max(0,file.getSizeBytes());}
 private Attempt prepare(UUID repo,UUID commit,String path,String contentType,long expectedSize,ArtifactWriteAuthority.Guard guard)throws Exception{
  try{return transaction(()->{
   sharedAuthority();draft(repo,commit);guard.check();access.requireBusinessFile(repo,path);
   if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM artifact_upload_attempt WHERE commit_id=? AND logical_path=? AND state='PREPARED')",Boolean.class,commit,path)))
    throw new ResponseStatusException(HttpStatus.CONFLICT,"UPLOAD_ALREADY_IN_PROGRESS");
   FileResp previous=files.selectByCommitIdAndPath(commit,path);
   long reserved=quotas.reserveUploadQuota(expectedSize,size(previous));UUID id=UUID.randomUUID();
   String category=DataFileFormatClassifier.classify(path,contentType).storageCategory();
   String key="workspace/"+repo+"/"+commit+"/attempts/"+id+"/"+category+"/"+path.substring(1);
   var actor=guard.actor();
   jdbc.update("""
    INSERT INTO artifact_upload_attempt(attempt_id,repo_id,commit_id,actor_user_id,actor_org_id,logical_path,object_key,
      operation_code,purpose_code,expected_size,reserved_bytes,expires_at)
    VALUES(?,?,?,?,?,?,?,?,?,?,?,clock_timestamp()+(? * interval '1 second'))
    """,id,repo,commit,actor.userId(),actor.orgId(),path,key,guard.operation(),guard.purpose(),expectedSize,reserved,maximumSeconds);
   return new Attempt(id,repo,commit,actor.userId(),actor.orgId(),path,key,reserved);
  });}catch(DataIntegrityViolationException conflict){throw new ResponseStatusException(HttpStatus.CONFLICT,"UPLOAD_ALREADY_IN_PROGRESS");}
 }
 public UploadFileResp upload(UUID repo,UUID commit,String path,String contentType,InputStream input,long expectedSize,UUID device)throws Exception{
  if(expectedSize>maximumBytes)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"UPLOAD_BYTE_LIMIT");
  if(expectedSize< -1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_UPLOAD_SIZE");
  String logical=PathUtils.normalizePath(path);if(contentType==null||contentType.isBlank())contentType="application/octet-stream";
  ArtifactWriteAuthority.Guard guard=authority.capture(repo,commit,"dataset.upload");
  if(!active.tryAcquire())throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"UPLOAD_CAPACITY_BUSY");
  Attempt attempt=null;GuardedInput protectedInput=null;
  try{
   attempt=prepare(repo,commit,logical,contentType,expectedSize,guard);
   protectedInput=new GuardedInput(input,guard);activeInputs.put(attempt.id(),protectedInput);protectedInput.start();
   String etag=minio.putObject(attempt.key(),protectedInput,expectedSize,contentType);
   protectedInput.verify(true);StatObjectResponse metadata=minio.statObject(attempt.key());
   long actualSize=metadata.size();
   if(actualSize!=protectedInput.count||expectedSize>=0&&actualSize!=expectedSize)throw new ResponseStatusException(HttpStatus.CONFLICT,"UPLOAD_SIZE_MISMATCH");
   String sha=HexFormat.of().formatHex(protectedInput.digest.digest());
   final Attempt accepted=attempt;final GuardedInput streamed=protectedInput;final String acceptedEtag=etag;
   Completed completed=transaction(()->{
    sharedAuthority();draft(repo,commit);streamed.verify(true);
    var rows=jdbc.queryForList("SELECT state,expires_at>clock_timestamp() AS valid FROM artifact_upload_attempt WHERE attempt_id=? FOR UPDATE",accepted.id());
    if(rows.size()!=1||!"PREPARED".equals(rows.get(0).get("state"))||!Boolean.TRUE.equals(rows.get(0).get("valid")))
     throw new ResponseStatusException(HttpStatus.CONFLICT,"UPLOAD_ATTEMPT_ENDED");
    FileResp previous=files.selectByCommitIdAndPath(commit,logical);long oldSize=size(previous);
    var result=files.addOrReplaceFileFull(commit,logical,minio.getBucket(),accepted.key(),metadata.versionId(),acceptedEtag,
     actualSize,metadata.contentType(),device,sha);
    origins.captureUploadedFile(repo,commit,result.getFileId());
    manifests.addOrReplaceFile(repo,commit,logical,result.getFileId());
    streamed.verify(true);
    long adjustment=actualSize-oldSize-accepted.reserved();
    if(adjustment>0)quotas.addUsedStorageAfterSuccess(adjustment);else if(adjustment<0)quotas.reduceUsedStorageAfterSuccess(-adjustment);
    if(jdbc.update("UPDATE artifact_upload_attempt SET state='COMMITTED',final_file_id=?,updated_at=clock_timestamp() WHERE attempt_id=? AND state='PREPARED'",result.getFileId(),accepted.id())!=1)
     throw new ResponseStatusException(HttpStatus.CONFLICT,"UPLOAD_ATTEMPT_ENDED");
    return new Completed(result.getFileId(),actualSize,metadata.contentType(),previous==null?null:previous.getFileKey());
   });
   discardOldIfUnreferenced(completed.previousKey(),attempt);
   return new UploadFileResp(repo,commit,completed.fileId(),device,logical,completed.size(),completed.contentType());
  }catch(Exception error){
   RuntimeException denied=protectedInput==null?null:protectedInput.failure.get();
   if(attempt!=null)abortAndCleanup(attempt,denied==null?"UPLOAD_DID_NOT_COMMIT":"UPLOAD_AUTHORITY_REVOKED");
   if(denied!=null)throw denied;
   throw error;
  }finally{
   if(attempt!=null)activeInputs.remove(attempt.id());
   if(protectedInput!=null)protectedInput.close();
   active.release();
  }
 }
 private final class GuardedInput extends FilterInputStream {
  final ArtifactWriteAuthority.Guard guard;final MessageDigest digest;final AtomicReference<RuntimeException> failure=new AtomicReference<>();
  final long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(maximumSeconds);
  volatile boolean closed;long lastChecked;long count;ScheduledFuture<?> timer;
  GuardedInput(InputStream input,ArtifactWriteAuthority.Guard guard)throws Exception{super(input);this.guard=guard;digest=MessageDigest.getInstance("SHA-256");}
  void start(){timer=watchdog.scheduleWithFixedDelay(()->{if(!closed)try{verify(true);}catch(RuntimeException denied){stop(denied);}},0,1,TimeUnit.SECONDS);}
  synchronized void verify(boolean force){
   if(failure.get()!=null)throw failure.get();
   long now=System.nanoTime();
   if(now>=deadline){RuntimeException ended=new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT,"UPLOAD_DEADLINE");failure.compareAndSet(null,ended);throw ended;}
   if(force||now-lastChecked>=TimeUnit.MILLISECONDS.toNanos(500)){
    try{guard.check();lastChecked=now;}catch(RuntimeException denied){failure.compareAndSet(null,denied);throw denied;}
   }
  }
  void stop(RuntimeException denied){failure.compareAndSet(null,denied);try{in.close();}catch(IOException ignored){}}
  @Override public int read()throws IOException{byte[] single=new byte[1];int n=read(single,0,1);return n<0?-1:single[0]&255;}
  @Override public int read(byte[] bytes,int offset,int length)throws IOException{
   if(length==0)return 0;verify(false);
   int n=in.read(bytes,offset,Math.min(length,65536));
   verify(false);
   if(n>0){
    if(count>maximumBytes-n){RuntimeException exceeded=new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"UPLOAD_BYTE_LIMIT");failure.compareAndSet(null,exceeded);throw exceeded;}
    digest.update(bytes,offset,n);count+=n;
   }
   return n;
  }
  @Override public long skip(long length)throws IOException{long consumed=0;byte[] buffer=new byte[8192];while(consumed<length){int n=read(buffer,0,(int)Math.min(buffer.length,length-consumed));if(n<0)break;consumed+=n;}return consumed;}
  @Override public boolean markSupported(){return false;}
  @Override public synchronized void reset()throws IOException{throw new IOException("Upload input is not replayable");}
  @Override public void close(){closed=true;if(timer!=null)timer.cancel(false);try{in.close();}catch(IOException ignored){}}
 }
 private boolean referenced(String key){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM artifact_file WHERE file_key=?)",Boolean.class,key));}
 private boolean owned(Attempt attempt){return attempt.key().startsWith("workspace/"+attempt.repo()+"/"+attempt.commit()+"/attempts/"+attempt.id()+"/");}
 private void releaseReservation(Attempt attempt)throws Exception{
  transaction(()->{
   var rows=jdbc.queryForList("SELECT state,quota_released,reserved_bytes FROM artifact_upload_attempt WHERE attempt_id=? FOR UPDATE",attempt.id());
   if(rows.size()==1&&!"COMMITTED".equals(rows.get(0).get("state"))&&!Boolean.TRUE.equals(rows.get(0).get("quota_released"))){
    long reserved=((Number)rows.get(0).get("reserved_bytes")).longValue();if(reserved>0)usage.reduceUsedStorageBytes(attempt.org(),reserved);
    jdbc.update("UPDATE artifact_upload_attempt SET quota_released=true,updated_at=clock_timestamp() WHERE attempt_id=?",attempt.id());
   }return null;
  });
 }
 private void abortAndCleanup(Attempt attempt,String reason){
  try{
   boolean removable=transaction(()->{
    var rows=jdbc.queryForList("SELECT state FROM artifact_upload_attempt WHERE attempt_id=? FOR UPDATE",attempt.id());
    if(rows.size()!=1||"COMMITTED".equals(rows.get(0).get("state"))||referenced(attempt.key()))return false;
    jdbc.update("UPDATE artifact_upload_attempt SET state='ABORTED',failure_code=?,updated_at=clock_timestamp() WHERE attempt_id=?",reason,attempt.id());return true;
   });
   if(!removable)return;releaseReservation(attempt);
   try{
    if(!owned(attempt))throw new IllegalStateException("UPLOAD_CLEANUP_SCOPE_INVALID");
    minio.removeObject(attempt.key());
    jdbc.update("UPDATE artifact_upload_attempt SET state='ABORTED',cleanup_completed_at=clock_timestamp(),updated_at=clock_timestamp() WHERE attempt_id=? AND state<>'COMMITTED'",attempt.id());
   }catch(Exception cleanup){
    jdbc.update("UPDATE artifact_upload_attempt SET state='CLEANUP_REQUIRED',updated_at=clock_timestamp() WHERE attempt_id=? AND state<>'COMMITTED'",attempt.id());
    log.warn("Upload object cleanup pending: attempt={}",attempt.id());
   }
  }catch(Exception unknown){log.warn("Upload outcome requires reconciliation; object retained: attempt={}",attempt.id());}
 }
 private void discardOldIfUnreferenced(String key,Attempt attempt){
  if(key==null||key.equals(attempt.key())||!key.startsWith("workspace/"+attempt.repo()+"/"+attempt.commit()+"/"))return;
  try{if(!referenced(key))minio.removeObject(key);}catch(Exception retained){log.warn("Replaced private object retained for cleanup: attempt={}",attempt.id());}
 }
 public PresignPutResp begin(UUID repo,UUID commit,String path,String type,Duration expire,UUID device)throws Exception{
  if(expire==null||expire.isNegative()||expire.isZero())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_UPLOAD_EXPIRY");
  String logical=PathUtils.normalizePath(path);var guard=authority.capture(repo,commit,"dataset.upload");
  transaction(()->{sharedAuthority();draft(repo,commit);guard.check();access.requireBusinessFile(repo,logical);return null;});
  String proxy="/api/artifacts/"+repo+"/commits/"+commit+"/files?path="+URLEncoder.encode(logical,StandardCharsets.UTF_8);
  if(type!=null&&!type.isBlank())proxy+="&contentType="+URLEncoder.encode(type,StandardCharsets.UTF_8);
  return new PresignPutResp("",proxy);
 }
 public FileResp confirm(UUID repo,UUID commit,String path)throws Exception{
  String logical=PathUtils.normalizePath(path);var guard=authority.capture(repo,commit,"dataset.upload");
  FileResp file=transaction(()->{sharedAuthority();draft(repo,commit);guard.check();return files.selectByCommitIdAndPath(commit,logical);});
  if(file==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"UPLOAD_CONTENT_NOT_FOUND");
  if("READY".equals(file.getUploadState())&&file.getSha256()!=null&&file.getSha256().matches("[a-fA-F0-9]{64}"))return file;
  guard.check();StatObjectResponse stat=minio.statObject(file.getFileKey());
  try(InputStream input=minio.getObject(file.getFileKey())){upload(repo,commit,logical,stat.contentType(),input,stat.size(),file.getDeviceId());}
  return files.selectByCommitIdAndPath(commit,logical);
 }
 public void delete(UUID repo,UUID commit,String path)throws Exception{
  String logical=PathUtils.normalizePath(path);var guard=authority.capture(repo,commit,"dataset.draft.manage");
  FileResp removed=transaction(()->{
   sharedAuthority();draft(repo,commit);guard.check();
   if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM artifact_upload_attempt WHERE commit_id=? AND logical_path=? AND state='PREPARED')",Boolean.class,commit,logical)))
    throw new ResponseStatusException(HttpStatus.CONFLICT,"UPLOAD_ALREADY_IN_PROGRESS");
   var manifest=manifests.getManifest(repo,commit);UUID id=manifest.getFiles().stream().filter(f->logical.equals(f.getPath())).map(f->f.getFileId()).findFirst().orElse(null);
   if(id==null)return null;
   var rows=files.selectFilesByIds(List.of(id));if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"FILE_MANIFEST_INCONSISTENT");
   FileResp existing=rows.get(0);manifests.removeFileEntry(repo,commit,logical);
   if(commit.equals(existing.getCommitId())){
    files.deleteByIdAndCommitId(id,commit);quotas.reduceUsedStorageAfterSuccess(size(existing));guard.check();return existing;
   }
   guard.check();return null;
  });
  if(removed!=null){
   String key=removed.getFileKey();
   if(key.startsWith("workspace/"+repo+"/"+commit+"/"))try{if(!referenced(key))minio.removeObject(key);}catch(Exception retained){log.warn("Deleted private object retained for cleanup: commit={}",commit);}
  }
 }
 public void requirePublishable(UUID repo,UUID commit){
  if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("PUBLICATION_TRANSACTION_REQUIRED");
  jdbc.execute("SELECT pg_advisory_xact_lock("+AUTHORITY_LOCK+"::bigint)");draft(repo,commit);
  if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM artifact_upload_attempt WHERE commit_id=? AND state='PREPARED')",Boolean.class,commit)))
   throw new ResponseStatusException(HttpStatus.CONFLICT,"UPLOADS_MUST_FINISH_BEFORE_PUBLICATION");
  try{
   var manifest=manifests.getManifest(repo,commit);
   for(var entry:manifest.getFiles()){
    var rows=jdbc.queryForList("""
     SELECT f.logical_path,f.upload_state,f.sha256,f.size_bytes FROM artifact_file f
       JOIN artifact_commit source ON source.commit_id=f.commit_id
      WHERE f.file_id=? AND source.repo_id=?
     """,entry.getFileId(),repo);
    if(rows.size()!=1||!entry.getPath().equals(rows.get(0).get("logical_path"))||!"READY".equals(rows.get(0).get("upload_state"))||
       !Objects.toString(rows.get(0).get("sha256"),"").matches("[a-fA-F0-9]{64}")||
       !(rows.get(0).get("size_bytes") instanceof Number)||((Number)rows.get(0).get("size_bytes")).longValue()<0)
     throw new ResponseStatusException(HttpStatus.CONFLICT,"FILE_IDENTITY_MUST_BE_READY_BEFORE_PUBLICATION");
   }
   origins.requirePublishable(repo,commit);
  }catch(ResponseStatusException denied){throw denied;}catch(Exception unavailable){throw new ResponseStatusException(HttpStatus.CONFLICT,"MANIFEST_NOT_READY_FOR_PUBLICATION");}
 }
 @Scheduled(fixedDelayString="${artifact.upload.cleanup-delay-ms:60000}",initialDelayString="${artifact.upload.cleanup-initial-delay-ms:30000}")
 public void reconcileExpired(){
  try{
   var rows=jdbc.queryForList("""
    SELECT attempt_id,repo_id,commit_id,actor_user_id,actor_org_id,logical_path,object_key,reserved_bytes
    FROM artifact_upload_attempt
    WHERE (state='PREPARED' AND expires_at<=clock_timestamp()) OR
      (state IN('ABORTED','CLEANUP_REQUIRED') AND cleanup_completed_at IS NULL)
    ORDER BY created_at LIMIT 100
    """);
   for(var row:rows){
    UUID id=(UUID)row.get("attempt_id");GuardedInput input=activeInputs.get(id);
    if(input!=null)input.stop(new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT,"UPLOAD_DEADLINE"));
    abortAndCleanup(new Attempt(id,(UUID)row.get("repo_id"),(UUID)row.get("commit_id"),(UUID)row.get("actor_user_id"),(UUID)row.get("actor_org_id"),
     (String)row.get("logical_path"),(String)row.get("object_key"),((Number)row.get("reserved_bytes")).longValue()),"UPLOAD_EXPIRED_OR_INTERRUPTED");
   }
  }catch(Exception unavailable){log.warn("Upload reconciliation deferred: {}",unavailable.getClass().getSimpleName());}
 }
 @PreDestroy public void stop(){
  for(GuardedInput input:activeInputs.values())input.stop(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"UPLOAD_SERVER_STOPPING"));
  watchdog.shutdownNow();
 }
}
