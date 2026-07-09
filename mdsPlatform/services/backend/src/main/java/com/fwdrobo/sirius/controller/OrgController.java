package com.fwdrobo.sirius.controller;

import com.fwdrobo.sirius.dto.PageRes;
import com.fwdrobo.sirius.dto.permission.OrgQuery;
import com.fwdrobo.sirius.dto.permission.OrgReq;
import com.fwdrobo.sirius.dto.permission.OrgResponse;
import com.fwdrobo.sirius.dto.user.OrgUserResp;
import com.fwdrobo.sirius.entity.permission.LicenseQuotaPolicy;
import com.fwdrobo.sirius.entity.permission.Org;
import com.fwdrobo.sirius.entity.permission.OrgStorageUsage;
import com.fwdrobo.sirius.service.OrgService;
import com.fwdrobo.sirius.service.TrialLimitService;
import com.fwdrobo.sirius.service.UserService;
import com.fwdrobo.sirius.util.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/orgs")
public class OrgController {

    private final OrgService orgService;
    private final UserService userService;
    private final TrialLimitService trialLimitService;

    public OrgController(OrgService orgService, UserService userService, TrialLimitService trialLimitService) {
        this.orgService = orgService;
        this.userService = userService;
        this.trialLimitService = trialLimitService;
    }

    @GetMapping("/{orgId}")
    public Org get(@PathVariable UUID orgId) {
        return orgService.get(orgId);
    }

    /**
     * 分页查询组织列表。
     */
    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public PageRes<OrgResponse> listOrgs(@Validated @ModelAttribute OrgQuery query) {
        return orgService.listPage(query.page(), query.size());
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> create(@RequestBody OrgReq req) {
        UUID orgId = orgService.create(req);
        return Map.of("message", "组织创建成功",
                "orgId", orgId);
    }

    @PatchMapping("/{orgId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> update(@PathVariable UUID orgId, @RequestBody OrgReq req) {
        orgService.update(orgId, req);
        return Map.of("message", "组织修改成功",
                "orgId", orgId);
    }

    @DeleteMapping("/{orgId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public Map<String, Object> delete(@PathVariable UUID orgId) {
        orgService.delete(orgId);
        return Map.of("message", "组织删除成功",
                "orgId", orgId);
    }

    /**
     * 查询组织下的用户列表，包含角色信息。
     */
    @GetMapping("/{orgId}/users")
    public List<OrgUserResp> getUsers(@PathVariable UUID orgId) {
        return userService.getUsersByOrg(orgId);
    }

    /**
     * 查询当前登录用户所属组织的授权类型与配额策略。
     */
    @GetMapping("/license-quota")
    public Map<String, Object> getLicenseQuota() {
        List<LicenseQuotaPolicy> policies = trialLimitService.allPolicies();
        return Map.of(
                "success", true,
                "licenseQuotaPolicy", policies
        );
    }

    /**
     * 查询当前登录用户所属组织的资源用量。
     */
    @GetMapping("/storage-usage")
    public Map<String, Object> getCurrentOrgStorageUsage() {
        UUID orgId = SecurityUtils.getUserOrgId();
        OrgStorageUsage usage = trialLimitService.currentOrgStorageUsage(orgId);
        LicenseQuotaPolicy policy = trialLimitService.currentOrgQuotaPolicy(orgId);
        return Map.of(
                "orgId", orgId,
                "resourceQuota", trialLimitService.buildResourceQuota(usage, policy)
        );
    }

}
