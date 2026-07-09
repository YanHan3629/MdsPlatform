package com.fwdrobo.sirius.entity.job;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class Job {
    private UUID jobId;
    private String jobName;

    private JobCategory jobCategory;

    // 运行配置
    private String image;
    private String cmd;

    // JSONB: 推荐用 Map 承载（也可以换成 JsonNode 或 String）
    private JsonNode params;

    private UUID createdBy;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    JsonNode inputRepoIds;
    JsonNode outputRepoIds;

}
