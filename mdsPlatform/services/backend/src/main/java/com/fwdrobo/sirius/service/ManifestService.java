package com.fwdrobo.sirius.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.dto.file.CommitManifest;
import com.fwdrobo.sirius.util.ExceptionUtils;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
/** File metadata and the current immutable manifest pointer commit together. */
@Service @Slf4j
public class ManifestService {
 private static final int MAX_MANIFEST_BYTES=32*1024*1024;
 private final MinioService minio;private final JdbcTemplate jdbc;private final ObjectMapper mapper;
 public ManifestService(MinioService minio,JdbcTemplate jdbc,ObjectMapper mapper){this.minio=minio;this.jdbc=jdbc;this.mapper=mapper;}
 private String legacyKey(UUID repo,UUID commit){return "manifest/artifacts/"+repo+"/commits/"+commit+"/commit_manifest.json";}
 private Map<String,Object> row(UUID repo,UUID commit,boolean lock){
  var rows=jdbc.queryForList("SELECT commit_status,manifest_object_key,manifest_sha256 FROM artifact_commit WHERE repo_id=? AND commit_id=?"+(lock?" FOR UPDATE":""),repo,commit);
  if(rows.size()!=1)throw ExceptionUtils.notFound("commit not found");return rows.get(0);
 }
 private void draft(UUID repo,UUID commit){
  if(!"DRAFT".equals(row(repo,commit,true).get("commit_status")))throw ExceptionUtils.conflict("only a DRAFT manifest can change");
 }
 @Transactional(rollbackFor=Exception.class)
 public CommitManifest initDraft(UUID repo,UUID commit,UUID parent)throws Exception{
  draft(repo,commit);
  var now=OffsetDateTime.now(Clock.systemUTC());
  var manifest=CommitManifest.builder().artifactId(repo).commitId(commit).version(1).createdAt(now).updatedAt(now).build();
  var files=new ArrayList<CommitManifest.File>();
  if(parent!=null)for(var file:getManifest(repo,parent).getFiles())files.add(new CommitManifest.File(file.getPath(),file.getFileId()));
  manifest.setFiles(files);writeRevision(repo,commit,manifest);return manifest;
 }
 public CommitManifest getManifest(UUID repo,UUID commit)throws Exception{
  var scope=row(repo,commit,false);String key=Objects.toString(scope.get("manifest_object_key"),"");
  String expected=Objects.toString(scope.get("manifest_sha256"),"");
  if(key.isBlank())key=legacyKey(repo,commit);
  else if(!key.matches(java.util.regex.Pattern.quote("manifest/artifacts/"+repo+"/commits/"+commit+"/revisions/")+"[a-f0-9-]{36}\\.json"))
   throw ExceptionUtils.conflict("manifest pointer is invalid");
  try(InputStream input=minio.getObject(key)){
   byte[] bytes=input.readNBytes(MAX_MANIFEST_BYTES+1);
   if(bytes.length>MAX_MANIFEST_BYTES)throw ExceptionUtils.conflict("manifest exceeds supported size");
   String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
   if(!expected.isBlank()&&!expected.equals(actual))throw ExceptionUtils.conflict("manifest content identity changed");
   CommitManifest manifest=mapper.readValue(bytes,CommitManifest.class);
   if(!repo.equals(manifest.getArtifactId())||!commit.equals(manifest.getCommitId()))throw ExceptionUtils.conflict("manifest snapshot identity mismatch");
   if(manifest.getFiles()==null)manifest.setFiles(new ArrayList<>());
   Set<String> paths=new HashSet<>();
   for(var file:manifest.getFiles())if(file.getPath()==null||file.getFileId()==null||!paths.add(file.getPath()))throw ExceptionUtils.conflict("manifest file identity is invalid");
   return manifest;
  }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw ExceptionUtils.conflict("manifest corrupted");
  }catch(ErrorResponseException error){
   String code=error.errorResponse()==null?"":error.errorResponse().code();
   if(Set.of("NoSuchKey","NoSuchObject").contains(code))throw ExceptionUtils.conflict("manifest missing");
   throw error;
  }
 }
 @Transactional(rollbackFor=Exception.class)
 public void addOrReplaceFile(UUID repo,UUID commit,String path,UUID fileId)throws Exception{
  draft(repo,commit);var manifest=getManifest(repo,commit);boolean found=false;
  for(var file:manifest.getFiles())if(path.equals(file.getPath())){file.setFileId(fileId);found=true;break;}
  if(!found)manifest.getFiles().add(new CommitManifest.File(path,fileId));
  touch(manifest);writeRevision(repo,commit,manifest);
 }
 @Transactional(rollbackFor=Exception.class)
 public UUID removeFileEntry(UUID repo,UUID commit,String path)throws Exception{
  draft(repo,commit);var manifest=getManifest(repo,commit);UUID removed=null;
  for(var iterator=manifest.getFiles().iterator();iterator.hasNext();){var file=iterator.next();if(path.equals(file.getPath())){removed=file.getFileId();iterator.remove();break;}}
  if(removed!=null){touch(manifest);writeRevision(repo,commit,manifest);}return removed;
 }
 private void touch(CommitManifest manifest){manifest.setVersion(manifest.getVersion()+1);manifest.setUpdatedAt(OffsetDateTime.now(Clock.systemUTC()));}
 private void writeRevision(UUID repo,UUID commit,CommitManifest manifest)throws Exception{
  if(!TransactionSynchronizationManager.isSynchronizationActive())throw new IllegalStateException("MANIFEST_TRANSACTION_REQUIRED");
  String key="manifest/artifacts/"+repo+"/commits/"+commit+"/revisions/"+UUID.randomUUID()+".json";
  byte[] bytes=mapper.writeValueAsBytes(manifest);
  if(bytes.length>MAX_MANIFEST_BYTES)throw ExceptionUtils.conflict("manifest exceeds supported size");
  // Register before the write: a storage response failure can follow a successful PUT.
  TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
   @Override public void afterCompletion(int status){
    if(status==STATUS_ROLLED_BACK){
     try{if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM artifact_commit WHERE manifest_object_key=?)",Boolean.class,key)))minio.removeObject(key);}
     catch(Exception cleanup){log.warn("Unreferenced manifest revision requires cleanup: keyHash={}",Integer.toHexString(key.hashCode()));}
    }else if(status==STATUS_UNKNOWN)log.warn("Manifest transaction outcome unknown; revision retained: keyHash={}",Integer.toHexString(key.hashCode()));
   }
  });
  minio.putObject(key,new ByteArrayInputStream(bytes),bytes.length,"application/json");
  String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  if(jdbc.update("UPDATE artifact_commit SET manifest_object_key=?,manifest_sha256=? WHERE repo_id=? AND commit_id=? AND commit_status='DRAFT'",key,sha,repo,commit)!=1)
   throw ExceptionUtils.conflict("manifest snapshot is no longer writable");
 }
}
