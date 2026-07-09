package com.fwdrobo.sirius.mapper;

import com.fwdrobo.sirius.entity.mm.MmSearchLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MmSearchLogMapper {
    int insert(MmSearchLog searchLog);
}
