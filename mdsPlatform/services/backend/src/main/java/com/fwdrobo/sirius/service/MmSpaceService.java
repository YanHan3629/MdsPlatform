package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.dto.PageRes;
import com.fwdrobo.sirius.dto.mm.MmSpaceReq;
import com.fwdrobo.sirius.dto.mm.MmSpaceResp;
import com.fwdrobo.sirius.entity.mm.MmSpace;
import com.fwdrobo.sirius.handler.JsonbTypeHandler;
import com.fwdrobo.sirius.mapper.MmSpaceMapper;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MmSpaceService {

    private final MmSpaceMapper mmSpaceMapper;

    public MmSpaceService(MmSpaceMapper mmSpaceMapper) {
        this.mmSpaceMapper = mmSpaceMapper;
    }

    @Transactional
    public MmSpaceResp create(MmSpaceReq req) {
        UUID orgId = SecurityUtils.getUserOrgId();
        if (orgId == null) {
            throw ExceptionUtils.badRequest("当前用户未绑定组织");
        }
        MmSpace existed = mmSpaceMapper.selectByOrgIdAndName(orgId, req.spaceName().trim());
        if (existed != null) {
            throw ExceptionUtils.conflict("space 已存在: " + req.spaceName());
        }
        MmSpace space = new MmSpace();
        space.setSpaceId(UUID.randomUUID());
        space.setSpaceName(req.spaceName().trim());
        space.setDescription(req.description());
        space.setOrgId(orgId);
        space.setOwnerUserId(SecurityUtils.getUserId());
        space.setVisibility("PRIVATE");
        space.setStatus("ACTIVE");
        space.setMeta(JsonbTypeHandler.toJsonNode(Map.of()));
        mmSpaceMapper.insert(space);
        return toResp(space);
    }

    public MmSpaceResp getById(UUID spaceId) {
        return toResp(getSpaceOrNotFound(spaceId));
    }

    public MmSpace getSpaceOrNotFound(UUID spaceId) {
        MmSpace space = mmSpaceMapper.selectById(spaceId);
        if (space == null) {
            throw ExceptionUtils.notFound("space 不存在: " + spaceId);
        }
        UUID currentOrgId = SecurityUtils.getUserOrgId();
        if (currentOrgId != null && !currentOrgId.equals(space.getOrgId())) {
            throw ExceptionUtils.forbidden("无权访问该 space");
        }
        return space;
    }

    public PageRes<MmSpaceResp> list(Integer page, Integer size) {
        UUID orgId = SecurityUtils.getUserOrgId();
        if (orgId == null) {
            throw ExceptionUtils.badRequest("当前用户未绑定组织");
        }
        int pageOrDefault = page == null ? 1 : page;
        int sizeOrDefault = size == null ? 20 : size;
        int offset = (pageOrDefault - 1) * sizeOrDefault;
        long total = mmSpaceMapper.countByOrgId(orgId);
        int totalPages = total == 0 ? 0 : (int) ((total + sizeOrDefault - 1) / sizeOrDefault);
        List<MmSpaceResp> items = mmSpaceMapper.selectByOrgId(orgId, sizeOrDefault, offset).stream().map(this::toResp).toList();
        return new PageRes<>(total, totalPages, pageOrDefault, sizeOrDefault, items);
    }

    private MmSpaceResp toResp(MmSpace space) {
        return new MmSpaceResp(space.getSpaceId(), space.getSpaceName(), space.getDescription(), space.getOrgId(), space.getOwnerUserId(), space.getVisibility(), space.getStatus(), space.getCreatedAt(), space.getUpdatedAt());
    }
}
