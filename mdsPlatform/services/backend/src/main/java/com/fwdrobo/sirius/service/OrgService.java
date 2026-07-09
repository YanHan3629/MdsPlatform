package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.dto.PageRes;
import com.fwdrobo.sirius.dto.permission.OrgReq;
import com.fwdrobo.sirius.dto.permission.OrgResponse;
import com.fwdrobo.sirius.dto.user.UserInfo;
import com.fwdrobo.sirius.entity.permission.Org;
import com.fwdrobo.sirius.entity.user.UserLicenseType;
import com.fwdrobo.sirius.mapper.OrgMapper;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class OrgService {
    private static final int ACTIVE_ORG_STATUS = 1;

    private final OrgMapper orgMapper;
    private final UserService userService;

    public OrgService(OrgMapper orgMapper, UserService userService) {
        this.orgMapper = orgMapper;
        this.userService = userService;
    }

    public Org get(UUID orgId) {
        Org org = orgMapper.selectById(orgId);
        if (org == null) {
            throw ExceptionUtils.badRequest("org不存在");
        }
        return org;
    }

    @Transactional
    public UUID create(OrgReq orgReq) {
        Org exists = orgMapper.selectByName(orgReq.getOrgName());
        if (exists != null) {
            throw ExceptionUtils.badRequest("orgName已存在");
        }
        Org org = new Org();
        org.setOrgName(orgReq.getOrgName());
        org.setOrgStatus(orgReq.getOrgStatus());
        org.setLicenseType(UserLicenseType.fromNullable(orgReq.getLicenseType()).name());
        org.setCreatedAt(OffsetDateTime.now());
        org.setCreatedBy(SecurityUtils.getUserId());

        return orgMapper.insert(org);
    }

    @Transactional
    public void update(UUID orgId, OrgReq orgReq) {
        Org org = orgMapper.selectById(orgId);
        if (org == null) {
            throw ExceptionUtils.badRequest("权限不存在");
        }
        org.setOrgName(orgReq.getOrgName());
        org.setOrgStatus(orgReq.getOrgStatus());
        if (orgReq.getLicenseType() != null) {
            org.setLicenseType(UserLicenseType.fromNullable(orgReq.getLicenseType()).name());
        } else {
            org.setLicenseType(null);
        }
        org.setUpdatedAt(OffsetDateTime.now());
        org.setUpdatedBy(SecurityUtils.getUserId());
        orgMapper.update(org);
    }

    @Transactional
    public void delete(UUID orgId) {
        if (orgId == null) return;

        orgMapper.deleteById(orgId);
    }

    /**
     * 分页查询可用组织列表。
     */
    public PageRes<OrgResponse> listPage(int page, int size) {
        long total = orgMapper.countByStatus(ACTIVE_ORG_STATUS);
        if (total == 0) {
            return new PageRes<>(0, 0, page, size, List.of());
        }

        int offset = Math.max(0, (page - 1) * size);
        List<Org> items = orgMapper.selectPageByStatus(ACTIVE_ORG_STATUS, offset, size);
        items = items != null ? items : List.of();

        int totalPages = (int) Math.ceil((double) total / size);
        if (totalPages > 0 && page > totalPages) {
            throw ExceptionUtils.notFound("页数超过上限");
        }

        List<OrgResponse> responses = items.stream()
                .map(this::toResponse)
                .toList();
        return new PageRes<>(total, totalPages, page, size, responses);
    }

    /**
     * 将组织实体转换为响应对象。
     */
    private OrgResponse toResponse(Org org) {
        OrgResponse response = new OrgResponse();
        response.setOrgId(org.getOrgId());
        response.setOrgName(org.getOrgName());
        response.setOrgStatus(org.getOrgStatus());
        response.setLicenseType(org.getLicenseType());
        response.setCreatedAt(org.getCreatedAt());
        response.setUpdatedAt(org.getUpdatedAt());
        response.setCreatedBy(toUserInfo(org.getCreatedBy()));
        response.setUpdatedBy(toUserInfo(org.getUpdatedBy()));
        return response;
    }

    /**
     * 将用户ID转换为用户信息。
     */
    private UserInfo toUserInfo(UUID userId) {
        if (userId == null) {
            return null;
        }
        return new UserInfo(userId, userService.resolveUsername(userId));
    }

    public List<Org> getAllOrgs() {
        return orgMapper.selectAll(ACTIVE_ORG_STATUS);
    }
}
