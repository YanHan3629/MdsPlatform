package com.fwdrobo.sirius.controller;

import com.fwdrobo.sirius.entity.mm.MmDataset;
import com.fwdrobo.sirius.entity.mm.MmDatasetVersion;
import com.fwdrobo.sirius.service.MmDatasetService;
import com.fwdrobo.sirius.service.MmDatasetVersionService;
import com.fwdrobo.sirius.service.MmIndexOutboxService;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import com.fwdrobo.sirius.util.IndexProcessingConfig;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/mm/datasets/{datasetId}/versions/{versionId}/ingest-tasks")
public class MmIngestTaskController {
    private final JdbcTemplate jdbc;
    private final MmDatasetService datasets;
    private final MmDatasetVersionService versions;
    private final MmIndexOutboxService outbox;

    public MmIngestTaskController(JdbcTemplate jdbc,
                                  MmDatasetService datasets,
                                  MmDatasetVersionService versions,
                                  MmIndexOutboxService outbox) {
        this.jdbc = jdbc;
        this.datasets = datasets;
        this.versions = versions;
        this.outbox = outbox;
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable UUID datasetId, @PathVariable UUID versionId) {
        requireOwnedVersion(datasetId, versionId);
        return jdbc.queryForList("""
                SELECT task_id AS "taskId",dataset_id AS "datasetId",dataset_version_id AS "versionId",
                  index_version_id AS "indexVersionId",task_status AS status,stage,attempt_count AS "attemptCount",
                  max_attempts AS "maxAttempts",error_message AS error,created_at AS "createdAt",updated_at AS "updatedAt",
                  completed_at AS "completedAt"
                FROM mm_ingest_task WHERE org_id=? AND dataset_id=? AND dataset_version_id=? ORDER BY created_at DESC
                """, SecurityUtils.getUserOrgId(), datasetId, versionId);
    }

    @GetMapping("/mapping")
    public Object mapping(@PathVariable UUID datasetId, @PathVariable UUID versionId) {
        var version = requireOwnedVersion(datasetId,versionId);
        return version.getMeta() == null ? Map.of() : version.getMeta().path("indexConfig");
    }

    @PostMapping("/mapping")
    public Map<String,Object> mapping(@PathVariable UUID datasetId, @PathVariable UUID versionId,
                                      @RequestBody Map<String,Object> mapping) {
        requireOwnedVersion(datasetId,versionId);
        var config = IndexProcessingConfig.validate(mapping);
        // Only future tasks use this preference. Published raw/index snapshots are not edited.
        jdbc.update("UPDATE mm_dataset_version SET meta=jsonb_set(COALESCE(meta,'{}'::jsonb),'{indexConfig}',?::jsonb,true) WHERE version_id=?",
                    IndexProcessingConfig.json(config),versionId);
        return config;
    }

    /** Queue a recoverable, idempotent professional-index backfill for an existing snapshot. */
    @PostMapping("/backfill")
    public Map<String, Object> backfill(@PathVariable UUID datasetId, @PathVariable UUID versionId) {
        MmDataset dataset = datasets.getDatasetOrNotFound(datasetId);
        MmDatasetVersion version = requireOwnedVersion(datasetId, versionId);
        if (List.of("DRAFT", "UPLOADING").contains(version.getVersionStatus())) {
            throw ExceptionUtils.badRequest("版本原始快照尚未就绪，不能回填索引");
        }
        UUID taskId = outbox.enqueueSnapshot(dataset.getOrgId(), datasetId, versionId,
                version.getRawCommitId(), SecurityUtils.getUserId());
        Map<String, Object> task = jdbc.queryForMap("""
                SELECT task_id AS "taskId",task_status AS status,stage,attempt_count AS "attemptCount",
                  max_attempts AS "maxAttempts",error_message AS error,created_at AS "createdAt",updated_at AS "updatedAt"
                FROM mm_ingest_task WHERE org_id=? AND task_id=?
                """, dataset.getOrgId(), taskId);
        return task;
    }

    private MmDatasetVersion requireOwnedVersion(UUID datasetId, UUID versionId) {
        datasets.getDatasetOrNotFound(datasetId);
        MmDatasetVersion version = versions.getVersionOrNotFound(versionId);
        if (!datasetId.equals(version.getDatasetId())) {
            throw ExceptionUtils.badRequest("dataset 与 version 不匹配");
        }
        return version;
    }
}
