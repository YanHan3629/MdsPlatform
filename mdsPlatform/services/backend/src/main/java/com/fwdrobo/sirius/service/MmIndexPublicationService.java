package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.port.SearchServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

/** Publish an immutable snapshot only after checksum validation and preload. */
@Slf4j
@Service
public class MmIndexPublicationService {
    private final SearchServiceClient search;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DatasetAccessService access;

    public MmIndexPublicationService(SearchServiceClient search, JdbcTemplate jdbc,
                                     PlatformTransactionManager transactionManager,DatasetAccessService access) {
        this.search = search;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.access=access;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(MmIndexPreparedEvent event) {
        preloadAndPublish(event);
    }

    private void preloadAndPublish(MmIndexPreparedEvent event) {
        try {
            sealIndexCommit(event.indexVersionId());
            search.preload(event.datasetId(), event.datasetVersionId(), event.indexVersionId());
            tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE mm_index_registration SET registration_status='READY',ready_at=now() WHERE index_version_id=?",
                        event.indexVersionId());
                jdbc.update("UPDATE mm_index_version SET index_status='READY',ready_at=now(),error_message=NULL WHERE index_version_id=? AND index_status='PREWARMING'",
                        event.indexVersionId());
                String activate=jdbc.queryForObject("SELECT COALESCE(meta->>'activateOnReady','true') FROM mm_index_version WHERE index_version_id=?",String.class,event.indexVersionId());
                if("false".equals(activate)){
                    jdbc.update("UPDATE mm_dataset_version SET version_status='READY' WHERE version_id=?",event.datasetVersionId());
                    return;
                }
                int activated = jdbc.update("""
                        UPDATE mm_dataset_version v SET active_index_version_id=?,version_status='READY'
                        WHERE v.version_id=? AND NOT EXISTS (
                          SELECT 1 FROM mm_index_version newer JOIN mm_index_version current
                            ON current.index_version_id=? AND newer.dataset_version_id=current.dataset_version_id
                          WHERE newer.created_at > current.created_at
                            AND newer.index_status NOT IN ('FAILED','DEPRECATED'))
                        """, event.indexVersionId(), event.datasetVersionId(), event.indexVersionId());
                if (activated == 0) {
                    jdbc.update("UPDATE mm_index_registration SET registration_status='DEPRECATED' WHERE index_version_id=?", event.indexVersionId());
                    jdbc.update("UPDATE mm_index_version SET index_status='DEPRECATED' WHERE index_version_id=?", event.indexVersionId());
                }
            });
        } catch (Exception exception) {
            String message = "preload failed; previous active index retained: " + exception.getMessage();
            tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE mm_index_registration SET registration_status='FAILED' WHERE index_version_id=?",
                        event.indexVersionId());
                jdbc.update("UPDATE mm_index_version SET index_status='FAILED',error_message=? WHERE index_version_id=?",
                        message.substring(0, Math.min(message.length(), 4000)), event.indexVersionId());
                jdbc.update("""
                        UPDATE mm_dataset_version SET version_status=CASE WHEN active_index_version_id IS NULL THEN 'FAILED' ELSE 'READY' END
                        WHERE version_id=?
                        """, event.datasetVersionId());
            });
            log.error("index preload/publication failed: indexVersionId={}", event.indexVersionId(), exception);
        }
    }

    /** Explicit compare-and-set promotion; old READY scopes and contracts stay frozen. */
    public Map<String,Object> activate(java.util.UUID datasetId,java.util.UUID versionId,java.util.UUID indexVersionId,java.util.UUID expectedActive){
        access.requireDatasetWrite(datasetId);
        return tx.execute(status->{
            var versions=jdbc.queryForList("SELECT active_index_version_id FROM mm_dataset_version WHERE version_id=? AND dataset_id=? FOR UPDATE",versionId,datasetId);
            if(versions.size()!=1)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"数据版本不存在");
            var previous=(java.util.UUID)versions.get(0).get("active_index_version_id");
            if(!indexVersionId.equals(previous)&&!java.util.Objects.equals(previous,expectedActive))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"活动索引已变化，请重新核对后切换");
            var ready=jdbc.queryForList("""
                SELECT i.index_version_id FROM mm_index_version i
                JOIN mm_dataset_version v ON v.version_id=i.dataset_version_id
                JOIN mm_dataset d ON d.dataset_id=i.dataset_id
                JOIN artifact_commit raw ON raw.repo_id=d.raw_repo_id AND raw.commit_id=v.raw_commit_id AND raw.commit_status='PUBLISHED'
                JOIN artifact_commit idx ON idx.repo_id=i.index_repo_id AND idx.commit_id=i.index_commit_id AND idx.commit_status='PUBLISHED'
                WHERE i.dataset_id=? AND i.dataset_version_id=? AND i.index_version_id=? AND i.index_status='READY'
                  AND NOT EXISTS(SELECT 1 FROM mm_index_registration r WHERE r.index_version_id=i.index_version_id AND r.registration_status<>'READY')
                """,datasetId,versionId,indexVersionId);
            if(ready.size()!=1)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"候选索引或冻结提交尚未就绪");
            if(!indexVersionId.equals(previous)){
                search.preload(datasetId,versionId,indexVersionId);
                jdbc.update("UPDATE mm_dataset_version SET active_index_version_id=?,version_status='READY',updated_at=now() WHERE dataset_id=? AND version_id=?",indexVersionId,datasetId,versionId);
                jdbc.update("""
                    UPDATE mm_index_version SET meta=jsonb_set(COALESCE(meta,'{}'::jsonb),'{activationHistory}',
                      COALESCE(meta->'activationHistory','[]'::jsonb)||jsonb_build_array(jsonb_build_object('activatedAt',now(),
                        'previousIndexVersionId',?::uuid,'actorUserId',?::uuid))) WHERE index_version_id=?
                    """,previous,com.fwdrobo.sirius.util.SecurityUtils.getUserId(),indexVersionId);
            }
            var result=new java.util.LinkedHashMap<String,Object>();result.put("datasetId",datasetId);result.put("versionId",versionId);
            result.put("activeIndexVersionId",indexVersionId);result.put("previousActiveIndexVersionId",previous);result.put("activated",!indexVersionId.equals(previous));return result;
        });
    }

    /** Seal verified output before search can publish a reference to it. */
    private void sealIndexCommit(java.util.UUID indexVersionId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT index_repo_id,index_commit_id FROM mm_index_version WHERE index_version_id=?
                """, indexVersionId);
        java.util.UUID repoId = (java.util.UUID) row.get("index_repo_id");
        java.util.UUID commitId = (java.util.UUID) row.get("index_commit_id");
        int sealed = jdbc.update("""
                UPDATE artifact_commit SET commit_status='PUBLISHED',published_at=now(),
                  comment=? WHERE repo_id=? AND commit_id=? AND commit_status='DRAFT'
                """, "verified immutable index snapshot " + indexVersionId, repoId, commitId);
        if (sealed == 0) {
            String status = jdbc.query("""
                    SELECT commit_status FROM artifact_commit WHERE repo_id=? AND commit_id=?
                    """, resultSet -> resultSet.next() ? resultSet.getString(1) : null, repoId, commitId);
            if (!"PUBLISHED".equals(status)) {
                throw new IllegalStateException("index artifact commit is not publishable: " + status);
            }
        }
    }

    /** Recover a callback committed just before the backend process stopped. */
    @Scheduled(initialDelayString = "${mm.index-preload.prepared-initial-delay-ms:20000}", fixedDelayString = "${mm.index-preload.prepared-refresh-delay-ms:60000}")
    public void recoverPreparedIndexes() {
        for (var row : jdbc.queryForList("""
                SELECT dataset_id,dataset_version_id,index_version_id FROM mm_index_version
                WHERE index_status='PREWARMING' ORDER BY created_at LIMIT 10
                """)) {
            preloadAndPublish(new MmIndexPreparedEvent((java.util.UUID) row.get("dataset_id"),
                    (java.util.UUID) row.get("dataset_version_id"), (java.util.UUID) row.get("index_version_id")));
        }
    }

    /** Restore the bounded search cache after either service restarts. */
    @Scheduled(initialDelayString = "${mm.index-preload.initial-delay-ms:15000}", fixedDelayString = "${mm.index-preload.refresh-delay-ms:300000}")
    public void restoreActiveIndexes() {
        for (var row : jdbc.queryForList("""
                SELECT d.dataset_id,v.version_id,v.active_index_version_id AS index_version_id
                FROM mm_dataset_version v JOIN mm_dataset d ON d.dataset_id=v.dataset_id
                JOIN mm_index_version i ON i.index_version_id=v.active_index_version_id
                WHERE i.index_status='READY' ORDER BY i.ready_at DESC LIMIT 2
                """)) {
            try {
                sealIndexCommit((java.util.UUID) row.get("index_version_id"));
                search.preload((java.util.UUID) row.get("dataset_id"),
                        (java.util.UUID) row.get("version_id"),
                        (java.util.UUID) row.get("index_version_id"));
            } catch (Exception exception) {
                log.warn("active index recovery preload failed: {}", row.get("index_version_id"), exception);
            }
        }
    }
}
