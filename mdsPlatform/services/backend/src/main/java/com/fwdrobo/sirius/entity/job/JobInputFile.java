package com.fwdrobo.sirius.entity.job;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class JobInputFile {
    private UUID jobId;
    private UUID repoId;
    // Nullable: DB sentinel '*ALL*' is converted to null by mapper SQL.
    private String logicalPath;
}
