package com.fwdrobo.sirius.dto.job;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record JobRunQuery(
        @Min(1) Integer page,
        @Min(1) @Max(100) Integer size,
        String dockerStatus,
        String containerStatus,
        String containerId,
        String containerName
) {
    public JobRunQuery {
        page = page == null ? 1 : page;
        size = size == null ? 20 : size;
    }
}
