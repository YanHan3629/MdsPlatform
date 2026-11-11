package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.port.SearchServiceClient;
import com.fwdrobo.sirius.security.IndexCacheScopeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Immutable publication with live original authority before seal and final activation. */
@Slf4j
@Service
public class MmIndexPublicationService {
 private final SearchServiceClient search;private final JdbcTemplate jdbc;private final TransactionTemplate tx;
 private final DatasetAccessService access;private final IndexCacheScopeService cache;
 private final Set<UUID> inFlightPreloads=ConcurrentHashMap.newKeySet();
 public MmIndexPublicationService(SearchServiceClient search,JdbcTemplate jdbc,PlatformTransactionManager manager,
    DatasetAccessService access,IndexCacheScopeService cache){
  this.search=search;this.jdbc=jdbc;this.access=access;this.cache=cache;
  this.tx=new TransactionTemplate(manager);this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
 }
 @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
 public void publish(MmIndexPreparedEvent event){preloadAndPublish(event);}
 private void preloadAndPublish(MmIndexPreparedEvent event){
  // The callback and scheduled recovery share this path in the backend process.
  if(!inFlightPreloads.add(event.indexVersionId()))return;
  try{
   IndexCacheScopeService.Ticket ticket=tx.execute(status->{
    cache.fence();cache.requireLiveRequest();
    var rows=jdbc.queryForList("SELECT index_status FROM mm_index_version WHERE dataset_id=? AND dataset_version_id=? AND index_version_id=? FOR UPDATE",
      event.datasetId(),event.datasetVersionId(),event.indexVersionId());
    if(rows.size()!=1||!"PREWARMING".equals(rows.get(0).get("index_status")))return null;
    cache.preparedOrigin(event.datasetId(),event.datasetVersionId(),event.indexVersionId());
    sealIndexCommit(event.indexVersionId());
    return cache.forPreparedIndex(event.datasetId(),event.datasetVersionId(),event.indexVersionId());
   });
   if(ticket==null)return;
   // No metadata/authority lock is held across downloads and native index loading.
   search.preloadPrepared(ticket);
   tx.executeWithoutResult(status->{
    cache.fence();cache.revalidate(ticket);
    var rows=jdbc.queryForList("SELECT index_status FROM mm_index_version WHERE index_version_id=? FOR UPDATE",event.indexVersionId());
    if(rows.size()!=1||!"PREWARMING".equals(rows.get(0).get("index_status")))return;
    jdbc.update("UPDATE mm_index_registration SET registration_status='READY',ready_at=now() WHERE index_version_id=?",event.indexVersionId());
    jdbc.update("UPDATE mm_index_version SET index_status='READY',ready_at=now(),error_message=NULL WHERE index_version_id=? AND index_status='PREWARMING'",event.indexVersionId());
    String activate=jdbc.queryForObject("SELECT COALESCE(meta->>'activateOnReady','true') FROM mm_index_version WHERE index_version_id=?",String.class,event.indexVersionId());
    if("false".equals(activate)){jdbc.update("UPDATE mm_dataset_version SET version_status='READY' WHERE version_id=?",event.datasetVersionId());return;}
    int activated=jdbc.update("""
     UPDATE mm_dataset_version v SET active_index_version_id=?,version_status='READY'
     WHERE v.version_id=? AND NOT EXISTS (
      SELECT 1 FROM mm_index_version newer JOIN mm_index_version current
       ON current.index_version_id=? AND newer.dataset_version_id=current.dataset_version_id
      WHERE newer.created_at>current.created_at AND newer.index_status NOT IN('FAILED','DEPRECATED'))
     """,event.indexVersionId(),event.datasetVersionId(),event.indexVersionId());
    if(activated==0){
     jdbc.update("UPDATE mm_index_registration SET registration_status='DEPRECATED' WHERE index_version_id=?",event.indexVersionId());
     jdbc.update("UPDATE mm_index_version SET index_status='DEPRECATED' WHERE index_version_id=?",event.indexVersionId());
    }
   });
  }catch(Exception error){
   tx.executeWithoutResult(status->{
    int changed=jdbc.update("UPDATE mm_index_version SET index_status='FAILED',error_message='INDEX_PRELOAD_AUTHORITY_OR_LOADING_FAILED' WHERE index_version_id=? AND index_status='PREWARMING'",event.indexVersionId());
    if(changed==1){
     jdbc.update("UPDATE mm_index_registration SET registration_status='FAILED' WHERE index_version_id=?",event.indexVersionId());
     jdbc.update("UPDATE mm_dataset_version SET version_status=CASE WHEN active_index_version_id IS NULL THEN 'FAILED' ELSE 'READY' END WHERE version_id=?",event.datasetVersionId());
    }
   });
   log.error("index preload/publication failed; previous active index retained: {}",event.indexVersionId(),error);
  }finally{
   inFlightPreloads.remove(event.indexVersionId());
  }
 }
 private Map<String,Object> result(UUID d,UUID v,UUID i,UUID previous,boolean changed){
  var out=new LinkedHashMap<String,Object>();out.put("datasetId",d);out.put("versionId",v);out.put("activeIndexVersionId",i);
  out.put("previousActiveIndexVersionId",previous);out.put("activated",changed);return out;
 }
 private void ready(UUID d,UUID v,UUID i){
  var rows=jdbc.queryForList("""
   SELECT i.index_version_id FROM mm_index_version i JOIN mm_dataset_version v ON v.version_id=i.dataset_version_id
   JOIN mm_dataset d ON d.dataset_id=i.dataset_id
   JOIN artifact_commit raw ON raw.repo_id=d.raw_repo_id AND raw.commit_id=v.raw_commit_id AND raw.commit_status='PUBLISHED'
   JOIN artifact_commit idx ON idx.repo_id=i.index_repo_id AND idx.commit_id=i.index_commit_id AND idx.commit_status='PUBLISHED'
   WHERE i.dataset_id=? AND i.dataset_version_id=? AND i.index_version_id=? AND i.index_status='READY'
    AND NOT EXISTS(SELECT 1 FROM mm_index_registration r WHERE r.index_version_id=i.index_version_id AND r.registration_status<>'READY')
   """,d,v,i);
  if(rows.size()!=1)throw com.fwdrobo.sirius.util.ExceptionUtils.conflict("候选索引或冻结提交尚未就绪");
 }
 private UUID active(UUID d,UUID v){
  var rows=jdbc.queryForList("SELECT active_index_version_id FROM mm_dataset_version WHERE dataset_id=? AND version_id=? FOR UPDATE",d,v);
  if(rows.size()!=1)throw com.fwdrobo.sirius.util.ExceptionUtils.notFound("数据版本不存在");
  return (UUID)rows.get(0).get("active_index_version_id");
 }
 /** Activation has its own operation and parent session; it never borrows the original builder. */
 public Map<String,Object> activate(UUID d,UUID v,UUID i,UUID expectedActive){
  try(var fixed=access.fixedOperation("dataset.index.activate","VIEW_BUILD")){
   UUID previous=tx.execute(status->{
    cache.fence();cache.requireLiveRequest();access.requireDatasetWrite(d);UUID current=active(d,v);ready(d,v,i);
    if(!i.equals(current)&&!Objects.equals(current,expectedActive))throw com.fwdrobo.sirius.util.ExceptionUtils.conflict("活动索引已变化，请重新核对后切换");
    return current;
   });
   if(i.equals(previous))return result(d,v,i,previous,false);
   var ticket=cache.forActivation(d,v,i);
   search.preload(d,v,i,ticket.credential().authorization(),ticket.credential().authorityKind());
   return tx.execute(status->{
    cache.fence();cache.requireLiveRequest();cache.revalidate(ticket);access.requireDatasetWrite(d);UUID current=active(d,v);ready(d,v,i);
    if(i.equals(current))return result(d,v,i,current,false);
    if(!Objects.equals(current,previous))throw com.fwdrobo.sirius.util.ExceptionUtils.conflict("活动索引已变化，请重新核对后切换");
    jdbc.update("UPDATE mm_dataset_version SET active_index_version_id=?,version_status='READY',updated_at=now() WHERE dataset_id=? AND version_id=?",i,d,v);
    jdbc.update("""
     UPDATE mm_index_version SET meta=jsonb_set(COALESCE(meta,'{}'::jsonb),'{activationHistory}',
      COALESCE(meta->'activationHistory','[]'::jsonb)||jsonb_build_array(jsonb_build_object('activatedAt',now(),
       'previousIndexVersionId',?::uuid,'actorUserId',?::uuid))) WHERE index_version_id=?
     """,previous,com.fwdrobo.sirius.util.SecurityUtils.getUserId(),i);
    return result(d,v,i,previous,true);
   });
  }
 }
 private void sealIndexCommit(UUID index){
  var row=jdbc.queryForMap("SELECT index_repo_id,index_commit_id FROM mm_index_version WHERE index_version_id=?",index);
  UUID repo=(UUID)row.get("index_repo_id"),commit=(UUID)row.get("index_commit_id");
  int sealed=jdbc.update("UPDATE artifact_commit SET commit_status='PUBLISHED',published_at=now(),comment=? WHERE repo_id=? AND commit_id=? AND commit_status='DRAFT'","verified immutable index snapshot "+index,repo,commit);
  if(sealed==0&&!"PUBLISHED".equals(jdbc.queryForObject("SELECT commit_status FROM artifact_commit WHERE repo_id=? AND commit_id=?",String.class,repo,commit)))
   throw new IllegalStateException("INDEX_COMMIT_NOT_PUBLISHABLE");
 }
 @Scheduled(initialDelayString="${mm.index-preload.prepared-initial-delay-ms:20000}",fixedDelayString="${mm.index-preload.prepared-refresh-delay-ms:60000}")
 public void recoverPreparedIndexes(){
  for(var row:jdbc.queryForList("SELECT dataset_id,dataset_version_id,index_version_id FROM mm_index_version WHERE index_status='PREWARMING' ORDER BY created_at LIMIT 10"))
   preloadAndPublish(new MmIndexPreparedEvent((UUID)row.get("dataset_id"),(UUID)row.get("dataset_version_id"),(UUID)row.get("index_version_id")));
 }
 @Scheduled(initialDelayString="${mm.index-preload.initial-delay-ms:15000}",fixedDelayString="${mm.index-preload.refresh-delay-ms:300000}")
 public void restoreActiveIndexes(){
  int authorizedAttempts=0;
  for(var row:jdbc.queryForList("""
   SELECT d.dataset_id,v.version_id,v.active_index_version_id AS index_version_id
   FROM mm_dataset_version v JOIN mm_dataset d ON d.dataset_id=v.dataset_id
   JOIN mm_index_version i ON i.index_version_id=v.active_index_version_id WHERE i.index_status='READY' ORDER BY i.ready_at DESC NULLS LAST,i.created_at DESC
   """)){
   if(authorizedAttempts>=2)break;
   try{
    UUID d=(UUID)row.get("dataset_id"),v=(UUID)row.get("version_id"),i=(UUID)row.get("index_version_id");
    var credential=cache.forServiceRecovery(d,v,i);
    authorizedAttempts++;
    search.preload(d,v,i,credential.authorization(),credential.authorityKind());
   }catch(Exception error){log.warn("explicit service cache recovery not available: {}",row.get("index_version_id"),error);}
  }
 }
}
