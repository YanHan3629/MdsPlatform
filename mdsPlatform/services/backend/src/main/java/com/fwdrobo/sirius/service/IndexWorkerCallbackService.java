package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.security.IndexJobAuthority;
import com.fwdrobo.sirius.dto.mm.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Callback receipts make retries idempotent and prevent changing frozen output coordinates. */
@Service
public class IndexWorkerCallbackService {
 private final IndexJobAuthority authority; private final MmIndexService indexes; private final JdbcTemplate jdbc;
 private final ObjectMapper canonical=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
 public IndexWorkerCallbackService(IndexJobAuthority authority,MmIndexService indexes,JdbcTemplate jdbc){this.authority=authority;this.indexes=indexes;this.jdbc=jdbc;}
 private static void fail(HttpStatus status,String code){throw new ResponseStatusException(status,code);}
 private String digest(Object payload){
  try{
   byte[] encoded=canonical.writeValueAsBytes(payload);if(encoded.length>1048576)fail(HttpStatus.BAD_REQUEST,"INDEX_CALLBACK_TOO_LARGE");
   return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
  }catch(ResponseStatusException error){throw error;}catch(Exception error){throw new IllegalStateException("INDEX_CALLBACK_ENCODING_FAILED",error);}
 }
 private boolean receipt(IndexJobAuthority.Origin o,String kind,Object payload){
  String hash=digest(payload);
  var old=jdbc.queryForList("SELECT callback_kind,payload_sha256 FROM ds_index_callback_receipt WHERE run_id=?",o.run());
  if(!old.isEmpty()){
   if(!kind.equals(old.get(0).get("callback_kind"))||!hash.equals(old.get(0).get("payload_sha256")))fail(HttpStatus.CONFLICT,"INDEX_CALLBACK_ALREADY_RECORDED_WITH_DIFFERENT_RESULT");
   return false;
  }
  String state=jdbc.queryForObject("SELECT index_status FROM mm_index_version WHERE index_version_id=? FOR UPDATE",String.class,o.coordinates().index());
  if(!Set.of("QUEUED","BUILDING").contains(state))fail(HttpStatus.CONFLICT,"INDEX_CALLBACK_STATE_NOT_ACCEPTING");
  jdbc.update("INSERT INTO ds_index_callback_receipt(run_id,callback_kind,payload_sha256) VALUES(?,?,?)",o.run(),kind,hash);
  return true;
 }
 private void audit(IndexJobAuthority.Origin o,String kind){
  jdbc.update("""
   INSERT INTO ds_authorization_audit(actor_user_id,actor_org_id,operation_code,target_kind,target_id,outcome,request_id,scope,reason_code)
   VALUES(?,?,'dataset.index.build','INDEX_VERSION',?,'SUCCESS',?,
    jsonb_build_object('runId',?::text,'jobId',?::text,'datasetId',?::text,'versionId',?::text,'grantId',?::text,
     'grantVersion',?::bigint,'callbackKind',?::text),'PARENT_BOUND_INDEX_CALLBACK_ACCEPTED')
   """,o.user(),o.org(),o.coordinates().index().toString(),UUID.randomUUID().toString(),o.run().toString(),o.job().toString(),
    o.coordinates().dataset().toString(),o.coordinates().version().toString(),o.grant().grantId().toString(),o.grant().version(),kind);
 }
 private static void path(String value,boolean required){
  if(value==null){if(required)fail(HttpStatus.BAD_REQUEST,"INDEX_CALLBACK_PATH_REQUIRED");return;}
  String normalized=value.replace('\\','/');
  if(!normalized.startsWith("/")||normalized.length()>2048||normalized.indexOf('\0')>=0||
     Arrays.asList(normalized.split("/",-1)).contains("..")||normalized.contains("://")||normalized.contains("?")||normalized.contains("#"))
   fail(HttpStatus.BAD_REQUEST,"INDEX_CALLBACK_PATH_INVALID");
 }
 @Transactional
 public MmIndexVersionResp ready(UUID d,UUID v,UUID i,MmIndexReadyReq req){
  var o=authority.lockAndCurrent(d,v,i);
  if(!o.outputCommit().equals(req.indexCommitId()))fail(HttpStatus.FORBIDDEN,"INDEX_CALLBACK_OUTPUT_COMMIT_MISMATCH");
  path(req.manifestPath(),true);path(req.representationManifestPath(),false);path(req.imageIndexPath(),false);path(req.textIndexPath(),false);
  path(req.imageMetadataPath(),false);path(req.textMetadataPath(),false);path(req.unifiedIndexPath(),false);path(req.unifiedMetadataPath(),false);path(req.professionalManifestPath(),false);
  if(req.indexes()==null||req.indexes().isEmpty()||req.indexes().size()>64)fail(HttpStatus.BAD_REQUEST,"INDEX_CALLBACK_REGISTRATIONS_REQUIRED");
  if(!receipt(o,"READY",req))return indexes.getIndexStatusExact(i);
  try(var metadata=authority.enterCallbackMetadata(o)){
  var response=indexes.markReady(d,v,i,req.indexCommitId(),req.imageIndexPath(),req.textIndexPath(),req.imageMetadataPath(),req.textMetadataPath(),
   req.unifiedIndexPath(),req.unifiedMetadataPath(),req.representationManifestPath(),req.manifestPath(),req.embeddingDim(),
   req.imageCount(),req.textCount(),req.unifiedCount(),req.indexes(),req.professionalManifestPath());
  audit(o,"READY");return response;
  }
 }
 @Transactional
 public MmIndexVersionResp failed(UUID d,UUID v,UUID i,MmIndexFailedReq req){
  var o=authority.lockAndCurrent(d,v,i);
  String message=req.errorMessage()==null?"INDEX_WORKER_FAILED":req.errorMessage();
  if(message.length()>4000)fail(HttpStatus.BAD_REQUEST,"INDEX_FAILURE_MESSAGE_TOO_LONG");
  if(!receipt(o,"FAILED",Map.of("errorMessage",message)))return indexes.getIndexStatusExact(i);
  // Diagnostic bodies are retained in the protected worker log; public errors use a fixed code.
  try(var metadata=authority.enterCallbackMetadata(o)){
  var response=indexes.markFailed(d,v,i,"INDEX_WORKER_FAILED");audit(o,"FAILED");return response;
  }
 }
}
