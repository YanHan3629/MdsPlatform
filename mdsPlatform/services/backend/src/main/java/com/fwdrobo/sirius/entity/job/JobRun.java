package com.fwdrobo.sirius.entity.job;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
public class JobRun {

    // 核心标识
    private UUID runId;
    private UUID jobId;
    private UUID orgId;

    // 业务状态
    private RunStatus runStatus = RunStatus.QUEUED;

    // 生命周期
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;

    // 失败信息
    private String errorMessage;
    private String logsUri;
    private JsonNode metrics;

    // 审计
    private UUID createdBy;
    private OffsetDateTime createdAt = OffsetDateTime.now();
    private Integer reservedGpu;
    private BigDecimal reservedCpu;
    private Integer reservedMemoryMb;
    private Integer runtimeQuotaReleased;

    // 容器身份
    private String containerId;
    private String containerName;
    private String dockerHost;

    // 容器状态
    private ContainerStatus containerStatus;
    private String dockerStatus;
    private Integer exitCode;
    private OffsetDateTime lastSeenAt;

}
