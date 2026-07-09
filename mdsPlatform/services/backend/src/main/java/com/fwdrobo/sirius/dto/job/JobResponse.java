package com.fwdrobo.sirius.dto.job;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fwdrobo.sirius.dto.user.UserInfo;
import com.fwdrobo.sirius.entity.job.JobCategory;
import com.fwdrobo.sirius.entity.job.RunStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record JobResponse(
        UUID jobId,
        String jobName,
        JobCategory jobCategory,
        String image,
        String cmd,
        JsonNode params,
        UserInfo createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        JsonNode outputRepoIds,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<Input> inputs,
        RunStatus lastRunStatus  // 最近一次运行状态，未运行过时为 null
) {
    public record Input(
            UUID repoId,
            String repoName,
            // Empty list means repo-wide input (all files in this repo).
            @JsonInclude(JsonInclude.Include.NON_NULL)
            List<String> paths
    ) {
    }
}
