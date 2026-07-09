package com.fwdrobo.sirius.dto.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fwdrobo.sirius.dto.user.UserInfo;
import com.fwdrobo.sirius.entity.job.RunStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RunResponse(
        UUID runId,
        UUID jobId,
        // 业务状态
        RunStatus runStatus,
        // 生命周期
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        // 失败信息
        String errorMessage,
        String logsUri,
        JsonNode metrics,
        // 审计
        UserInfo createdBy,
        OffsetDateTime createdAt,
        String containerId,
        String containerName

) {
}
