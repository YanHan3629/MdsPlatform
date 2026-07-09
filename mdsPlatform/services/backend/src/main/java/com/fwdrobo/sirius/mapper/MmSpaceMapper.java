package com.fwdrobo.sirius.mapper;

import com.fwdrobo.sirius.entity.mm.MmSpace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

@Mapper
public interface MmSpaceMapper {
    int insert(MmSpace space);

    MmSpace selectById(@Param("spaceId") UUID spaceId);

    MmSpace selectByOrgIdAndName(@Param("orgId") UUID orgId, @Param("spaceName") String spaceName);

    List<MmSpace> selectByOrgId(@Param("orgId") UUID orgId, @Param("limit") int limit, @Param("offset") int offset);

    long countByOrgId(@Param("orgId") UUID orgId);
}
