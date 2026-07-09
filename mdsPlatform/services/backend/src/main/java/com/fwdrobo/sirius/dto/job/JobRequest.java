package com.fwdrobo.sirius.dto.job;

import com.fwdrobo.sirius.validation.ValidLogicalPath;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record JobRequest(
        @NotBlank String jobName,             // jobId 为空时必填；否则可选（你想允许改名也行）
        @NotBlank String jobCategory,             // import/export/transform（创建时必填；更新时可选）
        @NotEmpty(message = "inputs 不能为空")
        @Size(max = 50, message = "inputs 最多支持 50 个 repo")
        List<@NotNull @Valid Input> inputs,
        @NotEmpty(message = "outputRepoIds 不能为空")
        List<@NotNull UUID> outputRepoIds,   // 本次选择的 outputs（覆盖默认）
        String image,
        String cmd,
        Boolean devMode,
        Map<String, Object> params  // 写入 job.params（可选）
) {
    public record Input(
            @NotNull UUID repoId,
            @NotNull
            @Size(max = 100, message = "单个 repo 的 paths 最多支持 100 条")
            List<@ValidLogicalPath String> paths
    ) {
    }
}
