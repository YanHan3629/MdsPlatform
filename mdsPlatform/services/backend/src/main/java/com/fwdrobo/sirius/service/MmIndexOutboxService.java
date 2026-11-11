package com.fwdrobo.sirius.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.security.IdentitySessionService;
import com.fwdrobo.sirius.util.ExceptionUtils;
import io.jsonwebtoken.Claims;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Durable transaction event and idempotent indexing task creation. */
@Service
public class MmIndexOutboxService {
    public static final String PARSER_VERSION = "parser-registry-1.1";
    public static final String BUILDER_VERSION = "professional-indexes-1.14";
    public static final String CONFIG_VERSION = "2026-09-13.1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final IdentitySessionService sessions;

    public MmIndexOutboxService(JdbcTemplate jdbc, ObjectMapper objectMapper, IdentitySessionService sessions) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.sessions = sessions;
    }

    public static String idempotencyKey(UUID orgId, UUID versionId, UUID rawCommitId) {
        try {
            String value = String.join(":", orgId.toString(), versionId.toString(), rawCommitId.toString(),
                    PARSER_VERSION, BUILDER_VERSION, CONFIG_VERSION);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot create ingestion idempotency key", exception);
        }
    }

    @Transactional
    public UUID enqueueSnapshot(UUID orgId, UUID datasetId, UUID versionId, UUID rawCommitId, UUID createdBy) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request)
                || !(request.getRequest().getAttribute("ds.accessClaims") instanceof Claims claims)) {
            throw ExceptionUtils.unauthorized("INDEX_ORIGIN_SESSION_REQUIRED");
        }
        var actor = sessions.authenticateAccess(claims);
        if (!actor.userId().equals(createdBy) || !actor.orgId().equals(orgId)) {
            throw ExceptionUtils.unauthorized("INDEX_ORIGIN_IDENTITY_MISMATCH");
        }
        UUID sessionId = UUID.fromString(claims.get("sid", String.class));
        String rawConfig = jdbc.queryForObject("SELECT COALESCE(meta->'indexConfig','{}'::jsonb)::text FROM mm_dataset_version WHERE version_id=?",String.class,versionId);
        Map<String,Object> config;
        boolean activateOnReady;
        try { config = com.fwdrobo.sirius.util.IndexProcessingConfig.validate(objectMapper.readValue(rawConfig, Map.class)); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("invalid index config",e); }
        try {
            var policy=objectMapper.readTree(jdbc.queryForObject("SELECT COALESCE(meta->'activateOnReady','true'::jsonb)::text FROM mm_dataset_version WHERE version_id=?",String.class,versionId));
            if(!policy.isBoolean())throw new IllegalArgumentException("invalid persisted index activation policy");
            activateOnReady=policy.asBoolean();
        } catch(java.io.IOException invalid){throw new IllegalArgumentException("invalid persisted index activation policy",invalid);}
        String configFingerprint=com.fwdrobo.sirius.util.IndexProcessingConfig.fingerprint(config);
        if(!activateOnReady) {
            // The ingestion key column is VARCHAR(128). Domain-separate held
            // builds inside the digest while preserving existing normal keys.
            try { configFingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((configFingerprint+":held").getBytes(StandardCharsets.UTF_8))); }
            catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        }
        String key = idempotencyKey(orgId, versionId, rawCommitId) + configFingerprint;
        UUID eventId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "datasetId", datasetId, "datasetVersionId", versionId, "rawCommitId", rawCommitId,
                    "parserVersion", PARSER_VERSION, "builderVersion", BUILDER_VERSION,
                    "configVersion", CONFIG_VERSION, "indexConfig",config,"activateOnReady",activateOnReady));
            jdbc.update("""
                    INSERT INTO mm_outbox_event(event_id,org_id,aggregate_type,aggregate_id,event_type,idempotency_key,payload)
                    VALUES (?,?,?,?,?,?,?::jsonb)
                    ON CONFLICT (org_id,idempotency_key) DO NOTHING
                    """, eventId, orgId, "DATASET_VERSION", versionId, "DATASET_SNAPSHOT_READY", key, payload);
            jdbc.update("""
                    INSERT INTO mm_ingest_task(task_id,org_id,dataset_id,dataset_version_id,raw_commit_id,idempotency_key,created_by,origin_session_id,meta)
                    VALUES (?,?,?,?,?,?,?,?,?::jsonb)
                    ON CONFLICT (org_id,idempotency_key) DO NOTHING
                    """, taskId, orgId, datasetId, versionId, rawCommitId, key, createdBy, sessionId, payload);
            // An explicit request can recover failed work or pre-upgrade queued work
            // without transferring a live task to another session.
            jdbc.update("""
                    UPDATE mm_ingest_task SET created_by=?,origin_session_id=?,task_status='RETRY',stage='REQUEUED',attempt_count=0,
                      next_attempt_at=now(),lease_until=NULL,index_version_id=NULL,error_message=NULL,completed_at=NULL
                    WHERE org_id=? AND idempotency_key=? AND
                      (task_status='FAILED' OR (task_status IN ('QUEUED','RETRY') AND origin_session_id IS NULL))
                    """, createdBy, sessionId, orgId, key);
            UUID persisted = jdbc.queryForObject(
                    "SELECT task_id FROM mm_ingest_task WHERE org_id=? AND idempotency_key=?", UUID.class, orgId, key);
            return persisted == null ? taskId : persisted;
        } catch (Exception exception) {
            throw new IllegalStateException("cannot enqueue dataset indexing", exception);
        }
    }
}
