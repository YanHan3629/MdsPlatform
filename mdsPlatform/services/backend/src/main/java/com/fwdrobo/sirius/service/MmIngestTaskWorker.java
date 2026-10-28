package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.dto.mm.MmBuildIndexReq;
import com.fwdrobo.sirius.container.ContainerInspector;
import com.fwdrobo.sirius.docker.entity.DockerHostResolver;
import com.fwdrobo.sirius.security.JwtAuthFilter;
import com.fwdrobo.sirius.security.JwtService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CPU/GPU build launcher with one durable lease at a time.  Claiming uses
 * SKIP LOCKED, so multiple backend replicas do not run the same task.
 */
@Slf4j
@Service
public class MmIngestTaskWorker {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MmIndexService indexes;
    private final JwtService jwtService;
    private final ContainerInspector containerInspector;
    private final DockerHostResolver dockerHostResolver;

    public MmIngestTaskWorker(JdbcTemplate jdbc, TransactionTemplate tx, MmIndexService indexes,
                              JwtService jwtService, ContainerInspector containerInspector,
                              DockerHostResolver dockerHostResolver) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.indexes = indexes;
        this.jwtService = jwtService;
        this.containerInspector = containerInspector;
        this.dockerHostResolver = dockerHostResolver;
    }

    @Scheduled(initialDelayString = "${mm.ingest.initial-delay-ms:8000}", fixedDelayString = "${mm.ingest.poll-delay-ms:5000}")
    public void poll() {
        refreshMissedContainerEvents();
        reconcileWaiting();
        Map<String, Object> task = claimOne();
        if (task != null) process(task);
    }

    /** Reconcile all index runs, including explicit builds and recovered READY artifacts. */
    private void refreshMissedContainerEvents() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT DISTINCT r.run_id,r.container_id,r.docker_host
                FROM mm_index_version i
                JOIN job_run r ON r.run_id=i.build_run_id
                WHERE r.run_status IN ('QUEUED','RUNNING')
                  AND r.container_id IS NOT NULL
                  AND (r.last_seen_at IS NULL OR r.last_seen_at < now()-interval '20 seconds')
                ORDER BY r.run_id LIMIT 50
                """);
        for (Map<String, Object> row : rows) {
            try {
                String server = String.valueOf(row.get("docker_host"));
                containerInspector.refreshByRunId((UUID) row.get("run_id"),
                        String.valueOf(row.get("container_id")), dockerHostResolver.resolve(server));
            } catch (Exception exception) {
                log.warn("cannot refresh stale indexing container: runId={}", row.get("run_id"), exception);
            }
        }
    }

    private Map<String, Object> claimOne() {
        return tx.execute(status -> {
            Boolean schedulerLock = jdbc.queryForObject(
                    "SELECT pg_try_advisory_xact_lock(hashtext('mm_ingest_task_global'))", Boolean.class);
            if (!Boolean.TRUE.equals(schedulerLock)) return null;
            jdbc.update("""
                    UPDATE mm_ingest_task SET task_status='RETRY',stage='RECOVERED',lease_until=NULL,
                      next_attempt_at=now(),error_message='worker lease expired; recovered after restart'
                    WHERE task_status='PROCESSING' AND lease_until < now()
                    """);
            jdbc.update("""
                    UPDATE mm_ingest_task SET task_status='SUPERSEDED',stage='SUPERSEDED',completed_at=now(),
                      error_message='superseded by builder version ' || ?
                    WHERE task_status IN ('QUEUED','RETRY')
                      AND COALESCE(meta->>'builderVersion','') <> ?
                    """, MmIndexOutboxService.BUILDER_VERSION, MmIndexOutboxService.BUILDER_VERSION);
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT task_id,org_id,dataset_id,dataset_version_id,created_by,attempt_count,max_attempts,meta::text AS task_meta
                    FROM mm_ingest_task
                    WHERE task_status IN ('QUEUED','RETRY') AND next_attempt_at <= now()
                      AND NOT EXISTS (
                        SELECT 1 FROM mm_ingest_task active
                        WHERE active.task_status IN ('PROCESSING','WAITING_INDEX'))
                    ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1
                    """);
            if (rows.isEmpty()) return null;
            Map<String, Object> row = rows.get(0);
            jdbc.update("""
                    UPDATE mm_ingest_task SET task_status='PROCESSING',stage='STARTING',attempt_count=attempt_count+1,
                      lease_until=now()+interval '10 minutes',error_message=NULL WHERE task_id=?
                    """, row.get("task_id"));
            return row;
        });
    }

    private void process(Map<String, Object> task) {
        UUID taskId = (UUID) task.get("task_id");
        UUID orgId = (UUID) task.get("org_id");
        UUID userId = (UUID) task.get("created_by");
        try {
            WorkerCredential credential = issueWorkerCredential(orgId, userId);
            var principal = new JwtAuthFilter.UserPrincipal(
                    credential.userId(), credential.userName(), List.of("ROLE_SYSTEM"), orgId);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority("ROLE_SYSTEM"))));
            var taskMeta = new com.fasterxml.jackson.databind.ObjectMapper().readTree(String.valueOf(task.get("task_meta")));
            if(taskMeta.has("activateOnReady")&&!taskMeta.get("activateOnReady").isBoolean())throw new IllegalStateException("invalid persisted index activation policy");
            Map<String,Object> indexConfig = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(taskMeta.path("indexConfig"),Map.class);
            Map<String, Object> result = indexes.buildIndexForIngestion(
                    (UUID) task.get("dataset_id"), (UUID) task.get("dataset_version_id"),taskId,
                    new MmBuildIndexReq(MmIndexOutboxService.BUILDER_VERSION
                            .replace("professional-indexes-", "professional-multispace-"),
                            "MULTI_SPACE", false,indexConfig == null ? Map.of() : indexConfig,taskMeta.path("activateOnReady").asBoolean(true)), credential.token());
            jdbc.update("""
                    UPDATE mm_ingest_task SET task_status='WAITING_INDEX',stage='BUILDING',lease_until=NULL,
                      index_version_id=? WHERE task_id=?
                    """, result.get("indexVersionId"), taskId);
            jdbc.update("UPDATE mm_outbox_event SET event_status='PROCESSED',processed_at=now() WHERE org_id=? AND aggregate_id=? AND event_status='PENDING'",
                    orgId, task.get("dataset_version_id"));
        } catch (Exception exception) {
            int nextAttempt = ((Number) task.get("attempt_count")).intValue() + 1;
            int maxAttempts = ((Number) task.get("max_attempts")).intValue();
            String status = nextAttempt >= maxAttempts ? "FAILED" : "RETRY";
            jdbc.update("""
                    UPDATE mm_ingest_task SET task_status=?,stage='BUILD_START_FAILED',lease_until=NULL,
                      next_attempt_at=now()+(interval '30 seconds' * power(2,least(attempt_count,6))),error_message=? WHERE task_id=?
                    """, status, abbreviate(exception.getMessage()), taskId);
            log.warn("automatic index task failed: taskId={}", taskId, exception);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void reconcileWaiting() {
        jdbc.update("""
                UPDATE mm_index_version i SET index_status='FAILED',error_message=COALESCE(r.error_message,'builder container failed')
                FROM job_run r WHERE i.build_run_id=r.run_id AND i.index_status IN ('QUEUED','BUILDING')
                  AND r.run_status IN ('FAILED','CANCELED')
                """);
        jdbc.update("""
                UPDATE mm_ingest_task t SET task_status='READY',stage='READY',completed_at=now(),error_message=NULL
                FROM mm_index_version i WHERE t.index_version_id=i.index_version_id
                  AND t.task_status='WAITING_INDEX' AND i.index_status='READY'
                """);
        jdbc.update("""
                UPDATE mm_ingest_task t SET
                  task_status=CASE WHEN t.attempt_count < t.max_attempts THEN 'RETRY' ELSE 'FAILED' END,
                  stage=CASE WHEN t.attempt_count < t.max_attempts THEN 'INDEX_RETRY' ELSE 'INDEX_FAILED' END,
                  next_attempt_at=CASE WHEN t.attempt_count < t.max_attempts
                    THEN now()+(interval '30 seconds' * power(2,least(t.attempt_count,6))) ELSE t.next_attempt_at END,
                  completed_at=CASE WHEN t.attempt_count < t.max_attempts THEN NULL ELSE now() END,
                  index_version_id=CASE WHEN t.attempt_count < t.max_attempts THEN NULL ELSE t.index_version_id END,
                  error_message=i.error_message
                FROM mm_index_version i WHERE t.index_version_id=i.index_version_id
                  AND t.task_status='WAITING_INDEX' AND i.index_status='FAILED'
                """);
        jdbc.update("""
                UPDATE mm_dataset_version v SET version_status=
                  CASE WHEN v.active_index_version_id IS NULL THEN 'FAILED' ELSE 'READY' END
                WHERE v.version_status='INDEXING'
                  AND NOT EXISTS (
                    SELECT 1 FROM mm_index_version i
                    WHERE i.dataset_version_id=v.version_id
                      AND i.index_status IN ('QUEUED','BUILDING','PREWARMING'))
                """);
    }

    private String abbreviate(String value) {
        String text = value == null ? "unknown indexing failure" : value;
        return text.substring(0, Math.min(text.length(), 4000));
    }

    private WorkerCredential issueWorkerCredential(UUID orgId, UUID requestedUserId) {
        List<Map<String, Object>> rows;
        if (requestedUserId != null) {
            rows = jdbc.queryForList("""
                    SELECT user_id,user_name,token_version FROM fwd_user
                    WHERE org_id=? AND user_id=? AND status_code=1 LIMIT 1
                    """, orgId, requestedUserId);
        } else {
            rows = List.of();
        }
        if (rows.isEmpty()) {
            rows = jdbc.queryForList("""
                    SELECT user_id,user_name,token_version FROM fwd_user
                    WHERE org_id=? AND status_code=1 ORDER BY created_at,user_id LIMIT 1
                    """, orgId);
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("no active organization user available for scoped indexing job");
        }
        Map<String, Object> user = rows.get(0);
        Number version = (Number) user.get("token_version");
        String userName = String.valueOf(user.get("user_name"));
        String token = jwtService.generateAccessToken(userName, List.of("SYSTEM"),
                version == null ? 0 : version.intValue());
        return new WorkerCredential((UUID) user.get("user_id"), userName, token);
    }

    private record WorkerCredential(UUID userId, String userName, String token) {}
}
