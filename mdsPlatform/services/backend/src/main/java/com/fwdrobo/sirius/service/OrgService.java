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
    private final com.fwdrobo.sirius.security.AuthorizationService authorization;
    private final com.fwdrobo.sirius.security.IdentitySessionService sessions;
    private final com.fwdrobo.sirius.security.AuthorizationAuditService audit;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public OrgService(OrgMapper orgMapper, UserService userService,
            com.fwdrobo.sirius.security.AuthorizationService authorization,
            com.fwdrobo.sirius.security.IdentitySessionService sessions,
            com.fwdrobo.sirius.security.AuthorizationAuditService audit,org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.authorization=authorization;this.sessions=sessions;this.audit=audit;this.jdbc=jdbc;
        this.orgMapper = orgMapper;
        this.userService = userService;
    }

    public Org get(UUID orgId) {
        var actor=authorization.actor();
        if(!actor.platformAdmin()&&!actor.orgId().equals(orgId))throw ExceptionUtils.notFound("ENTERPRISE_NOT_AVAILABLE");
        Org org = orgMapper.selectById(orgId);
        if (org == null) {
            throw ExceptionUtils.badRequest("org不存在");
        }
        return org;
    }

    @Transactional
    public UUID create(OrgReq orgReq) {
        authorization.lockIdentityMutation();authorization.requirePlatformManagement("identity.enterprise.manage");
        validateRequest(orgReq);
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

        UUID id=orgMapper.insert(org);
        enterpriseEvent(id,"ENTERPRISE_CREATED",orgReq.getOrgStatus());return id;
    }

    @Transactional
    public void update(UUID orgId, OrgReq orgReq) {
        authorization.lockIdentityMutation();authorization.requirePlatformManagement("identity.enterprise.manage");
        validateRequest(orgReq);checkVersion(orgId,orgReq.getExpectedVersion());
        var actor=authorization.actor();
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
        jdbc.update("UPDATE org SET permission_version=permission_version+1 WHERE org_id=?",orgId);
        revokeEnterprise(orgId);
        audit.record(actor.userId(),actor.orgId(),"identity.enterprise.manage","ENTERPRISE",orgId.toString(),"SUCCESS",
            "ENTERPRISE_UPDATED",java.util.Map.of(),null,java.util.Map.of("state",orgReq.getOrgStatus()));
    }

    @Transactional
    public void delete(UUID orgId) { delete(orgId,null); }
    @Transactional
    public void delete(UUID orgId,Long expectedVersion) {
        authorization.lockIdentityMutation();authorization.requirePlatformManagement("identity.enterprise.manage");checkVersion(orgId,expectedVersion);
        var actor=authorization.actor();
        jdbc.update("UPDATE org SET org_status=0,permission_version=permission_version+1 WHERE org_id=?",orgId);
        revokeEnterprise(orgId);
        audit.record(actor.userId(),actor.orgId(),"identity.enterprise.manage","ENTERPRISE",orgId.toString(),"SUCCESS",
            "ENTERPRISE_DISABLED_HISTORY_RETAINED",java.util.Map.of(),null,java.util.Map.of("state",0));
    }
    private void checkVersion(UUID id,Long expected) {
        var rows=jdbc.queryForList("SELECT permission_version FROM org WHERE org_id=?",Long.class,id);
        if(rows.size()!=1)throw ExceptionUtils.notFound("ENTERPRISE_NOT_AVAILABLE");
        if(expected==null)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PRECONDITION_REQUIRED,"EXPECTED_VERSION_REQUIRED");
        if(!expected.equals(rows.get(0)))throw ExceptionUtils.conflict("ENTERPRISE_VERSION_CONFLICT");
    }
    private void validateRequest(OrgReq req) {
        if(req==null||req.getOrgName()==null||req.getOrgName().isBlank()||req.getOrgName().length()>128||
            req.getOrgStatus()==null||!java.util.Set.of(0,1).contains(req.getOrgStatus()))throw ExceptionUtils.badRequest("ENTERPRISE_FIELDS_INVALID");
    }
    private void revokeEnterprise(UUID id) {
        for(UUID uid:jdbc.queryForList("SELECT user_id FROM fwd_user WHERE org_id=? AND account_state<>'CLOSED'",UUID.class,id))
            sessions.revokeAll(uid,"ENTERPRISE_POLICY_CHANGED");
        jdbc.update("UPDATE ds_auth_initialization SET policy_version=policy_version+1,updated_at=now() WHERE singleton");
    }
    private void enterpriseEvent(UUID id,String reason,Integer state) {
        var actor=authorization.actor();
        audit.record(actor.userId(),actor.orgId(),"identity.enterprise.manage","ENTERPRISE",id.toString(),"SUCCESS",reason,
            java.util.Map.of(),null,java.util.Map.of("state",state));
    }

    /**
     * 分页查询可用组织列表。
     */
    public PageRes<OrgResponse> listPage(int page, int size) {
        authorization.requirePlatformManagement("identity.enterprise.manage");
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
        response.setPermissionVersion(org.getPermissionVersion());
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
        var actor=authorization.actor();
        if(actor.platformAdmin())return orgMapper.selectAll(ACTIVE_ORG_STATUS);
        return java.util.List.of(get(actor.orgId()));
    }
}
