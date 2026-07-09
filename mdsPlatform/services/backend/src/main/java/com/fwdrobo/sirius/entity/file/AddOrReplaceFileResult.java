package com.fwdrobo.sirius.entity.file;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AddOrReplaceFileResult {
    private UUID fileId;
    // 是否为本次新插入（依赖 PostgreSQL 的 xmax=0 语义）
    private boolean inserted;
}
